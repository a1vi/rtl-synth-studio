package com.example.domain.synth

import java.util.Locale
import kotlin.math.abs

enum class StimulusPattern(val label: String) {
  AUTO("Auto"),
  COUNTER("Count"),
  TOGGLE("Toggle"),
  PULSE_HIGH("Pulse"),
  LOW("0"),
  HIGH("1"),
  ;

  fun next(): StimulusPattern {
    val all = entries
    return all[(ordinal + 1) % all.size]
  }
}

enum class WaveformRadix(val label: String) {
  HEX("HEX"),
  DEC("DEC"),
  BIN("BIN"),
}

data class SignalTrace(
  val name: String,
  val direction: PortDirection?,
  val width: Int,
  val isClock: Boolean,
  val isReset: Boolean,
  val isSequentialReg: Boolean,
  val values: List<Long>,
) {
  fun formatValue(cycleIndex: Int, radix: WaveformRadix): String {
    val raw = values.getOrElse(cycleIndex) { 0L }
    val masked = RtlSimulator.maskValue(raw, width)
    return when (radix) {
      WaveformRadix.HEX -> {
        if (width == 1) (masked and 1L).toString()
        else {
          val hexDigits = ((width + 3) / 4).coerceAtLeast(1)
          "0x" + masked.toString(16).uppercase(Locale.ROOT).padStart(hexDigits, '0')
        }
      }
      WaveformRadix.DEC -> masked.toString(10)
      WaveformRadix.BIN -> masked.toString(2).padStart(width.coerceAtMost(16), '0')
    }
  }
}

data class SimulationResult(
  val moduleName: String,
  val cycleCount: Int,
  val clockPort: String?,
  val resetPort: String?,
  val traces: List<SignalTrace>,
  val warnings: List<String> = emptyList(),
)

/**
 * Cycle-accurate on-device Verilog RTL functional simulator.
 * Evaluates continuous `assign`s, combinational `always` (`if`/`case`/`for`),
 * sequential `always @(posedge clk ...)` flip-flops, and hierarchical submodules.
 */
object RtlSimulator {

  fun simulate(
    design: ParsedVerilogDesign,
    cycleCount: Int = 16,
    patternOverrides: Map<String, StimulusPattern> = emptyMap(),
    customValueOverrides: Map<Pair<String, Int>, Long> = emptyMap(),
  ): SimulationResult {
    val top = design.topModule
    val safeCycles = cycleCount.coerceIn(4, 32)
    val warnings = mutableListOf<String>()

    val clockPort = detectClockPort(top)
    val (resetPort, resetActiveLow) = detectResetPort(top)

    val widths = linkedMapOf<String, Int>()
    top.ports.forEach { widths[it.name] = it.width }
    top.logicSignals.forEach { widths.putIfAbsent(it.name, it.width) }

    val seqTargets = mutableSetOf<String>()
    top.alwaysBlocks.filter { it.isSequential }.forEach { block ->
      collectAssignedTargets(block.statements, seqTargets)
    }

    val signalValues = linkedMapOf<String, Long>()
    widths.keys.forEach { signalValues[it] = 0L }
    top.parameters.forEach { param -> signalValues[param.name] = param.value.toLong() }

    val traceCollectors = linkedMapOf<String, MutableList<Long>>()
    top.ports.forEach { p -> traceCollectors[p.name] = ArrayList(safeCycles) }
    top.internalSignals.take(12).forEach { sig ->
      traceCollectors.putIfAbsent(sig.name, ArrayList(safeCycles))
    }

    // Track submodule persistent states across cycles
    val subModuleStates = mutableMapOf<String, MutableMap<String, Long>>()

    for (cycle in 0 until safeCycles) {
      // 1. Apply primary input stimuli for this cycle
      top.inputPorts.forEach { port ->
        val custom = customValueOverrides[port.name to cycle]
        val v = when {
          port.name == clockPort -> (cycle % 2).toLong() // visual alternating edge parity; rising edge every cycle
          custom != null -> maskValue(custom, port.width)
          else -> {
            val pattern = patternOverrides[port.name] ?: StimulusPattern.AUTO
            generateStimulusValue(
              port = port,
              cycle = cycle,
              pattern = pattern,
              isReset = port.name == resetPort,
              resetActiveLow = resetActiveLow,
            )
          }
        }
        signalValues[port.name] = maskValue(v, port.width)
      }

      // 2. Converge combinational logic (assigns + always_comb + submodules)
      evaluateCombinationalNetwork(
        design = design,
        module = top,
        signalValues = signalValues,
        widths = widths,
        subModuleStates = subModuleStates,
      )

      // 3. Record cycle waveform sample (before clock edge updates registered Q for next cycle)
      traceCollectors.forEach { (sigName, list) ->
        val w = widths[sigName] ?: 1
        list += maskValue(signalValues[sigName] ?: 0L, w)
      }

      // 4. Rising clock edge: evaluate sequential always blocks with simultaneous non-blocking commit
      val nextRegUpdates = linkedMapOf<String, Long>()
      top.alwaysBlocks.filter { it.isSequential }.forEach { block ->
        val blockNext = linkedMapOf<String, Long>()
        execStmts(
          stmts = block.statements,
          env = signalValues,
          widths = widths,
          blockingOut = blockNext,
          nonBlockingOut = nextRegUpdates,
          isSequential = true,
        )
      }
      nextRegUpdates.forEach { (name, value) ->
        val w = widths[name] ?: 1
        signalValues[name] = maskValue(value, w)
      }
    }

    val traces = traceCollectors.map { (name, vals) ->
      val port = top.ports.firstOrNull { it.name == name }
      val w = widths[name] ?: 1
      SignalTrace(
        name = name,
        direction = port?.direction,
        width = w,
        isClock = name == clockPort,
        isReset = name == resetPort,
        isSequentialReg = name in seqTargets,
        values = vals,
      )
    }

    if (traces.isEmpty()) {
      warnings += "Module '${top.name}' has no observable ports or signals to trace."
    }

    return SimulationResult(
      moduleName = top.name,
      cycleCount = safeCycles,
      clockPort = clockPort,
      resetPort = resetPort,
      traces = traces,
      warnings = warnings,
    )
  }

  fun simulateManualNetlist(
    report: SynthesisReport,
    cycleCount: Int = 16,
    patternOverrides: Map<String, StimulusPattern> = emptyMap(),
    customValueOverrides: Map<Pair<String, Int>, Long> = emptyMap(),
  ): SimulationResult {
    val safeCycles = cycleCount.coerceIn(4, 32)
    val inPorts = report.ports.filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
    val outPorts = report.ports.filter { it.direction == PortDirection.OUTPUT }
    val clockPort = inPorts.firstOrNull { it.name.lowercase(Locale.ROOT).contains("clk") }?.name
    val resetPort = inPorts.firstOrNull { it.name.lowercase(Locale.ROOT).contains("rst") }?.name
    val resetLow = resetPort?.lowercase(Locale.ROOT)?.endsWith("_n") == true

    val netValues = linkedMapOf<String, Long>()
    val regState = linkedMapOf<String, Long>()
    val collectors = linkedMapOf<String, MutableList<Long>>()
    report.ports.forEach { collectors[it.name] = ArrayList(safeCycles) }

    val combNodes = report.nodes.filter { !it.gateType.isPort && !it.gateType.isSequential }
    val seqNodes = report.nodes.filter { it.gateType.isSequential }

    for (cycle in 0 until safeCycles) {
      inPorts.forEach { port ->
        val custom = customValueOverrides[port.name to cycle]
        val v = when {
          port.name == clockPort -> (cycle % 2).toLong()
          custom != null -> maskValue(custom, port.width)
          else -> generateStimulusValue(
            port = port,
            cycle = cycle,
            pattern = patternOverrides[port.name] ?: StimulusPattern.AUTO,
            isReset = port.name == resetPort,
            resetActiveLow = resetLow,
          )
        }
        netValues[port.name] = maskValue(v, port.width)
      }
      seqNodes.forEach { seq ->
        netValues[seq.outputNet] = regState[seq.outputNet] ?: 0L
      }

      // Propagate combinational gates
      repeat(combNodes.size.coerceIn(2, 12)) {
        combNodes.forEach { node ->
          val w = node.bitWidth
          val a = resolveNetOperand(node.inputs.getOrNull(0), netValues)
          val b = resolveNetOperand(node.inputs.getOrNull(1), netValues)
          val c = resolveNetOperand(node.inputs.getOrNull(2), netValues)
          val out = when (node.gateType) {
            GateType.CONST -> 0L
            GateType.INV -> a.inv()
            GateType.BUF -> a
            GateType.AND2 -> a and b
            GateType.OR2 -> a or b
            GateType.NAND2 -> (a and b).inv()
            GateType.NOR2 -> (a or b).inv()
            GateType.XOR2 -> a xor b
            GateType.MUX2 -> if ((a and 1L) != 0L) b else c
            GateType.ADD_CELL -> a + b
            GateType.SUB_CELL -> a - b
            GateType.MUL_CELL -> a * b
            GateType.CMP_CELL -> if (a == b) 1L else 0L
            GateType.LUT4, GateType.LUT6, GateType.CARRY4 -> a xor b
            else -> a
          }
          netValues[node.outputNet] = maskValue(out, w)
        }
      }

      outPorts.forEach { out ->
        val driverNode = report.nodes.firstOrNull { !it.gateType.isPort && it.outputNet == out.name }
        if (driverNode == null) {
          val outNode = report.nodes.firstOrNull { it.gateType == GateType.OUTPUT_PORT && it.outputNet == out.name }
          val src = outNode?.inputs?.firstOrNull()
          if (src != null && src != out.name) {
            netValues[out.name] = netValues[src] ?: 0L
          }
        }
      }

      collectors.forEach { (name, list) ->
        val w = report.ports.firstOrNull { it.name == name }?.width ?: 1
        list += maskValue(netValues[name] ?: 0L, w)
      }

      seqNodes.forEach { seq ->
        val d = resolveNetOperand(seq.inputs.getOrNull(0), netValues)
        val rst = seq.inputs.getOrNull(2)?.let { resolveNetOperand(it, netValues) } ?: 0L
        regState[seq.outputNet] = if ((rst and 1L) != 0L) 0L else maskValue(d, seq.bitWidth)
      }
    }

    return SimulationResult(
      moduleName = report.moduleName,
      cycleCount = safeCycles,
      clockPort = clockPort,
      resetPort = resetPort,
      traces = collectors.map { (name, vals) ->
        val port = report.ports.firstOrNull { it.name == name }
        SignalTrace(
          name = name,
          direction = port?.direction,
          width = port?.width ?: 1,
          isClock = name == clockPort,
          isReset = name == resetPort,
          isSequentialReg = seqNodes.any { it.outputNet == name },
          values = vals,
        )
      },
    )
  }

  private fun evaluateCombinationalNetwork(
    design: ParsedVerilogDesign,
    module: ParsedVerilogModule,
    signalValues: MutableMap<String, Long>,
    widths: Map<String, Int>,
    subModuleStates: MutableMap<String, MutableMap<String, Long>>,
  ) {
    repeat(6) {
      var changed = false
      module.assignments.forEach { assign ->
        val w = widths[assign.target] ?: 1
        val rhs = evalExpr(assign.expr, signalValues, widths)
        val updated = applyAssignedValue(
          current = signalValues[assign.target] ?: 0L,
          rhs = rhs,
          targetWidth = w,
          msb = assign.targetMsb,
          lsb = assign.targetLsb,
        )
        if (signalValues[assign.target] != updated) {
          signalValues[assign.target] = updated
          changed = true
        }
      }

      module.alwaysBlocks.filter { !it.isSequential }.forEach { block ->
        val nextComb = linkedMapOf<String, Long>()
        execStmts(
          stmts = block.statements,
          env = signalValues,
          widths = widths,
          blockingOut = nextComb,
          nonBlockingOut = nextComb,
          isSequential = false,
        )
        nextComb.forEach { (k, v) ->
          val w = widths[k] ?: 1
          val masked = maskValue(v, w)
          if (signalValues[k] != masked) {
            signalValues[k] = masked
            changed = true
          }
        }
      }

      module.instances.forEach { inst ->
        val subMod = design.modules.firstOrNull { it.name == inst.moduleType } ?: return@forEach
        val subEnv = subModuleStates.getOrPut(inst.instanceName) {
          linkedMapOf<String, Long>().apply {
            subMod.ports.forEach { put(it.name, 0L) }
            subMod.logicSignals.forEach { putIfAbsent(it.name, 0L) }
            subMod.parameters.forEach { put(it.name, it.value.toLong()) }
          }
        }
        val subWidths = linkedMapOf<String, Int>().apply {
          subMod.ports.forEach { put(it.name, it.width) }
          subMod.logicSignals.forEach { putIfAbsent(it.name, it.width) }
        }
        subMod.ports.forEachIndexed { idx, port ->
          if (port.direction != PortDirection.OUTPUT) {
            val expr = inst.portConnections[port.name] ?: inst.positionalConnections.getOrNull(idx)
            if (expr != null) {
              subEnv[port.name] = maskValue(evalExpr(expr, signalValues, widths), port.width)
            }
          }
        }
        evaluateCombinationalNetwork(design, subMod, subEnv, subWidths, subModuleStates)
        subMod.ports.forEachIndexed { idx, port ->
          if (port.direction == PortDirection.OUTPUT) {
            val expr = inst.portConnections[port.name] ?: inst.positionalConnections.getOrNull(idx)
            if (expr is VerilogExpr.Signal) {
              val w = widths[expr.name] ?: port.width
              val outVal = maskValue(subEnv[port.name] ?: 0L, w)
              if (signalValues[expr.name] != outVal) {
                signalValues[expr.name] = outVal
                changed = true
              }
            }
          }
        }
      }

      if (!changed) return
    }
  }

  private fun execStmts(
    stmts: List<VerilogStmt>,
    env: Map<String, Long>,
    widths: Map<String, Int>,
    blockingOut: MutableMap<String, Long>,
    nonBlockingOut: MutableMap<String, Long>,
    isSequential: Boolean,
  ) {
    val localEnv = env.toMutableMap()
    blockingOut.forEach { (k, v) -> localEnv[k] = v }

    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> {
          val w = widths[stmt.target] ?: 1
          val rhs = evalExpr(stmt.expr, localEnv, widths)
          val baseVal = nonBlockingOut[stmt.target] ?: localEnv[stmt.target] ?: 0L
          val updated = applyAssignedValue(
            current = baseVal,
            rhs = rhs,
            targetWidth = w,
            msb = stmt.targetMsb,
            lsb = stmt.targetLsb,
          )
          if (stmt.nonBlocking && isSequential) {
            nonBlockingOut[stmt.target] = updated
          } else {
            blockingOut[stmt.target] = updated
            localEnv[stmt.target] = updated
            if (isSequential) nonBlockingOut[stmt.target] = updated
          }
        }
        is VerilogStmt.IfStmt -> {
          val cond = evalExpr(stmt.cond, localEnv, widths) != 0L
          val branch = if (cond) stmt.thenBranch else stmt.elseBranch
          if (branch.isNotEmpty()) {
            execStmts(branch, localEnv, widths, blockingOut, nonBlockingOut, isSequential)
          }
        }
        is VerilogStmt.CaseStmt -> {
          val selVal = evalExpr(stmt.selector, localEnv, widths)
          val matched = stmt.branches.firstOrNull { (pats, _) ->
            pats.any { evalExpr(it, localEnv, widths) == selVal }
          }?.second ?: stmt.defaultBranch
          if (matched.isNotEmpty()) {
            execStmts(matched, localEnv, widths, blockingOut, nonBlockingOut, isSequential)
          }
        }
      }
    }
  }

  private fun evalExpr(
    expr: VerilogExpr,
    env: Map<String, Long>,
    widths: Map<String, Int>,
  ): Long = when (expr) {
    is VerilogExpr.Const -> maskValue(expr.value.toLong(), expr.width)
    is VerilogExpr.Signal -> {
      val w = widths[expr.name] ?: 32
      maskValue(env[expr.name] ?: 0L, w)
    }
    is VerilogExpr.Slice -> {
      val raw = env[expr.name] ?: 0L
      val hi = maxOf(expr.msb, expr.lsb)
      val lo = minOf(expr.msb, expr.lsb).coerceAtLeast(0)
      val sliceWidth = abs(hi - lo) + 1
      maskValue(raw ushr lo, sliceWidth)
    }
    is VerilogExpr.Concat -> {
      var acc = 0L
      expr.parts.forEach { part ->
        val pw = inferExprWidth(part, widths)
        val pv = maskValue(evalExpr(part, env, widths), pw)
        acc = (acc shl pw.coerceAtMost(32)) or pv
      }
      acc
    }
    is VerilogExpr.Replication -> {
      val iw = inferExprWidth(expr.inner, widths)
      val iv = maskValue(evalExpr(expr.inner, env, widths), iw)
      var acc = 0L
      repeat(expr.count.coerceIn(1, 32)) {
        acc = (acc shl iw.coerceAtMost(32)) or iv
      }
      acc
    }
    is VerilogExpr.Unary -> {
      val w = inferExprWidth(expr.inner, widths)
      val v = maskValue(evalExpr(expr.inner, env, widths), w)
      when (expr.op) {
        "~" -> maskValue(v.inv(), w)
        "!" -> if (v == 0L) 1L else 0L
        "-" -> maskValue(-v, w)
        "&" -> if (v == maskValue(-1L, w)) 1L else 0L
        "|" -> if (v != 0L) 1L else 0L
        "^" -> (java.lang.Long.bitCount(v) % 2).toLong()
        else -> v
      }
    }
    is VerilogExpr.Binary -> {
      val w = maxOf(inferExprWidth(expr.left, widths), inferExprWidth(expr.right, widths))
      val l = evalExpr(expr.left, env, widths)
      val r = evalExpr(expr.right, env, widths)
      when (expr.op) {
        "+" -> maskValue(l + r, w)
        "-" -> maskValue(l - r, w)
        "*" -> maskValue(l * r, w * 2)
        "&" -> maskValue(l and r, w)
        "|" -> maskValue(l or r, w)
        "^" -> maskValue(l xor r, w)
        "~&" -> maskValue((l and r).inv(), w)
        "~|" -> maskValue((l or r).inv(), w)
        "^~", "~^" -> maskValue((l xor r).inv(), w)
        "&&" -> if (l != 0L && r != 0L) 1L else 0L
        "||" -> if (l != 0L || r != 0L) 1L else 0L
        "==" -> if (l == r) 1L else 0L
        "!=" -> if (l != r) 1L else 0L
        "<" -> if (l < r) 1L else 0L
        "<=" -> if (l <= r) 1L else 0L
        ">" -> if (l > r) 1L else 0L
        ">=" -> if (l >= r) 1L else 0L
        "<<" -> maskValue(l shl r.toInt().coerceIn(0, 62), w)
        ">>" -> maskValue(l ushr r.toInt().coerceIn(0, 62), w)
        else -> 0L
      }
    }
    is VerilogExpr.Ternary -> {
      if (evalExpr(expr.cond, env, widths) != 0L) evalExpr(expr.thenExpr, env, widths)
      else evalExpr(expr.elseExpr, env, widths)
    }
  }

  private fun inferExprWidth(expr: VerilogExpr, widths: Map<String, Int>): Int = when (expr) {
    is VerilogExpr.Const -> expr.width
    is VerilogExpr.Signal -> widths[expr.name] ?: 1
    is VerilogExpr.Slice -> abs(expr.msb - expr.lsb) + 1
    is VerilogExpr.Concat -> expr.parts.sumOf { inferExprWidth(it, widths) }.coerceAtLeast(1)
    is VerilogExpr.Replication -> (expr.count * inferExprWidth(expr.inner, widths)).coerceAtLeast(1)
    is VerilogExpr.Unary -> if (expr.op in setOf("!", "&", "|", "^")) 1 else inferExprWidth(expr.inner, widths)
    is VerilogExpr.Binary -> if (expr.op in setOf("==", "!=", "<", "<=", ">", ">=", "&&", "||")) 1
    else maxOf(inferExprWidth(expr.left, widths), inferExprWidth(expr.right, widths))
    is VerilogExpr.Ternary -> maxOf(inferExprWidth(expr.thenExpr, widths), inferExprWidth(expr.elseExpr, widths))
  }

  private fun applyAssignedValue(
    current: Long,
    rhs: Long,
    targetWidth: Int,
    msb: Int?,
    lsb: Int?,
  ): Long {
    if (msb == null || lsb == null) return maskValue(rhs, targetWidth)
    val hi = maxOf(msb, lsb)
    val lo = minOf(msb, lsb).coerceAtLeast(0)
    val sliceW = (hi - lo + 1).coerceIn(1, 62)
    val sliceMask = maskValue(-1L, sliceW) shl lo
    val cleared = current and sliceMask.inv()
    val inserted = (maskValue(rhs, sliceW) shl lo) and sliceMask
    return maskValue(cleared or inserted, targetWidth)
  }

  private fun collectAssignedTargets(stmts: List<VerilogStmt>, out: MutableSet<String>) {
    stmts.forEach { s ->
      when (s) {
        is VerilogStmt.Assign -> out += s.target
        is VerilogStmt.IfStmt -> {
          collectAssignedTargets(s.thenBranch, out)
          collectAssignedTargets(s.elseBranch, out)
        }
        is VerilogStmt.CaseStmt -> {
          s.branches.forEach { (_, body) -> collectAssignedTargets(body, out) }
          collectAssignedTargets(s.defaultBranch, out)
        }
      }
    }
  }

  private fun detectClockPort(module: ParsedVerilogModule): String? {
    module.alwaysBlocks.firstOrNull { it.isSequential }?.clockName?.let { return it }
    return module.inputPorts.firstOrNull {
      val low = it.name.lowercase(Locale.ROOT)
      low == "clk" || low == "clock" || low.contains("clk")
    }?.name
  }

  private fun detectResetPort(module: ParsedVerilogModule): Pair<String?, Boolean> {
    val seqWithRst = module.alwaysBlocks.firstOrNull { it.isSequential && it.resetName != null }
    if (seqWithRst != null) {
      return seqWithRst.resetName to seqWithRst.resetActiveLow
    }
    val rstPort = module.inputPorts.firstOrNull {
      val low = it.name.lowercase(Locale.ROOT)
      low == "rst" || low == "rst_n" || low == "reset" || low == "reset_n" || low.contains("rst")
    }
    val activeLow = rstPort?.name?.lowercase(Locale.ROOT)?.endsWith("_n") == true
    return rstPort?.name to activeLow
  }

  private fun generateStimulusValue(
    port: RtlPort,
    cycle: Int,
    pattern: StimulusPattern,
    isReset: Boolean,
    resetActiveLow: Boolean,
  ): Long {
    val lowName = port.name.lowercase(Locale.ROOT)
    val effective = if (pattern == StimulusPattern.AUTO) {
      when {
        isReset -> StimulusPattern.PULSE_HIGH
        lowName == "en" || lowName == "enable" || lowName.startsWith("valid") || lowName == "cin" ->
          StimulusPattern.HIGH
        port.width == 1 -> StimulusPattern.TOGGLE
        else -> StimulusPattern.COUNTER
      }
    } else {
      pattern
    }

    val raw = when (effective) {
      StimulusPattern.LOW -> 0L
      StimulusPattern.HIGH -> if (port.width == 1) 1L else maskValue(-1L, port.width)
      StimulusPattern.PULSE_HIGH -> {
        val active = cycle < 2
        if (isReset && resetActiveLow) {
          if (active) 0L else 1L
        } else {
          if (active) 1L else 0L
        }
      }
      StimulusPattern.TOGGLE -> if (cycle < 2 && isReset) 1L else ((cycle / 2) % 2).toLong()
      StimulusPattern.COUNTER, StimulusPattern.AUTO -> {
        if (port.width == 1) (cycle % 2).toLong()
        else (cycle.toLong() * 3L + 1L)
      }
    }
    return maskValue(raw, port.width)
  }

  private fun resolveNetOperand(netName: String?, netValues: Map<String, Long>): Long {
    if (netName == null) return 0L
    if (netName == "1'b0" || netName == "0") return 0L
    if (netName == "1'b1" || netName == "1") return 1L
    return netValues[netName] ?: 0L
  }

  /**
   * Parses initial/#delay signal assignments from a Verilog/SystemVerilog testbench
   * into per-(signal, cycle) stimulus overrides for interactive simulation.
   */
  fun parseTestbenchOverrides(
    testbenchSource: String,
    cycleCount: Int = 16,
  ): Map<Pair<String, Int>, Long> {
    if (testbenchSource.isBlank()) return emptyMap()
    val result = mutableMapOf<Pair<String, Int>, Long>()
    val initialMatch = Regex("""(?is)\binitial\b\s*(?:begin)?(.*?)(?:\bend\b|\z)""").find(testbenchSource)
    val body = initialMatch?.groupValues?.get(1) ?: testbenchSource
    var currentCycle = 0
    val tokens = body.split(';', '\n')
    val delayRegex = Regex("""#\s*(\d+)""")
    val assignRegex = Regex("""^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*([0-9a-fA-FxXbBdDoOhH_'']+)""")
    tokens.forEach { rawStmt ->
      var stmt = rawStmt.trim()
      if (stmt.isEmpty() || stmt.startsWith("//") || stmt.startsWith("${'$'}")) return@forEach
      val dMatch = delayRegex.find(stmt)
      if (dMatch != null) {
        val ns = dMatch.groupValues[1].toIntOrNull() ?: 1
        currentCycle = (currentCycle + (ns / 2).coerceAtLeast(1)).coerceAtMost(cycleCount - 1)
        stmt = stmt.substring(dMatch.range.last + 1).trim()
      }
      val aMatch = assignRegex.find(stmt)
      if (aMatch != null) {
        val sig = aMatch.groupValues[1]
        val lit = parseLiteralLong(aMatch.groupValues[2])
        if (lit != null) {
          for (c in currentCycle until cycleCount) {
            result[sig to c] = lit
          }
        }
      }
    }
    return result
  }

  private fun parseLiteralLong(raw: String): Long? {
    val clean = raw.trim().replace("_", "")
    clean.toLongOrNull()?.let { return it }
    val sized = Regex("""^\d*'([bBoOdDhH])([0-9a-fA-F]+)$""").find(clean) ?: return null
    val radix = when (sized.groupValues[1].lowercase(Locale.ROOT)) {
      "b" -> 2
      "o" -> 8
      "h" -> 16
      else -> 10
    }
    return sized.groupValues[2].toLongOrNull(radix)
  }

  fun maskValue(value: Long, width: Int): Long {
    val safeW = width.coerceIn(1, 62)
    val mask = if (safeW >= 62) Long.MAX_VALUE else (1L shl safeW) - 1L
    return value and mask
  }
}
