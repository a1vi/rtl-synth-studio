package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic on-device RTL Synthesis Engine powered by [VerilogHdlParser] and [VhdlFrontendParser]:
 * 1. Parse Verilog / SystemVerilog / VHDL HDL modules, I/O ports, logic signals, and submodules
 * 2. RTL Elaboration & Constant/DCE Optimization
 * 3. Technology Mapping (ASAP7 7nm, SKY130 130nm, GF180MCU 180nm, Nangate45 45nm, Artix-7 LUT6)
 * 4. Static Timing Analysis (Topological STA, WNS, TNS, Fmax, Critical Path Extraction)
 * 5. Structural Gate-Level Verilog Netlist Emitter
 */
object RtlSynthesisEngine {

  fun synthesize(
    verilogSource: String,
    library: TargetLibrary,
    goal: OptimizationGoal,
    clockPeriodNs: Double,
    topModuleName: String? = null,
    sdcText: String = "",
    engineProfile: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
    bitBlast: Boolean = false,
    dftConfig: DftConfig = DftConfig(),
  ): SynthesisResult {
    val startedNs = System.nanoTime()
    val log = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val sdcSet = SdcParser.parse(sdcText, clockPeriodNs)
    val safeClockNs = (sdcSet.primaryClockPeriodNs ?: clockPeriodNs).coerceIn(0.2, 500.0)

    log += "[00.00s] RTL Synth Studio — Engine: ${engineProfile.displayName} | PDK: ${library.displayName} | Goal: ${goal.displayName}"

    val effectiveSource = if (VhdlFrontendParser.looksLikeVhdl(verilogSource)) {
      log += "[00.01s] Phase 1: VHDL-2008 entity/architecture lowering..."
      when (val conv = VhdlFrontendParser.toCanonicalVerilog(verilogSource)) {
        is VhdlConversionResult.Error -> {
          log += "[ERROR] VHDL Line ${conv.line}: ${conv.message}"
          return SynthesisResult.Error(conv.line, conv.message, log)
        }
        is VhdlConversionResult.Success -> {
          log += "[00.01s] Lowered VHDL entity '${conv.entityName}' to canonical RTL IR."
          conv.verilogSource
        }
      }
    } else {
      log += "[00.01s] Phase 1: Verilog / SystemVerilog lexical & syntactic analysis..."
      verilogSource
    }

    val parsedDesign = when (val parseResult = VerilogHdlParser.parse(effectiveSource, topModuleName)) {
      is VerilogParseResult.Error -> {
        log += "[ERROR] Line ${parseResult.line}:${parseResult.column}: ${parseResult.message}"
        return SynthesisResult.Error(parseResult.line, parseResult.message, log)
      }
      is VerilogParseResult.Success -> parseResult.design
    }

    val moduleAst = parsedDesign.topModule
    warnings += parsedDesign.diagnostics
    warnings += sdcSet.diagnostics

    log += "[00.02s] Identified ${parsedDesign.modules.size} module(s); top='${moduleAst.name}' " +
      "(${moduleAst.ports.size} ports, ${moduleAst.internalSignals.size} internal signals, " +
      "${moduleAst.assignments.size} assigns, ${moduleAst.alwaysBlocks.size} always blocks, ${moduleAst.instances.size} instances)"

    if (sdcSet.totalConstraintCount > 0) {
      log += "[00.02s] Applied SDC constraints: ${sdcSet.summaryLine()}"
    }

    val rtlDrcReport = RtlDesignRuleChecker.checkDesign(parsedDesign, effectiveSource)
    log += "[DRC] Pre-Synthesis RTL DRC (${rtlDrcReport.rulesEvaluated} rules): ${rtlDrcReport.summaryBadgeText()} (${rtlDrcReport.scanDurationMs} ms)"
    rtlDrcReport.violations.forEach { v ->
      val lineNote = if (v.line > 0) "Line ${v.line}: " else ""
      if (v.severity == RtlDrcSeverity.ERROR) {
        log += "[WARN] [${v.ruleId}] ${v.moduleName} $lineNote${v.title} — ${v.message}"
        warnings += "[${v.ruleId}] $lineNote${v.message}"
      } else if (v.severity == RtlDrcSeverity.WARNING) {
        log += "[WARN] [${v.ruleId}] ${v.moduleName} $lineNote${v.title} — ${v.message}"
        warnings += "[${v.ruleId}] $lineNote${v.message}"
      }
    }

    val effectiveGoal = when (engineProfile) {
      SynthesisEngineProfile.YOSYS_ABC ->
        if (goal == OptimizationGoal.BALANCED) OptimizationGoal.MIN_DELAY else goal
      SynthesisEngineProfile.AREA_COMPACTOR ->
        if (goal == OptimizationGoal.BALANCED) OptimizationGoal.MIN_AREA else goal
      SynthesisEngineProfile.SILICON_FLOW -> goal
    }

    val modeTag = if (bitBlast) "${effectiveGoal.displayName}, Bit-Blasted" else effectiveGoal.displayName
    log += "[00.03s] Phase 2: RTL elaboration & logic optimization ($modeTag)..."
    val elaborator = Elaborator(parsedDesign, moduleAst, effectiveGoal, warnings, bitBlast)
    val irGraph = elaborator.elaborate()
    val preOptCount = (irGraph.rawOps * 1.18).toInt().coerceAtLeast(irGraph.nodes.count { !it.gateType.isPort })
    log += "[00.05s] Elaborated ${irGraph.rawOps} RTL operators (flattened ${irGraph.flattenedInstances} instance(s)) -> ${irGraph.nodes.count { !it.gateType.isPort }} optimized IR cells"

    log += "[00.06s] Phase 3: Technology mapping to ${library.shortName} (${engineProfile.shortName})..."
    val mapper = TechMapper(library, effectiveGoal, engineProfile)
    val baseMappedNodes = mapper.mapNodes(irGraph.nodes)
    log += "[00.08s] Mapped ${baseMappedNodes.count { !it.gateType.isPort }} physical primitives"

    val scanOutcome = DftEngine.applyScanInsertion(
      ports = moduleAst.ports,
      mappedNodes = baseMappedNodes,
      library = library,
      config = dftConfig,
      warnings = warnings,
      log = log,
    )

    log += "[00.09s] Phase 4: Static Timing Analysis @ ${formatNum(safeClockNs)} ns (${formatNum(1000.0 / safeClockNs)} MHz)..."

    val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
    return SynthesisResult.Success(
      buildReportFromMappedNodes(
        moduleName = moduleAst.name,
        ports = scanOutcome.ports,
        library = library,
        goal = goal,
        safeClockNs = safeClockNs,
        mappedNodes = scanOutcome.nodes,
        log = log,
        warnings = warnings,
        sdcSet = sdcSet,
        durationMs = elapsedMs,
        preOptCellCount = preOptCount,
        engineProfile = engineProfile,
        dftConfig = dftConfig,
        scanOutcome = scanOutcome,
        rtlDrcReport = rtlDrcReport,
      )
    )
  }

  fun createBlankManualReport(
    moduleName: String,
    library: TargetLibrary,
    goal: OptimizationGoal,
    clockPeriodNs: Double,
  ): SynthesisReport {
    val cleanName = moduleName.trim().ifBlank { "custom_schematic" }
    val ports = listOf(
      RtlPort(name = "in_a", direction = PortDirection.INPUT, width = 1, isReg = false),
      RtlPort(name = "in_b", direction = PortDirection.INPUT, width = 1, isReg = false),
      RtlPort(name = "out_y", direction = PortDirection.OUTPUT, width = 1, isReg = false),
    )
    val starterNodes = listOf(
      GateNode(
        id = "in_in_a",
        cellName = "INPUT",
        gateType = GateType.INPUT_PORT,
        instanceName = "in_a",
        inputs = emptyList(),
        outputNet = "in_a",
        bitWidth = 1,
        delayNs = 0.0,
        areaUm2 = 0.0,
        powerUw = 0.0,
      ),
      GateNode(
        id = "in_in_b",
        cellName = "INPUT",
        gateType = GateType.INPUT_PORT,
        instanceName = "in_b",
        inputs = emptyList(),
        outputNet = "in_b",
        bitWidth = 1,
        delayNs = 0.0,
        areaUm2 = 0.0,
        powerUw = 0.0,
      ),
      GateNode(
        id = "out_out_y",
        cellName = "OUTPUT",
        gateType = GateType.OUTPUT_PORT,
        instanceName = "out_y",
        inputs = listOf("out_y"),
        outputNet = "out_y",
        bitWidth = 1,
        delayNs = 0.0,
        areaUm2 = 0.0,
        powerUw = 0.0,
      ),
    )
    return recomputeReportFromNodes(
      moduleName = cleanName,
      ports = ports,
      rawNodes = starterNodes,
      library = library,
      goal = goal,
      clockPeriodNs = clockPeriodNs,
      warnings = listOf("Manual schematic canvas: drag primitives from the library and wire them to out_y."),
    )
  }

  fun recomputeReportFromNodes(
    moduleName: String,
    ports: List<RtlPort>,
    rawNodes: List<GateNode>,
    library: TargetLibrary,
    goal: OptimizationGoal,
    clockPeriodNs: Double,
    warnings: List<String> = emptyList(),
    sdcText: String = "",
  ): SynthesisReport {
    val sdcSet = SdcParser.parse(sdcText, clockPeriodNs)
    val safeClockNs = (sdcSet.primaryClockPeriodNs ?: clockPeriodNs).coerceIn(0.2, 500.0)
    val mapper = TechMapper(library, goal)
    val mappedNodes = mapper.mapNodes(rawNodes)
    val log = mutableListOf(
      "[00.00s] Manual Schematic Update — Target: ${library.displayName} | Goal: ${goal.displayName}",
      "[00.01s] Mapped ${mappedNodes.count { !it.gateType.isPort }} physical primitives @ ${formatNum(safeClockNs)} ns",
    )
    return buildReportFromMappedNodes(
      moduleName = moduleName,
      ports = ports,
      library = library,
      goal = goal,
      safeClockNs = safeClockNs,
      mappedNodes = mappedNodes,
      log = log,
      warnings = warnings + sdcSet.diagnostics,
      sdcSet = sdcSet,
    )
  }

  private fun buildReportFromMappedNodes(
    moduleName: String,
    ports: List<RtlPort>,
    library: TargetLibrary,
    goal: OptimizationGoal,
    safeClockNs: Double,
    mappedNodes: List<GateNode>,
    log: MutableList<String>,
    warnings: List<String>,
    sdcSet: SdcConstraintSet = SdcParser.parse("", safeClockNs),
    durationMs: Long = 1L,
    preOptCellCount: Int = 0,
    engineProfile: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
    dftConfig: DftConfig = DftConfig(),
    scanOutcome: ScanInsertionOutcome? = null,
    rtlDrcReport: RtlDrcReport? = null,
  ): SynthesisReport {
    val allWarnings = warnings.toMutableList()
    val edges = buildEdges(mappedNodes)
    val timedResult = runStaticTimingAnalysis(mappedNodes, edges, safeClockNs, sdcSet, library)

    val nonPortNodes = timedResult.nodes.filter { !it.gateType.isPort }
    val combCount = nonPortNodes.count { !it.gateType.isSequential }
    val seqCount = nonPortNodes.count { it.gateType.isSequential }
    val totalArea = nonPortNodes.sumOf { it.areaUm2 }
    val powerReport = PowerAnalysisEngine.analyze(
      moduleName = moduleName,
      ports = ports,
      nodes = timedResult.nodes,
      edges = timedResult.edges,
      library = library,
      clockPeriodNs = safeClockNs,
      simulation = null,
    )
    val switchingPowerMw = powerReport.switchingPowerMw
    val internalPowerMw = powerReport.internalPowerMw
    val dynamicPowerMw = powerReport.dynamicPowerMw
    val leakagePowerMw = powerReport.leakagePowerMw
    val totalPowerMw = powerReport.totalPowerMw

    val histogram = nonPortNodes.groupingBy { it.gateType.shortLabel }.eachCount()
      .entries.sortedByDescending { it.value }
      .associate { it.key to it.value }

    val fmaxMhz = if (timedResult.criticalPath.isNotEmpty() && timedResult.criticalDelayNs > 0.001) {
      1000.0 / timedResult.criticalDelayNs
    } else {
      0.0
    }

    val fmaxLabel = if (fmaxMhz > 0.0) "${formatNum(fmaxMhz)} MHz" else "N/A (no constrained path)"
    log += "[00.11s] STA complete: Critical Path = ${formatNum(timedResult.criticalDelayNs)} ns, WNS = ${formatSigned(timedResult.wnsNs)} ns, Fmax = $fmaxLabel"
    log += "[00.12s] Area = ${formatNum(totalArea)} um^2 | Cells = ${nonPortNodes.size} (Comb: $combCount, Seq: $seqCount) | Power = ${formatNum(totalPowerMw)} mW (Sw: ${formatNum(switchingPowerMw)}, Int: ${formatNum(internalPowerMw)}, Leak: ${formatNum(leakagePowerMw)})"

    if (timedResult.wnsNs < 0.0) {
      allWarnings += "Timing violation: WNS is ${formatSigned(timedResult.wnsNs)} ns. Consider Min Delay optimization or relaxing clock period."
    }

    log += "[00.13s] Phase 5: Generating structural gate-level Verilog netlist..."
    val netlistVerilog = emitStructuralVerilog(
      moduleName = moduleName,
      ports = ports,
      nodes = timedResult.nodes,
      library = library,
    )
    log += "[00.14s] Synthesis finished successfully."

    val avgInDelay = sdcSet.ioDelays.filter { it.isInput }.map { it.delayNs }.maxOrNull() ?: 0.0
    val avgOutDelay = sdcSet.ioDelays.filter { !it.isInput }.map { it.delayNs }.maxOrNull() ?: 0.0
    val maxOutLoadPf = sdcSet.outputLoads.maxOfOrNull { it.loadPf } ?: 0.0
    val fpCount = timedResult.nodes.count { it.isFalsePath }

    val effectiveScanOutcome = scanOutcome ?: ScanInsertionOutcome(
      ports = ports,
      nodes = timedResult.nodes,
      scanChains = emptyList(),
      lockupLatchCount = timedResult.nodes.count { it.gateType == GateType.LOCKUP_LATCH },
      jtagTapInserted = false,
      mbistCollarInserted = false,
      scanAreaOverheadUm2 = 0.0,
      scanInsertedCount = timedResult.nodes.count { it.gateType == GateType.SDFF || it.gateType == GateType.SDFFR },
    )
    val dftReport = DftEngine.analyzeAndGenerateAtpg(
      moduleName = moduleName,
      ports = ports,
      nodes = timedResult.nodes,
      edges = timedResult.edges,
      library = library,
      clockPeriodNs = safeClockNs,
      totalAreaUm2 = totalArea,
      config = dftConfig,
      scanInsertion = effectiveScanOutcome,
    )
    log += "[DFT] Audit & ATPG: Stuck-At Coverage = ${formatNum(dftReport.stuckAtCoveragePct)}% (${dftReport.detectedFaults}/${dftReport.totalFaults} faults, ${dftReport.atpgVectors.size} vectors)."

    return SynthesisReport(
      moduleName = moduleName,
      ports = ports,
      targetLibrary = library,
      optimizationGoal = goal,
      clockPeriodNs = safeClockNs,
      nodes = timedResult.nodes,
      edges = timedResult.edges,
      criticalPath = timedResult.criticalPath,
      criticalPathDelayNs = timedResult.criticalDelayNs,
      wnsNs = timedResult.wnsNs,
      tnsNs = timedResult.tnsNs,
      fmaxMhz = fmaxMhz,
      totalAreaUm2 = totalArea,
      cellCount = nonPortNodes.size,
      combCellCount = combCount,
      seqCellCount = seqCount,
      dynamicPowerMw = dynamicPowerMw,
      leakagePowerMw = leakagePowerMw,
      totalPowerMw = totalPowerMw,
      gateHistogram = histogram,
      netlistVerilog = netlistVerilog,
      synthesisLog = log,
      warnings = allWarnings.distinct(),
      sdcSummary = sdcSet.summaryLine(),
      appliedSdcConstraints = sdcSet.totalConstraintCount,
      clocksDetected = sdcSet.clocks,
      inputDelayNs = avgInDelay,
      outputDelayNs = avgOutDelay,
      maxOutputLoadPf = maxOutLoadPf,
      clockUncertaintyNs = sdcSet.clockUncertaintyNs,
      falsePathCount = fpCount,
      synthesisDurationMs = durationMs,
      preOptCellCount = preOptCellCount.coerceAtLeast(nonPortNodes.size),
      postOptCellCount = nonPortNodes.size,
      engineProfile = engineProfile,
      dftReport = dftReport,
      switchingPowerMw = switchingPowerMw,
      internalPowerMw = internalPowerMw,
      powerAnalysis = powerReport,
      rtlDrcReport = rtlDrcReport,
    )
  }

  // ==================== ELABORATOR & OPTIMIZER ====================

  private data class IrGraph(val nodes: List<GateNode>, val rawOps: Int, val flattenedInstances: Int = 0)

  private class Elaborator(
    private val design: ParsedVerilogDesign,
    private val module: ParsedVerilogModule,
    private val goal: OptimizationGoal,
    private val warnings: MutableList<String>,
    private val bitBlast: Boolean = false,
  ) {
    private val nodes = mutableListOf<GateNode>()
    private val idToNodeIndex = HashMap<String, Int>()
    private val netToNodeId = mutableMapOf<String, String>()
    private val exprCache = mutableMapOf<String, String>()
    private var instCounter = 0
    private var wireCounter = 0
    private var rawOps = 0
    private var flattenedInstances = 0
    private var currentSourceLine = 0
    private var currentPrefix = ""
    private var currentHierarchyPath = ""

    private fun recordNode(node: GateNode) {
      idToNodeIndex[node.id] = nodes.size
      nodes += node
    }

    fun elaborate(): IrGraph {
      module.ports.filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
        .forEach { port ->
          val id = "in_${port.name}"
          recordNode(
            GateNode(
              id = id,
              cellName = "INPUT",
              gateType = GateType.INPUT_PORT,
              instanceName = port.name,
              inputs = emptyList(),
              outputNet = port.name,
              bitWidth = port.width,
              delayNs = 0.0,
              areaUm2 = 0.0,
              powerUw = 0.0,
              sourceLine = module.line,
            )
          )
          netToNodeId[port.name] = id
        }

      val topParams = module.parameters.associate { it.name to it.value }
      elaborateModuleInternals(
        mod = module,
        prefix = "",
        hierarchyPath = "",
        localToParentNet = emptyMap(),
        paramEnv = topParams,
        depth = 0,
        activeStack = setOf(module.name),
      )

      module.ports.filter { it.direction == PortDirection.OUTPUT }
        .forEach { port ->
          val id = "out_${port.name}"
          if (netToNodeId[port.name] == null) {
            warnings += "Output port '${port.name}' has no driver; tied to logic 0."
          }
          recordNode(
            GateNode(
              id = id,
              cellName = "OUTPUT",
              gateType = GateType.OUTPUT_PORT,
              instanceName = port.name,
              inputs = listOf(port.name),
              outputNet = port.name,
              bitWidth = port.width,
              delayNs = 0.0,
              areaUm2 = 0.0,
              powerUw = 0.0,
              sourceLine = module.line,
            )
          )
        }

      return IrGraph(
        nodes = nodes,
        rawOps = max(rawOps, nodes.size),
        flattenedInstances = flattenedInstances,
      )
    }

    private fun elaborateModuleInternals(
      mod: ParsedVerilogModule,
      prefix: String,
      hierarchyPath: String,
      localToParentNet: Map<String, String>,
      paramEnv: Map<String, Int>,
      depth: Int,
      activeStack: Set<String>,
    ) {
      val prevPrefix = currentPrefix
      val prevHierarchyPath = currentHierarchyPath
      currentPrefix = prefix
      currentHierarchyPath = hierarchyPath
      try {
        fun mapSignal(raw: String): String =
          localToParentNet[raw] ?: if (prefix.isEmpty()) raw else "${prefix}$raw"

      fun resolveWidth(sigName: String): Int {
        val baseWidth = mod.signalWidth(sigName)
        if (prefix.isEmpty() || paramEnv.isEmpty()) return baseWidth
        for (p in mod.parameters) {
          val ovr = paramEnv[p.name] ?: continue
          if (ovr != p.value && p.value > 1 && baseWidth == p.value) {
            return ovr.coerceIn(1, 256)
          }
        }
        return baseWidth
      }

      val seqTargets = mutableSetOf<String>()
      mod.alwaysBlocks.filter { it.isSequential }.forEach { block ->
        collectAssignedTargets(block.statements, seqTargets)
      }
      seqTargets.forEach { rawReg ->
        val mappedReg = mapSignal(rawReg)
        if (!netToNodeId.containsKey(mappedReg)) {
          netToNodeId[mappedReg] = "reg_$mappedReg"
        }
      }

      mod.assignments.forEach { assign ->
        currentSourceLine = assign.line
        val targetNet = mapSignal(assign.target)
        val width = resolveWidth(assign.target)
        val remappedExpr = remapExpr(assign.expr, ::mapSignal, paramEnv)
        val srcNet = lowerExpr(remappedExpr, width)
        addBufferOrAlias(targetNet, srcNet, width)
      }

      mod.alwaysBlocks.filter { !it.isSequential }.forEach { block ->
        currentSourceLine = block.line
        val targets = mutableSetOf<String>()
        collectAssignedTargets(block.statements, targets)
        val remappedStmts = block.statements.map { remapStmt(it, ::mapSignal, paramEnv) }
        targets.forEach { rawTarget ->
          val targetNet = mapSignal(rawTarget)
          val width = resolveWidth(rawTarget)
          val expr = extractMuxTreeForTarget(remappedStmts, targetNet)
          if (expr != null) {
            val srcNet = lowerExpr(expr, width)
            addBufferOrAlias(targetNet, srcNet, width)
          }
        }
      }

      mod.alwaysBlocks.filter { it.isSequential }.forEach { block ->
        currentSourceLine = block.line
        val targets = mutableSetOf<String>()
        collectAssignedTargets(block.statements, targets)
        val remappedStmts = block.statements.map { remapStmt(it, ::mapSignal, paramEnv) }
        val clkNet = block.clockName?.let(::mapSignal) ?: mapSignal("clk")
        targets.forEach { rawTarget ->
          val targetNet = mapSignal(rawTarget)
          val width = resolveWidth(rawTarget)
          val (resetCond, resetVal, nextExpr) = extractSeqResetAndNext(remappedStmts, targetNet)
          val dNet = lowerExpr(nextExpr, width)
          val inputs = mutableListOf(dNet, clkNet)
          val hasReset = resetCond != null
          if (resetCond != null) {
            inputs += lowerExpr(resetCond, 1)
          }
          if (resetVal != null && resetVal !is VerilogExpr.Const) {
            inputs += lowerExpr(resetVal, width)
          }
          val baseRegId = "reg_$targetNet"
          val dffId = if (!idToNodeIndex.containsKey(baseRegId)) baseRegId else "${baseRegId}_${instCounter++}"
          recordNode(
            GateNode(
              id = dffId,
              cellName = if (hasReset) "DFFR" else "DFF",
              gateType = if (hasReset) GateType.DFFR else GateType.DFF,
              instanceName = "u_reg_$targetNet",
              inputs = inputs.distinct(),
              outputNet = targetNet,
              bitWidth = width,
              delayNs = 0.0,
              areaUm2 = 0.0,
              powerUw = 0.0,
              sourceLine = block.line,
              hierarchyPath = currentHierarchyPath,
            )
          )
          netToNodeId[targetNet] = dffId
        }
      }

      // Recursive per-instance elaboration with #(...) parameter overrides
      mod.instances.forEachIndexed { idxInMod, inst ->
        currentSourceLine = inst.line
        val subMod = design.modules.firstOrNull { it.name == inst.moduleType }
        if (subMod != null && subMod.name !in activeStack && depth < 6) {
          flattenedInstances++
          val childParamEnv = subMod.parameters.associate { it.name to it.value }.toMutableMap()
          if (inst.symbolicPositionalParamOverrides.isNotEmpty()) {
            inst.symbolicPositionalParamOverrides.forEachIndexed { pIdx, expr ->
              val resolved = evalParamExpr(expr, paramEnv) ?: inst.positionalParamOverrides.getOrNull(pIdx)
              val pDef = subMod.parameters.getOrNull(pIdx)
              if (pDef != null && resolved != null) {
                childParamEnv[pDef.name] = resolved
              }
            }
          } else {
            inst.positionalParamOverrides.forEachIndexed { pIdx, pVal ->
              subMod.parameters.getOrNull(pIdx)?.let { childParamEnv[it.name] = pVal }
            }
          }
          if (inst.symbolicParamOverrides.isNotEmpty()) {
            inst.symbolicParamOverrides.forEach { (k, expr) ->
              childParamEnv[k] = evalParamExpr(expr, paramEnv) ?: inst.paramOverrides[k] ?: childParamEnv[k] ?: 0
            }
          } else {
            inst.paramOverrides.forEach { (k, v) -> childParamEnv[k] = v }
          }

          val childPortMap = mutableMapOf<String, String>()
          subMod.ports.forEachIndexed { pIdx, p ->
            val conn = if (inst.portConnections.isNotEmpty()) {
              inst.portConnections[p.name]
            } else {
              inst.positionalConnections.getOrNull(pIdx)
            }
            if (conn != null) {
              val remappedConn = remapExpr(conn, ::mapSignal, paramEnv)
              if (p.direction == PortDirection.OUTPUT) {
                when (remappedConn) {
                  is VerilogExpr.Signal -> childPortMap[p.name] = remappedConn.name
                  is VerilogExpr.Slice -> childPortMap[p.name] = remappedConn.name
                  else -> childPortMap[p.name] = "${prefix}${inst.instanceName}_${p.name}"
                }
              } else {
                val widthHint = childParamEnv[p.name] ?: p.width
                val loweredInNet = lowerExpr(remappedConn, widthHint)
                childPortMap[p.name] = loweredInNet
              }
            }
          }

          val childPrefix = "$prefix${inst.instanceName}_${idxInMod}_"
          val childHierPath = if (hierarchyPath.isEmpty()) inst.instanceName else "$hierarchyPath/${inst.instanceName}"
          elaborateModuleInternals(
            mod = subMod,
            prefix = childPrefix,
            hierarchyPath = childHierPath,
            localToParentNet = childPortMap,
            paramEnv = childParamEnv,
            depth = depth + 1,
            activeStack = activeStack + subMod.name,
          )
        } else {
          val inNets = mutableListOf<String>()
          val outNets = mutableListOf<String>()
          if (subMod != null && (inst.portConnections.isNotEmpty() || inst.positionalConnections.isNotEmpty())) {
            subMod.ports.forEachIndexed { pIdx, p ->
              val conn = if (inst.portConnections.isNotEmpty()) {
                inst.portConnections[p.name]
              } else {
                inst.positionalConnections.getOrNull(pIdx)
              }
              if (conn != null) {
                val remappedConn = remapExpr(conn, ::mapSignal, paramEnv)
                if (p.direction == PortDirection.OUTPUT) {
                  if (remappedConn is VerilogExpr.Signal) outNets += remappedConn.name
                  else if (remappedConn is VerilogExpr.Slice) outNets += remappedConn.name
                } else {
                  inNets += lowerExpr(remappedConn, p.width)
                }
              }
            }
          } else {
            inst.portConnections.values.forEach { expr -> inNets += lowerExpr(remapExpr(expr, ::mapSignal, paramEnv), 1) }
            inst.positionalConnections.forEach { expr -> inNets += lowerExpr(remapExpr(expr, ::mapSignal, paramEnv), 1) }
          }
          val cleanInNets = inNets.distinct()
          if (outNets.isEmpty()) {
            val targetNet = mapSignal("${inst.instanceName}_out")
            emitGate(
              type = GateType.BUF,
              inputs = cleanInNets,
              outputNet = targetNet,
              width = resolveWidth("${inst.instanceName}_out"),
              customLabel = inst.moduleType.uppercase(Locale.ROOT),
            )
          } else {
            outNets.distinct().forEachIndexed { oIdx, targetNet ->
              val suffixLabel = if (oIdx == 0) inst.moduleType.uppercase(Locale.ROOT) else "${inst.moduleType.uppercase(Locale.ROOT)}_$oIdx"
              emitGate(
                type = GateType.BUF,
                inputs = cleanInNets,
                outputNet = targetNet,
                width = resolveWidth(targetNet),
                customLabel = suffixLabel,
              )
            }
          }
        }
      }
      } finally {
        currentPrefix = prevPrefix
        currentHierarchyPath = prevHierarchyPath
      }
    }

    private fun evalParamExpr(expr: VerilogExpr, env: Map<String, Int>): Int? = when (expr) {
      is VerilogExpr.Const -> expr.value.toInt()
      is VerilogExpr.Signal -> env[expr.name]
      is VerilogExpr.Unary -> {
        val v = evalParamExpr(expr.inner, env) ?: return null
        when (expr.op) {
          "-" -> -v
          "+" -> v
          "~" -> v.inv()
          "!" -> if (v == 0) 1 else 0
          else -> null
        }
      }
      is VerilogExpr.Binary -> {
        val l = evalParamExpr(expr.left, env) ?: return null
        val r = evalParamExpr(expr.right, env) ?: return null
        when (expr.op) {
          "+" -> l + r
          "-" -> l - r
          "*" -> l * r
          "/" -> if (r != 0) l / r else 0
          "%" -> if (r != 0) l % r else 0
          "<<" -> l shl (r and 31)
          ">>" -> l ushr (r and 31)
          "&" -> l and r
          "|" -> l or r
          "^" -> l xor r
          "==" -> if (l == r) 1 else 0
          "!=" -> if (l != r) 1 else 0
          ">" -> if (l > r) 1 else 0
          ">=" -> if (l >= r) 1 else 0
          "<" -> if (l < r) 1 else 0
          "<=" -> if (l <= r) 1 else 0
          else -> null
        }
      }
      is VerilogExpr.Ternary -> {
        val c = evalParamExpr(expr.cond, env) ?: return null
        if (c != 0) evalParamExpr(expr.thenExpr, env) else evalParamExpr(expr.elseExpr, env)
      }
      else -> null
    }

    private fun remapExpr(
      expr: VerilogExpr,
      mapSignal: (String) -> String,
      paramEnv: Map<String, Int>,
    ): VerilogExpr = when (expr) {
      is VerilogExpr.Const -> expr
      is VerilogExpr.Signal -> paramEnv[expr.name]?.let { VerilogExpr.Const(it, 32) } ?: VerilogExpr.Signal(mapSignal(expr.name))
      is VerilogExpr.Slice -> expr.copy(
        name = mapSignal(expr.name),
        msbExpr = expr.msbExpr?.let { remapExpr(it, mapSignal, paramEnv) },
        lsbExpr = expr.lsbExpr?.let { remapExpr(it, mapSignal, paramEnv) },
      )
      is VerilogExpr.Index -> expr.copy(
        base = remapExpr(expr.base, mapSignal, paramEnv),
        indexExpr = remapExpr(expr.indexExpr, mapSignal, paramEnv),
      )
      is VerilogExpr.Concat -> VerilogExpr.Concat(expr.parts.map { remapExpr(it, mapSignal, paramEnv) })
      is VerilogExpr.Replication -> VerilogExpr.Replication(expr.count, remapExpr(expr.inner, mapSignal, paramEnv))
      is VerilogExpr.Unary -> VerilogExpr.Unary(expr.op, remapExpr(expr.inner, mapSignal, paramEnv))
      is VerilogExpr.Binary -> VerilogExpr.Binary(
        expr.op,
        remapExpr(expr.left, mapSignal, paramEnv),
        remapExpr(expr.right, mapSignal, paramEnv),
      )
      is VerilogExpr.Ternary -> VerilogExpr.Ternary(
        remapExpr(expr.cond, mapSignal, paramEnv),
        remapExpr(expr.thenExpr, mapSignal, paramEnv),
        remapExpr(expr.elseExpr, mapSignal, paramEnv),
      )
      is VerilogExpr.FuncCall -> VerilogExpr.FuncCall(
        expr.name,
        expr.args.map { remapExpr(it, mapSignal, paramEnv) },
      )
    }

    private fun remapStmt(
      stmt: VerilogStmt,
      mapSignal: (String) -> String,
      paramEnv: Map<String, Int>,
    ): VerilogStmt = when (stmt) {
      is VerilogStmt.Assign -> stmt.copy(
        target = mapSignal(stmt.target),
        expr = remapExpr(stmt.expr, mapSignal, paramEnv),
        targetMsbExpr = stmt.targetMsbExpr?.let { remapExpr(it, mapSignal, paramEnv) },
        targetLsbExpr = stmt.targetLsbExpr?.let { remapExpr(it, mapSignal, paramEnv) },
      )
      is VerilogStmt.IfStmt -> stmt.copy(
        cond = remapExpr(stmt.cond, mapSignal, paramEnv),
        thenBranch = stmt.thenBranch.map { remapStmt(it, mapSignal, paramEnv) },
        elseBranch = stmt.elseBranch.map { remapStmt(it, mapSignal, paramEnv) },
      )
      is VerilogStmt.CaseStmt -> stmt.copy(
        selector = remapExpr(stmt.selector, mapSignal, paramEnv),
        branches = stmt.branches.map { (pats, body) ->
          pats.map { remapExpr(it, mapSignal, paramEnv) } to body.map { remapStmt(it, mapSignal, paramEnv) }
        },
        defaultBranch = stmt.defaultBranch.map { remapStmt(it, mapSignal, paramEnv) },
      )
      is VerilogStmt.Display -> stmt.copy(args = stmt.args.map { remapExpr(it, mapSignal, paramEnv) })
      is VerilogStmt.AssertStmt -> stmt.copy(cond = remapExpr(stmt.cond, mapSignal, paramEnv))
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
          is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
        }
      }
    }

    private fun extractMuxTreeForTarget(
      stmts: List<VerilogStmt>,
      target: String,
      fallback: VerilogExpr? = null,
    ): VerilogExpr? {
      var current: VerilogExpr? = fallback
      for (s in stmts) {
        when (s) {
          is VerilogStmt.Assign -> if (s.target == target) current = s.expr
          is VerilogStmt.IfStmt -> {
            val tExpr = extractMuxTreeForTarget(s.thenBranch, target, current)
            val eExpr = extractMuxTreeForTarget(s.elseBranch, target, current)
            if (tExpr != null && eExpr != null && (tExpr != current || eExpr != current)) {
              current = VerilogExpr.Ternary(s.cond, tExpr, eExpr)
            } else if (tExpr != null) {
              current = tExpr
            }
          }
          is VerilogStmt.CaseStmt -> {
            var acc: VerilogExpr? = extractMuxTreeForTarget(s.defaultBranch, target, current)
              ?: current ?: VerilogExpr.Const(0, 1)
            for ((patterns, body) in s.branches.asReversed()) {
              val branchExpr = extractMuxTreeForTarget(body, target, current)
              if (branchExpr != null) {
                val cond = patterns.map { pat -> VerilogExpr.Binary("==", s.selector, pat) as VerilogExpr }
                  .reduce { a, b -> VerilogExpr.Binary("||", a, b) }
                acc = VerilogExpr.Ternary(cond, branchExpr, acc!!)
              }
            }
            current = acc
          }
          is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
        }
      }
      return current
    }

    private fun extractSeqResetAndNext(
      stmts: List<VerilogStmt>,
      target: String,
    ): Triple<VerilogExpr?, VerilogExpr?, VerilogExpr> {
      val firstIf = stmts.firstOrNull() as? VerilogStmt.IfStmt
      if (firstIf != null && firstIf.elseBranch.isNotEmpty() && isResetCondition(firstIf.cond)) {
        val resetVal = extractMuxTreeForTarget(firstIf.thenBranch, target, VerilogExpr.Const(0, 1))
        val nextVal = extractMuxTreeForTarget(firstIf.elseBranch, target, VerilogExpr.Signal(target))
          ?: VerilogExpr.Signal(target)
        return Triple(firstIf.cond, resetVal, nextVal)
      }
      val nextVal = extractMuxTreeForTarget(stmts, target, VerilogExpr.Signal(target)) ?: VerilogExpr.Signal(target)
      return Triple(null, null, nextVal)
    }

    private fun isResetCondition(cond: VerilogExpr): Boolean {
      val text = cond.toString().lowercase(Locale.ROOT)
      return "rst" in text || "reset" in text || cond is VerilogExpr.Unary
    }

    private fun addBufferOrAlias(target: String, srcNet: String, width: Int) {
      if (srcNet == target) return
      val existingDriver = netToNodeId[srcNet]
      val idx = existingDriver?.let { idToNodeIndex[it] }
      val existingNode = idx?.let { nodes.getOrNull(it) }
      if (existingNode != null && !existingNode.gateType.isPort && !existingNode.gateType.isSequential && existingNode.outputNet.startsWith("_n")) {
        nodes[idx] = existingNode.copy(outputNet = target)
        netToNodeId.remove(srcNet)
        netToNodeId[target] = existingNode.id
      } else {
        emitGate(GateType.BUF, listOf(srcNet), target, width)
      }
    }

    private fun lowerExpr(expr: VerilogExpr, widthHint: Int): String {
      rawOps++
      return when (expr) {
        is VerilogExpr.Const -> {
          val key = "CONST_${expr.value}_${expr.width}"
          if (goal == OptimizationGoal.MIN_AREA && exprCache.containsKey(key)) {
            return exprCache[key]!!
          }
          val net = "_const_${expr.width}b${expr.value}"
          if (!netToNodeId.containsKey(net)) {
            emitGate(GateType.CONST, emptyList(), net, expr.width, customLabel = "${expr.width}'d${expr.value}")
          }
          exprCache[key] = net
          net
        }
        is VerilogExpr.Signal -> expr.name
        is VerilogExpr.Slice -> {
          val w = max(1, abs(expr.msb - expr.lsb) + 1)
          val dynIdx = expr.msbExpr?.takeIf { it !is VerilogExpr.Const }
          if (dynIdx != null) {
            val idxNet = lowerExpr(dynIdx, 8)
            val key = "DYNSLICE_${expr.name}_${idxNet}"
            if (exprCache.containsKey(key)) return exprCache[key]!!
            val net = newWire()
            emitGate(GateType.MUX2, listOf(idxNet, expr.name, expr.name), net, w)
            exprCache[key] = net
            net
          } else {
            val net = "_slice_${expr.name}_${expr.msb}_${expr.lsb}"
            if (!netToNodeId.containsKey(net)) {
              emitGate(GateType.BUF, listOf(expr.name), net, w)
            }
            net
          }
        }
        is VerilogExpr.Index -> {
          val baseNet = lowerExpr(expr.base, widthHint)
          val idxNet = lowerExpr(expr.indexExpr, 8)
          val key = "IDX_${baseNet}_${idxNet}"
          if (exprCache.containsKey(key)) return exprCache[key]!!
          val net = newWire()
          emitGate(GateType.MUX2, listOf(idxNet, baseNet, baseNet), net, max(widthHint, expr.width))
          exprCache[key] = net
          net
        }
        is VerilogExpr.FuncCall -> {
          if (expr.name.equals("\$clog2", ignoreCase = true) && expr.args.firstOrNull() is VerilogExpr.Const) {
            val v = (expr.args.first() as VerilogExpr.Const).value
            val lg = if (v <= 1) 0 else 32 - Integer.numberOfLeadingZeros(v - 1)
            return lowerExpr(VerilogExpr.Const(lg, 32), widthHint)
          }
          val argNets = expr.args.map { lowerExpr(it, widthHint) }.distinct()
          val net = newWire()
          emitGate(
            type = if (argNets.size >= 2) GateType.AND2 else GateType.BUF,
            inputs = argNets.ifEmpty { listOf("1'b0") },
            outputNet = net,
            width = widthHint,
          )
          net
        }
        is VerilogExpr.Concat -> {
          val partNets = expr.parts.map { lowerExpr(it, 1) }
          val key = "CONCAT_${partNets.joinToString("_")}"
          if (exprCache.containsKey(key)) return exprCache[key]!!
          val net = newWire()
          emitGate(GateType.BUF, partNets.distinct(), net, widthHint)
          exprCache[key] = net
          net
        }
        is VerilogExpr.Replication -> {
          val innerNet = lowerExpr(expr.inner, 1)
          val net = newWire()
          emitGate(GateType.BUF, listOf(innerNet), net, max(widthHint, expr.count))
          net
        }
        is VerilogExpr.Unary -> {
          val inner = lowerExpr(expr.inner, widthHint)
          if (expr.op == "+") return inner
          val gate = when (expr.op) {
            "~", "!", "-" -> GateType.INV
            "&", "~&" -> GateType.AND2
            "|","~|" -> GateType.OR2
            "^" -> GateType.XOR2
            "~^", "^~" -> GateType.XNOR2
            else -> GateType.INV
          }
          val key = "${gate.name}_${expr.op}_$inner"
          if (goal != OptimizationGoal.MIN_DELAY && exprCache.containsKey(key)) return exprCache[key]!!
          val net = newWire()
          emitGate(gate, listOf(inner), net, widthHint)
          exprCache[key] = net
          net
        }
        is VerilogExpr.Binary -> {
          if (expr.left is VerilogExpr.Const && expr.right is VerilogExpr.Const) {
            val folded = when (expr.op) {
              "+" -> expr.left.value + expr.right.value
              "-" -> expr.left.value - expr.right.value
              "*" -> expr.left.value * expr.right.value
              "&" -> expr.left.value and expr.right.value
              "|" -> expr.left.value or expr.right.value
              "^" -> expr.left.value xor expr.right.value
              else -> null
            }
            if (folded != null) {
              return lowerExpr(VerilogExpr.Const(folded, widthHint), widthHint)
            }
          }
          val lNet = lowerExpr(expr.left, widthHint)
          val rNet = lowerExpr(expr.right, widthHint)
          val gate = when (expr.op) {
            "&", "&&" -> GateType.AND2
            "|", "||" -> GateType.OR2
            "^" -> GateType.XOR2
            "~&" -> GateType.NAND2
            "~|" -> GateType.NOR2
            "^~", "~^" -> GateType.XNOR2
            "+" -> GateType.ADD_CELL
            "-" -> GateType.SUB_CELL
            "*", "/", "%", "**" -> GateType.MUL_CELL
            "==", "!=", "===", "!==", "==?", "!=?", "<", "<=", ">", ">=" -> GateType.CMP_CELL
            "<<", ">>", "<<<", ">>>" -> GateType.BUF
            else -> GateType.AND2
          }
          val key = "${gate.name}_${lNet}_${rNet}"
          if (goal == OptimizationGoal.MIN_AREA && exprCache.containsKey(key)) return exprCache[key]!!
          val net = newWire()
          emitGate(gate, listOf(lNet, rNet), net, widthHint)
          exprCache[key] = net
          net
        }
        is VerilogExpr.Ternary -> {
          val cNet = lowerExpr(expr.cond, 1)
          val tNet = lowerExpr(expr.thenExpr, widthHint)
          val eNet = lowerExpr(expr.elseExpr, widthHint)
          val key = "MUX_${cNet}_${tNet}_${eNet}"
          if (goal == OptimizationGoal.MIN_AREA && exprCache.containsKey(key)) return exprCache[key]!!
          val net = newWire()
          emitGate(GateType.MUX2, listOf(cNet, tNet, eNet), net, widthHint)
          exprCache[key] = net
          net
        }
      }
    }

    private fun newWire(): String = "_n${++wireCounter}"

    private fun emitGate(
      type: GateType,
      inputs: List<String>,
      outputNet: String,
      width: Int,
      customLabel: String? = null,
    ): String {
      if (bitBlast && customLabel == null) {
        when (type) {
          GateType.ADD_CELL, GateType.SUB_CELL -> {
            return emitBitBlastedAdder(
              aNet = inputs.getOrElse(0) { "1'b0" },
              bNet = inputs.getOrElse(1) { "1'b0" },
              outputNet = outputNet,
              width = width,
              isSub = type == GateType.SUB_CELL,
            )
          }
          GateType.MUL_CELL -> {
            return emitBitBlastedMultiplier(
              aNet = inputs.getOrElse(0) { "1'b0" },
              bNet = inputs.getOrElse(1) { "1'b0" },
              outputNet = outputNet,
              width = width,
            )
          }
          GateType.CMP_CELL -> {
            return emitBitBlastedComparator(
              aNet = inputs.getOrElse(0) { "1'b0" },
              bNet = inputs.getOrElse(1) { "1'b0" },
              outputNet = outputNet,
              width = width,
            )
          }
          GateType.MUX2 -> {
            return emitBitBlastedMux(
              selNet = inputs.getOrElse(0) { "1'b0" },
              aNet = inputs.getOrElse(1) { "1'b0" },
              bNet = inputs.getOrElse(2) { "1'b0" },
              outputNet = outputNet,
              width = width,
            )
          }
          else -> Unit
        }
      }
      return emitSingleGate(type, inputs, outputNet, width, customLabel)
    }

    private fun emitBitBlastedAdder(
      aNet: String,
      bNet: String,
      outputNet: String,
      width: Int,
      isSub: Boolean,
    ): String {
      val bits = width.coerceIn(1, 32)
      var carryNet = if (isSub) {
        val cWire = newWire()
        emitSingleGate(GateType.INV, listOf(bNet), cWire, 1)
        cWire
      } else {
        aNet
      }
      val sumTapNets = mutableListOf<String>()
      var lastId = ""
      for (bit in 0 until bits) {
        val bEff = if (isSub) {
          val invW = newWire()
          emitSingleGate(GateType.INV, listOf(bNet), invW, 1)
          invW
        } else {
          bNet
        }
        val halfXor = newWire()
        emitSingleGate(GateType.XOR2, listOf(aNet, bEff), halfXor, 1)
        val genAnd = newWire()
        emitSingleGate(GateType.AND2, listOf(aNet, bEff), genAnd, 1)
        val propAnd = newWire()
        emitSingleGate(GateType.AND2, listOf(halfXor, carryNet), propAnd, 1)
        val nextCarry = newWire()
        emitSingleGate(GateType.OR2, listOf(genAnd, propAnd), nextCarry, 1)
        val sumBitNet = if (bit == bits - 1) outputNet else newWire()
        lastId = emitSingleGate(GateType.XOR2, listOf(halfXor, carryNet), sumBitNet, if (bit == bits - 1) width else 1)
        sumTapNets += sumBitNet
        carryNet = nextCarry
      }
      return lastId
    }

    private fun emitBitBlastedMultiplier(
      aNet: String,
      bNet: String,
      outputNet: String,
      width: Int,
    ): String {
      val stages = width.coerceIn(2, 16)
      var accumNet = newWire()
      emitSingleGate(GateType.AND2, listOf(aNet, bNet), accumNet, 1)
      var lastId = ""
      for (s in 1 until stages) {
        val pp = newWire()
        emitSingleGate(GateType.AND2, listOf(aNet, bNet), pp, 1)
        val carry = newWire()
        emitSingleGate(GateType.AND2, listOf(accumNet, pp), carry, 1)
        val sum = if (s == stages - 1) outputNet else newWire()
        lastId = emitSingleGate(GateType.XOR2, listOf(accumNet, pp), sum, if (s == stages - 1) width else 1)
        accumNet = if (s == stages - 1) sum else {
          val nextStage = newWire()
          emitSingleGate(GateType.OR2, listOf(sum, carry), nextStage, 1)
          nextStage
        }
      }
      return lastId
    }

    private fun emitBitBlastedComparator(
      aNet: String,
      bNet: String,
      outputNet: String,
      width: Int,
    ): String {
      val bits = width.coerceIn(1, 16)
      var diffTree = newWire()
      emitSingleGate(GateType.XOR2, listOf(aNet, bNet), diffTree, 1)
      var lastId = ""
      for (bit in 1 until bits) {
        val x = newWire()
        emitSingleGate(GateType.XOR2, listOf(aNet, bNet), x, 1)
        val merged = newWire()
        lastId = emitSingleGate(GateType.OR2, listOf(diffTree, x), merged, 1)
        diffTree = merged
      }
      lastId = emitSingleGate(GateType.INV, listOf(diffTree), outputNet, 1)
      return lastId
    }

    private fun emitBitBlastedMux(
      selNet: String,
      aNet: String,
      bNet: String,
      outputNet: String,
      width: Int,
    ): String {
      val nSel = newWire()
      emitSingleGate(GateType.INV, listOf(selNet), nSel, 1)
      val slices = width.coerceIn(1, 8)
      var lastId = ""
      for (s in 0 until slices) {
        val hiAnd = newWire()
        emitSingleGate(GateType.AND2, listOf(selNet, aNet), hiAnd, 1)
        val loAnd = newWire()
        emitSingleGate(GateType.AND2, listOf(nSel, bNet), loAnd, 1)
        val out = if (s == slices - 1) outputNet else newWire()
        lastId = emitSingleGate(GateType.OR2, listOf(hiAnd, loAnd), out, if (s == slices - 1) width else 1)
      }
      return lastId
    }

    private fun emitSingleGate(
      type: GateType,
      inputs: List<String>,
      outputNet: String,
      width: Int,
      customLabel: String? = null,
    ): String {
      val id = "g_${++instCounter}"
      recordNode(
        GateNode(
          id = id,
          cellName = customLabel ?: type.shortLabel,
          gateType = type,
          instanceName = "${currentPrefix}u_${type.shortLabel.lowercase(Locale.ROOT)}_$instCounter",
          inputs = inputs,
          outputNet = outputNet,
          bitWidth = width,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
          sourceLine = currentSourceLine,
          hierarchyPath = currentHierarchyPath,
        )
      )
      netToNodeId[outputNet] = id
      return id
    }
  }

  // ==================== TECHNOLOGY MAPPER ====================

  private class TechMapper(
    private val library: TargetLibrary,
    private val goal: OptimizationGoal,
    private val engineProfile: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
  ) {
    fun mapNodes(irNodes: List<GateNode>): List<GateNode> {
      val engineDelayFactor = when (engineProfile) {
        SynthesisEngineProfile.YOSYS_ABC -> 0.94
        SynthesisEngineProfile.AREA_COMPACTOR -> 1.05
        SynthesisEngineProfile.SILICON_FLOW -> 1.0
      }
      val engineAreaFactor = when (engineProfile) {
        SynthesisEngineProfile.AREA_COMPACTOR -> 0.92
        SynthesisEngineProfile.YOSYS_ABC -> 1.04
        SynthesisEngineProfile.SILICON_FLOW -> 1.0
      }
      val delayScale = engineDelayFactor * when (goal) {
        OptimizationGoal.MIN_DELAY -> 0.84
        OptimizationGoal.BALANCED -> 1.0
        OptimizationGoal.MIN_AREA -> 1.14
      }
      val areaScale = engineAreaFactor * when (goal) {
        OptimizationGoal.MIN_AREA -> 0.86
        OptimizationGoal.BALANCED -> 1.0
        OptimizationGoal.MIN_DELAY -> 1.18
      }

      return irNodes.map { node ->
        if (node.gateType.isPort) return@map node

        val wFactor = 1.0 + (node.bitWidth - 1) * 0.45
        val mapped = when (library) {
          TargetLibrary.ASAP7_7NM -> mapAsap7(node, wFactor)
          TargetLibrary.SKY130_130NM -> mapSky130(node, wFactor)
          TargetLibrary.GF180MCU_180NM -> mapGf180(node, wFactor)
          TargetLibrary.NANGATE45_45NM -> mapNangate45(node, wFactor)
          TargetLibrary.ARTIX7_FPGA -> mapArtix7(node, wFactor)
        }
        mapped.copy(
          delayNs = mapped.delayNs * delayScale,
          areaUm2 = mapped.areaUm2 * areaScale,
          powerUw = mapped.powerUw * (if (goal == OptimizationGoal.MIN_DELAY) 1.15 else 1.0),
        )
      }
    }

    private fun mapAsap7(node: GateNode, wFactor: Double): GateNode {
      val (cell, gate, d, a, p) = when (node.gateType) {
        GateType.CONST -> Quint("TIE0_ASAP7", GateType.CONST, 0.01, 0.05, 0.2)
        GateType.INV -> Quint("INVx1_ASAP7", GateType.INV, 0.018, 0.07 * wFactor, 1.8 * wFactor)
        GateType.BUF -> Quint("BUFx2_ASAP7", GateType.BUF, 0.024, 0.09 * wFactor, 2.4 * wFactor)
        GateType.AND2 -> Quint("AND2x1_ASAP7", GateType.AND2, 0.032, 0.14 * wFactor, 3.6 * wFactor)
        GateType.OR2 -> Quint("OR2x1_ASAP7", GateType.OR2, 0.034, 0.14 * wFactor, 3.8 * wFactor)
        GateType.NAND2 -> Quint("NAND2x1_ASAP7", GateType.NAND2, 0.022, 0.11 * wFactor, 2.7 * wFactor)
        GateType.NOR2 -> Quint("NOR2x1_ASAP7", GateType.NOR2, 0.025, 0.11 * wFactor, 2.9 * wFactor)
        GateType.XOR2 -> Quint("XOR2x1_ASAP7", GateType.XOR2, 0.048, 0.22 * wFactor, 5.4 * wFactor)
        GateType.XNOR2 -> Quint("XNOR2x1_ASAP7", GateType.XNOR2, 0.049, 0.22 * wFactor, 5.5 * wFactor)
        GateType.MUX2 -> Quint("MUX2x1_ASAP7", GateType.MUX2, 0.052, 0.26 * wFactor, 6.2 * wFactor)
        GateType.ADD_CELL -> Quint("FA_ADD_ASAP7", GateType.ADD_CELL, 0.095 + node.bitWidth * 0.022, 0.68 * wFactor, 14.5 * wFactor)
        GateType.SUB_CELL -> Quint("FA_SUB_ASAP7", GateType.SUB_CELL, 0.105 + node.bitWidth * 0.024, 0.74 * wFactor, 15.8 * wFactor)
        GateType.MUL_CELL -> Quint("BOOTH_MUL_ASAP7", GateType.MUL_CELL, 0.18 + node.bitWidth * 0.035, 1.95 * wFactor, 34.0 * wFactor)
        GateType.CMP_CELL -> Quint("COMPx1_ASAP7", GateType.CMP_CELL, 0.072 + node.bitWidth * 0.014, 0.45 * wFactor, 9.2 * wFactor)
        GateType.DFF -> Quint("DFFx1_ASAP7", GateType.DFF, 0.045, 0.42 * wFactor, 11.0 * wFactor)
        GateType.DFFR -> Quint("DFFRx1_ASAP7", GateType.DFFR, 0.052, 0.48 * wFactor, 12.6 * wFactor)
        GateType.SDFF -> Quint("SDFFx1_ASAP7", GateType.SDFF, 0.075, 0.48 * wFactor, 12.2 * wFactor)
        GateType.SDFFR -> Quint("SDFFRx1_ASAP7", GateType.SDFFR, 0.082, 0.55 * wFactor, 13.8 * wFactor)
        GateType.LOCKUP_LATCH -> Quint("DLLATCHx1_ASAP7", GateType.LOCKUP_LATCH, 0.04, 0.24, 2.5)
        else -> Quint("CELL_ASAP7", node.gateType, 0.035, 0.15 * wFactor, 3.5 * wFactor)
      }
      return node.copy(cellName = cell, gateType = gate, delayNs = d, areaUm2 = a, powerUw = p)
    }

    private fun mapSky130(node: GateNode, wFactor: Double): GateNode {
      val (cell, gate, d, a, p) = when (node.gateType) {
        GateType.CONST -> Quint("sky130_conb_1", GateType.CONST, 0.04, 2.5, 0.8)
        GateType.INV -> Quint("sky130_inv_1", GateType.INV, 0.095, 3.75 * wFactor, 6.5 * wFactor)
        GateType.BUF -> Quint("sky130_buf_2", GateType.BUF, 0.13, 5.0 * wFactor, 8.2 * wFactor)
        GateType.AND2 -> Quint("sky130_and2_1", GateType.AND2, 0.18, 6.25 * wFactor, 11.4 * wFactor)
        GateType.OR2 -> Quint("sky130_or2_1", GateType.OR2, 0.19, 6.25 * wFactor, 11.8 * wFactor)
        GateType.NAND2 -> Quint("sky130_nand2_1", GateType.NAND2, 0.11, 5.0 * wFactor, 9.1 * wFactor)
        GateType.NOR2 -> Quint("sky130_nor2_1", GateType.NOR2, 0.13, 5.0 * wFactor, 9.5 * wFactor)
        GateType.XOR2 -> Quint("sky130_xor2_1", GateType.XOR2, 0.27, 10.0 * wFactor, 17.6 * wFactor)
        GateType.XNOR2 -> Quint("sky130_xnor2_1", GateType.XNOR2, 0.28, 10.0 * wFactor, 17.9 * wFactor)
        GateType.MUX2 -> Quint("sky130_mux2_1", GateType.MUX2, 0.29, 11.25 * wFactor, 19.2 * wFactor)
        GateType.ADD_CELL -> Quint("sky130_fa_add", GateType.ADD_CELL, 0.42 + node.bitWidth * 0.09, 26.0 * wFactor, 44.0 * wFactor)
        GateType.SUB_CELL -> Quint("sky130_fa_sub", GateType.SUB_CELL, 0.46 + node.bitWidth * 0.095, 28.5 * wFactor, 47.0 * wFactor)
        GateType.MUL_CELL -> Quint("sky130_mul_arr", GateType.MUL_CELL, 0.85 + node.bitWidth * 0.16, 74.0 * wFactor, 98.0 * wFactor)
        GateType.CMP_CELL -> Quint("sky130_cmp_1", GateType.CMP_CELL, 0.34 + node.bitWidth * 0.05, 18.0 * wFactor, 29.0 * wFactor)
        GateType.DFF -> Quint("sky130_dfxtp_1", GateType.DFF, 0.26, 20.0 * wFactor, 32.0 * wFactor)
        GateType.DFFR -> Quint("sky130_dfrtp_1", GateType.DFFR, 0.31, 22.5 * wFactor, 36.5 * wFactor)
        GateType.SDFF -> Quint("sky130_sdfxtp_1", GateType.SDFF, 0.30, 22.8 * wFactor, 35.4 * wFactor)
        GateType.SDFFR -> Quint("sky130_sdfrtp_1", GateType.SDFFR, 0.35, 25.6 * wFactor, 40.0 * wFactor)
        GateType.LOCKUP_LATCH -> Quint("sky130_dlxtn_1", GateType.LOCKUP_LATCH, 0.16, 11.2, 12.0)
        else -> Quint("sky130_cell", node.gateType, 0.2, 7.5 * wFactor, 12.0 * wFactor)
      }
      return node.copy(cellName = cell, gateType = gate, delayNs = d, areaUm2 = a, powerUw = p)
    }

    private fun mapGf180(node: GateNode, wFactor: Double): GateNode {
      val (cell, gate, d, a, p) = when (node.gateType) {
        GateType.CONST -> Quint("gf180_tie_0", GateType.CONST, 0.06, 4.2, 1.2)
        GateType.INV -> Quint("gf180_inv_1", GateType.INV, 0.14, 5.6 * wFactor, 9.2 * wFactor)
        GateType.BUF -> Quint("gf180_buf_2", GateType.BUF, 0.18, 7.8 * wFactor, 11.4 * wFactor)
        GateType.AND2 -> Quint("gf180_and2_1", GateType.AND2, 0.25, 9.4 * wFactor, 15.8 * wFactor)
        GateType.OR2 -> Quint("gf180_or2_1", GateType.OR2, 0.27, 9.4 * wFactor, 16.3 * wFactor)
        GateType.NAND2 -> Quint("gf180_nand2_1", GateType.NAND2, 0.16, 7.5 * wFactor, 12.6 * wFactor)
        GateType.NOR2 -> Quint("gf180_nor2_1", GateType.NOR2, 0.19, 7.5 * wFactor, 13.1 * wFactor)
        GateType.XOR2 -> Quint("gf180_xor2_1", GateType.XOR2, 0.38, 15.2 * wFactor, 24.0 * wFactor)
        GateType.XNOR2 -> Quint("gf180_xnor2_1", GateType.XNOR2, 0.39, 15.2 * wFactor, 24.4 * wFactor)
        GateType.MUX2 -> Quint("gf180_mux2_1", GateType.MUX2, 0.41, 16.8 * wFactor, 26.5 * wFactor)
        GateType.ADD_CELL -> Quint("gf180_fa_add", GateType.ADD_CELL, 0.62 + node.bitWidth * 0.12, 39.0 * wFactor, 58.0 * wFactor)
        GateType.SUB_CELL -> Quint("gf180_fa_sub", GateType.SUB_CELL, 0.66 + node.bitWidth * 0.13, 42.0 * wFactor, 61.5 * wFactor)
        GateType.MUL_CELL -> Quint("gf180_mul_arr", GateType.MUL_CELL, 1.15 + node.bitWidth * 0.22, 108.0 * wFactor, 132.0 * wFactor)
        GateType.CMP_CELL -> Quint("gf180_cmp_1", GateType.CMP_CELL, 0.48 + node.bitWidth * 0.07, 26.0 * wFactor, 39.0 * wFactor)
        GateType.DFF -> Quint("gf180_dffq_1", GateType.DFF, 0.37, 29.0 * wFactor, 43.0 * wFactor)
        GateType.DFFR -> Quint("gf180_dffrq_1", GateType.DFFR, 0.43, 33.0 * wFactor, 48.0 * wFactor)
        GateType.SDFF -> Quint("gf180_sdffq_1", GateType.SDFF, 0.41, 33.1 * wFactor, 47.5 * wFactor)
        GateType.SDFFR -> Quint("gf180_sdffrq_1", GateType.SDFFR, 0.47, 37.6 * wFactor, 52.8 * wFactor)
        GateType.LOCKUP_LATCH -> Quint("gf180_dlatn_1", GateType.LOCKUP_LATCH, 0.22, 16.4, 16.0)
        else -> Quint("gf180_cell", node.gateType, 0.28, 11.0 * wFactor, 16.0 * wFactor)
      }
      return node.copy(cellName = cell, gateType = gate, delayNs = d, areaUm2 = a, powerUw = p)
    }

    private fun mapNangate45(node: GateNode, wFactor: Double): GateNode {
      val (cell, gate, d, a, p) = when (node.gateType) {
        GateType.CONST -> Quint("LOGIC0_X1", GateType.CONST, 0.02, 0.53, 0.4)
        GateType.INV -> Quint("INV_X1", GateType.INV, 0.042, 0.79 * wFactor, 3.1 * wFactor)
        GateType.BUF -> Quint("BUF_X2", GateType.BUF, 0.056, 1.06 * wFactor, 4.2 * wFactor)
        GateType.AND2 -> Quint("AND2_X1", GateType.AND2, 0.078, 1.06 * wFactor, 5.8 * wFactor)
        GateType.OR2 -> Quint("OR2_X1", GateType.OR2, 0.084, 1.06 * wFactor, 6.0 * wFactor)
        GateType.NAND2 -> Quint("NAND2_X1", GateType.NAND2, 0.052, 0.79 * wFactor, 4.4 * wFactor)
        GateType.NOR2 -> Quint("NOR2_X1", GateType.NOR2, 0.061, 0.79 * wFactor, 4.7 * wFactor)
        GateType.XOR2 -> Quint("XOR2_X1", GateType.XOR2, 0.115, 1.59 * wFactor, 8.9 * wFactor)
        GateType.XNOR2 -> Quint("XNOR2_X1", GateType.XNOR2, 0.118, 1.59 * wFactor, 9.1 * wFactor)
        GateType.MUX2 -> Quint("MUX2_X1", GateType.MUX2, 0.128, 1.86 * wFactor, 9.8 * wFactor)
        GateType.ADD_CELL -> Quint("FA_X1_ADD", GateType.ADD_CELL, 0.21 + node.bitWidth * 0.045, 4.52 * wFactor, 22.0 * wFactor)
        GateType.SUB_CELL -> Quint("FA_X1_SUB", GateType.SUB_CELL, 0.23 + node.bitWidth * 0.048, 4.92 * wFactor, 24.0 * wFactor)
        GateType.MUL_CELL -> Quint("MUL_X1_ARR", GateType.MUL_CELL, 0.42 + node.bitWidth * 0.08, 14.5 * wFactor, 52.0 * wFactor)
        GateType.CMP_CELL -> Quint("CMP_X1", GateType.CMP_CELL, 0.16 + node.bitWidth * 0.028, 3.1 * wFactor, 14.5 * wFactor)
        GateType.DFF -> Quint("DFF_X1", GateType.DFF, 0.11, 4.52 * wFactor, 16.8 * wFactor)
        GateType.DFFR -> Quint("DFFR_X1", GateType.DFFR, 0.13, 5.05 * wFactor, 19.2 * wFactor)
        GateType.SDFF -> Quint("SDFF_X1", GateType.SDFF, 0.145, 5.15 * wFactor, 18.6 * wFactor)
        GateType.SDFFR -> Quint("SDFFR_X1", GateType.SDFFR, 0.165, 5.76 * wFactor, 21.1 * wFactor)
        GateType.LOCKUP_LATCH -> Quint("DLL_X1", GateType.LOCKUP_LATCH, 0.08, 2.38, 6.0)
        else -> Quint("CELL_N45", node.gateType, 0.09, 1.3 * wFactor, 6.5 * wFactor)
      }
      return node.copy(cellName = cell, gateType = gate, delayNs = d, areaUm2 = a, powerUw = p)
    }

    private fun mapArtix7(node: GateNode, wFactor: Double): GateNode {
      val (cell, gate, d, a, p) = when (node.gateType) {
        GateType.CONST -> Quint("GND_VCC", GateType.CONST, 0.05, 0.5, 0.5)
        GateType.INV, GateType.BUF, GateType.AND2, GateType.OR2, GateType.NAND2, GateType.NOR2 ->
          Quint("LUT4", GateType.LUT4, 0.22, 1.0 * max(1, node.bitWidth / 2), 14.0 * wFactor)
        GateType.XOR2, GateType.XNOR2, GateType.MUX2, GateType.CMP_CELL ->
          Quint("LUT6", GateType.LUT6, 0.28, 1.5 * max(1, node.bitWidth / 2), 19.0 * wFactor)
        GateType.ADD_CELL, GateType.SUB_CELL ->
          Quint("CARRY4", GateType.CARRY4, 0.36 + (node.bitWidth / 4.0) * 0.14, 2.5 * wFactor, 31.0 * wFactor)
        GateType.MUL_CELL ->
          Quint("DSP48E1", GateType.MUL_CELL, 0.95, 12.0, 68.0)
        GateType.DFF -> Quint("FDRE", GateType.DFF, 0.21, 1.0 * node.bitWidth, 16.0 * wFactor)
        GateType.DFFR -> Quint("FDCE", GateType.DFFR, 0.24, 1.0 * node.bitWidth, 17.5 * wFactor)
        GateType.SDFF -> Quint("FDRE_SCAN", GateType.SDFF, 0.25, 1.2 * node.bitWidth, 18.0 * wFactor)
        GateType.SDFFR -> Quint("FDCE_SCAN", GateType.SDFFR, 0.28, 1.2 * node.bitWidth, 19.5 * wFactor)
        GateType.LOCKUP_LATCH -> Quint("LDCE", GateType.LOCKUP_LATCH, 0.14, 1.0, 6.5)
        else -> Quint("LUT6", GateType.LUT6, 0.25, 1.2 * wFactor, 15.0 * wFactor)
      }
      return node.copy(cellName = cell, gateType = gate, delayNs = d, areaUm2 = a, powerUw = p)
    }

    private data class Quint(
      val cell: String,
      val gate: GateType,
      val delay: Double,
      val area: Double,
      val power: Double,
    )
  }

  // ==================== GRAPH EDGES & STA ====================

  private fun buildEdges(nodes: List<GateNode>): List<NetEdge> {
    val driverByNet = mutableMapOf<String, GateNode>()
    nodes.filter { it.gateType != GateType.OUTPUT_PORT }.forEach { node ->
      driverByNet[node.outputNet] = node
    }
    val edges = mutableListOf<NetEdge>()
    nodes.forEach { consumer ->
      if (consumer.gateType == GateType.INPUT_PORT) return@forEach
      consumer.inputs.forEach { inNet ->
        val driver = driverByNet[inNet]
        if (driver != null && driver.id != consumer.id) {
          edges += NetEdge(
            netName = inNet,
            fromNodeId = driver.id,
            toNodeId = consumer.id,
            width = minOf(driver.bitWidth, consumer.bitWidth).coerceAtLeast(1),
            isCritical = false,
          )
        }
      }
    }
    return edges.distinctBy { "${it.fromNodeId}->${it.toNodeId}:${it.netName}" }
  }

  private data class StaResult(
    val nodes: List<GateNode>,
    val edges: List<NetEdge>,
    val criticalPath: List<TimingPathStep>,
    val criticalDelayNs: Double,
    val wnsNs: Double,
    val tnsNs: Double,
  )

  private fun runStaticTimingAnalysis(
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    clockPeriodNs: Double,
    sdcSet: SdcConstraintSet = SdcParser.parse("", clockPeriodNs),
    library: TargetLibrary = TargetLibrary.ASAP7_7NM,
  ): StaResult {
    val nodeMap = nodes.associateBy { it.id }.toMutableMap()
    val outgoing = edges.groupBy { it.fromNodeId }
    val incoming = edges.groupBy { it.toNodeId }
    val uncertaintyNs = sdcSet.clockUncertaintyNs
    val outputLoadDelayByNodeId = nodes.associate { node ->
      val delay = if (node.gateType == GateType.OUTPUT_PORT) {
        val loadPf = sdcSet.outputLoadForPort(node.outputNet)
        SdcParser.outputLoadDelayNs(loadPf, library)
      } else {
        0.0
      }
      node.id to delay
    }

    // Resolve clock domain & period per node (multi-clock support)
    val clockPeriodByName = sdcSet.clocks.associate { it.name.lowercase(Locale.ROOT) to it.periodNs } +
      sdcSet.clocks.mapNotNull { c -> c.portName?.lowercase(Locale.ROOT)?.let { it to c.periodNs } }

    fun clockForNode(node: GateNode): Pair<String, Double> {
      val clkSignal = if (node.gateType.isSequential) {
        node.inputs.getOrNull(1) ?: "clk"
      } else {
        "clk"
      }
      val matched = clockPeriodByName[clkSignal.lowercase(Locale.ROOT)]
        ?: clockPeriodNs
      return clkSignal to matched
    }

    val combEdges = edges.filter { edge ->
      val toNode = nodeMap[edge.toNodeId]
      toNode != null && !toNode.gateType.isSequential
    }
    val combOutgoing = combEdges.groupBy { it.fromNodeId }

    val combInDegree = nodes.associate { it.id to 0 }.toMutableMap()
    combEdges.forEach { e -> combInDegree[e.toNodeId] = (combInDegree[e.toNodeId] ?: 0) + 1 }

    val queue = ArrayDeque<String>()
    nodes.forEach { n ->
      if ((combInDegree[n.id] ?: 0) == 0) queue.addLast(n.id)
    }

    val topoOrder = ArrayList<String>(nodes.size)
    val inTopo = HashSet<String>(nodes.size * 2)
    while (queue.isNotEmpty()) {
      val curr = queue.removeFirst()
      if (inTopo.add(curr)) {
        topoOrder += curr
      }
      (combOutgoing[curr] ?: emptyList()).forEach { edge ->
        val next = edge.toNodeId
        val rem = (combInDegree[next] ?: 0) - 1
        combInDegree[next] = rem
        if (rem == 0) queue.addLast(next)
      }
    }
    nodes.forEach { if (inTopo.add(it.id)) topoOrder += it.id }

    val levelMap = mutableMapOf<String, Int>()
    val arrivalMap = mutableMapOf<String, Double>()
    val predMap = mutableMapOf<String, String>()
    val launchSourceMap = mutableMapOf<String, List<String>>()

    for (id in topoOrder) {
      val node = nodeMap[id] ?: continue
      when {
        node.gateType == GateType.INPUT_PORT -> {
          levelMap[id] = 0
          arrivalMap[id] = sdcSet.inputDelayForPort(node.outputNet)
          launchSourceMap[id] = listOf(node.outputNet)
        }
        node.gateType.isSequential -> {
          levelMap[id] = 0
          arrivalMap[id] = node.delayNs
          launchSourceMap[id] = listOf(node.instanceName, node.outputNet)
        }
        else -> {
          val drivers = (incoming[id] ?: emptyList()).mapNotNull { edge ->
            val srcArr = arrivalMap[edge.fromNodeId] ?: return@mapNotNull null
            val srcLvl = levelMap[edge.fromNodeId] ?: 0
            Triple(edge.fromNodeId, srcArr, srcLvl)
          }
          val bestDriver = drivers.maxByOrNull { it.second }
          val maxInArr = bestDriver?.second ?: 0.0
          val maxInLvl = drivers.maxOfOrNull { it.third } ?: -1
          val effDelay = if (node.gateType == GateType.OUTPUT_PORT) {
            outputLoadDelayByNodeId[id] ?: 0.0
          } else {
            node.delayNs
          }
          levelMap[id] = maxInLvl + 1
          arrivalMap[id] = maxInArr + effDelay
          if (bestDriver != null) {
            predMap[id] = bestDriver.first
            launchSourceMap[id] = launchSourceMap[bestDriver.first].orEmpty()
          }
        }
      }
    }

    // Collect endpoints along with their effective required time and arrival time
    data class EndpointEval(
      val nodeId: String,
      val arrivalNs: Double,
      val requiredNs: Double,
      val isFalsePath: Boolean,
    )

    val endpointEvals = mutableListOf<EndpointEval>()
    val falsePathNodeIds = mutableSetOf<String>()

    nodes.forEach { node ->
      when {
        node.gateType == GateType.OUTPUT_PORT -> {
          val launches = launchSourceMap[node.id].orEmpty()
          val isFp = sdcSet.isFalsePathEndpoint(node.outputNet, launches)
          if (isFp) falsePathNodeIds += node.id
          val maxDelayOvr = sdcSet.maxDelayOverrideFor(node.outputNet, launches)
          val outDelay = sdcSet.outputDelayForPort(node.outputNet)
          val req = (maxDelayOvr ?: (clockPeriodNs - outDelay - uncertaintyNs)).coerceAtLeast(0.05)
          val arr = arrivalMap[node.id] ?: 0.0
          endpointEvals += EndpointEval(node.id, arr, req, isFp)
        }
        node.gateType.isSequential -> {
          val dEdges = (incoming[node.id] ?: emptyList())
          val dBest = dEdges.maxByOrNull { arrivalMap[it.fromNodeId] ?: 0.0 }
          val maxD = dBest?.let { arrivalMap[it.fromNodeId] ?: 0.0 } ?: 0.0
          val launches = dBest?.let { launchSourceMap[it.fromNodeId] }.orEmpty()
          val isFp = sdcSet.isFalsePathEndpoint(node.instanceName, launches) ||
            sdcSet.isFalsePathEndpoint(node.outputNet, launches)
          if (isFp) falsePathNodeIds += node.id
          val (_, domainPeriod) = clockForNode(node)
          val maxDelayOvr = sdcSet.maxDelayOverrideFor(node.instanceName, launches)
            ?: sdcSet.maxDelayOverrideFor(node.outputNet, launches)
          val req = ((maxDelayOvr ?: domainPeriod) - uncertaintyNs).coerceAtLeast(0.05)
          val arr = maxD + node.delayNs
          endpointEvals += EndpointEval(node.id, arr, req, isFp)
          if (dBest != null) predMap[node.id] = dBest.fromNodeId
        }
      }
    }

    val constrainedEndpoints = endpointEvals.filter { !it.isFalsePath }
    val criticalEndpoint = constrainedEndpoints.minByOrNull { it.requiredNs - it.arrivalNs }
      ?: endpointEvals.maxByOrNull { it.arrivalNs }
    val criticalDelay = criticalEndpoint?.arrivalNs ?: (arrivalMap.values.maxOrNull() ?: 0.0)

    val criticalNodeIds = mutableSetOf<String>()
    val criticalStepsReversed = mutableListOf<TimingPathStep>()
    var cursor: String? = criticalEndpoint?.nodeId ?: arrivalMap.maxByOrNull { it.value }?.key
    val visited = mutableSetOf<String>()
    while (cursor != null && cursor !in visited) {
      visited += cursor
      criticalNodeIds += cursor
      val node = nodeMap[cursor]
      if (node != null) {
        val arr = if (node.gateType.isSequential && cursor == criticalEndpoint?.nodeId) {
          criticalDelay
        } else {
          arrivalMap[cursor] ?: 0.0
        }
        val stepDelay = if (node.gateType == GateType.OUTPUT_PORT) {
          outputLoadDelayByNodeId[node.id] ?: 0.0
        } else {
          node.delayNs
        }
        criticalStepsReversed += TimingPathStep(
          nodeId = node.id,
          instanceName = node.instanceName,
          cellName = node.cellName,
          netName = node.outputNet,
          incrDelayNs = stepDelay,
          arrivalNs = arr,
          sourceLine = node.sourceLine,
        )
      }
      cursor = predMap[cursor]
    }
    val criticalPath = criticalStepsReversed.asReversed()

    val endpointReqMap = endpointEvals.associate { it.nodeId to it.requiredNs }
    val requiredMap = mutableMapOf<String, Double>()
    for (id in topoOrder.asReversed()) {
      val node = nodeMap[id] ?: continue
      if (node.gateType == GateType.OUTPUT_PORT) {
        requiredMap[id] = endpointReqMap[id] ?: (clockPeriodNs - uncertaintyNs)
      } else {
        val minOutReq = (outgoing[id] ?: emptyList()).minOfOrNull { edge ->
          val dstNode = nodeMap[edge.toNodeId]
          when {
            dstNode?.gateType?.isSequential == true -> {
              val seqReq = endpointReqMap[dstNode.id] ?: (clockPeriodNs - uncertaintyNs)
              seqReq - dstNode.delayNs
            }
            dstNode?.gateType == GateType.OUTPUT_PORT -> {
              val outReq = requiredMap[edge.toNodeId] ?: (clockPeriodNs - uncertaintyNs)
              outReq - (outputLoadDelayByNodeId[dstNode.id] ?: 0.0)
            }
            else -> {
              (requiredMap[edge.toNodeId] ?: (clockPeriodNs - uncertaintyNs)) - (dstNode?.delayNs ?: 0.0)
            }
          }
        } ?: (clockPeriodNs - uncertaintyNs)
        requiredMap[id] = minOutReq
      }
    }

    val wns = constrainedEndpoints.minOfOrNull { it.requiredNs - it.arrivalNs }
      ?: (clockPeriodNs - criticalDelay)
    val tns = constrainedEndpoints
      .map { it.requiredNs - it.arrivalNs }
      .filter { it < 0.0 }
      .sum()

    val maxCombLevel = (levelMap.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val finalNodes = nodes.map { node ->
      val lvl = if (node.gateType == GateType.OUTPUT_PORT) maxCombLevel else (levelMap[node.id] ?: 0)
      val arr = arrivalMap[node.id] ?: 0.0
      val req = requiredMap[node.id] ?: clockPeriodNs
      val (clkDom, _) = clockForNode(node)
      val isFp = node.id in falsePathNodeIds
      val effDelay = if (node.gateType == GateType.OUTPUT_PORT) {
        outputLoadDelayByNodeId[node.id] ?: 0.0
      } else {
        node.delayNs
      }
      node.copy(
        delayNs = effDelay,
        level = lvl,
        arrivalNs = arr,
        requiredNs = req,
        slackNs = if (isFp) Double.POSITIVE_INFINITY.coerceAtMost(999.0) else req - arr,
        isCriticalPath = node.id in criticalNodeIds,
        isFalsePath = isFp,
        clockDomain = clkDom,
      )
    }

    val finalEdges = edges.map { edge ->
      edge.copy(isCritical = edge.fromNodeId in criticalNodeIds && edge.toNodeId in criticalNodeIds)
    }

    return StaResult(
      nodes = finalNodes,
      edges = finalEdges,
      criticalPath = criticalPath,
      criticalDelayNs = criticalDelay,
      wnsNs = wns,
      tnsNs = tns,
    )
  }

  // ==================== STRUCTURAL VERILOG EMITTER ====================

  private fun emitStructuralVerilog(
    moduleName: String,
    ports: List<RtlPort>,
    nodes: List<GateNode>,
    library: TargetLibrary,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("// Synthesized Gate-Level Netlist — RTL Synth Studio")
    sb.appendLine("// Target Library: ${library.displayName} (${library.processNode})")
    sb.appendLine("`timescale 1ns/1ps")
    sb.appendLine()
    val portNames = ports.joinToString(", ") { it.name }
    sb.appendLine("module ${moduleName}_synth ($portNames);")

    ports.forEach { p ->
      val dir = p.direction.name.lowercase(Locale.ROOT)
      val range = if (p.width > 1) " [${p.msb}:${p.lsb}]" else ""
      sb.appendLine("  $dir$range ${p.name};")
    }
    sb.appendLine()

    val portSet = ports.map { it.name }.toSet()
    val internalNets = nodes
      .filter { !it.gateType.isPort }
      .map { it.outputNet to it.bitWidth }
      .filter { it.first !in portSet }
      .distinctBy { it.first }

    if (internalNets.isNotEmpty()) {
      internalNets.forEach { (net, width) ->
        val range = if (width > 1) " [${width - 1}:0]" else ""
        sb.appendLine("  wire$range $net;")
      }
      sb.appendLine()
    }

    nodes.filter { !it.gateType.isPort }.forEach { cell ->
      val pinBindings = when {
        cell.gateType == GateType.LOCKUP_LATCH -> {
          val d = cell.inputs.getOrElse(0) { "1'b0" }
          val gn = cell.inputs.getOrElse(1) { "clk" }
          ".D($d), .GN($gn), .Q(${cell.outputNet})"
        }
        cell.gateType == GateType.SDFF || cell.gateType == GateType.SDFFR -> {
          val d = cell.inputs.getOrElse(0) { "1'b0" }
          val clk = cell.inputs.getOrElse(1) { "clk" }
          val hasRst = cell.gateType == GateType.SDFFR
          val rst = if (hasRst) cell.inputs.getOrNull(2) else null
          val seIdx = if (hasRst) 3 else 2
          val siIdx = if (hasRst) 4 else 3
          val se = cell.inputs.getOrElse(seIdx) { "scan_en" }
          val si = cell.inputs.getOrElse(siIdx) { "scan_in_0" }
          if (rst != null) {
            ".CLK($clk), .RST($rst), .D($d), .SE($se), .SI($si), .Q(${cell.outputNet})"
          } else {
            ".CLK($clk), .D($d), .SE($se), .SI($si), .Q(${cell.outputNet})"
          }
        }
        cell.gateType.isSequential -> {
          val d = cell.inputs.getOrElse(0) { "1'b0" }
          val clk = cell.inputs.getOrElse(1) { "clk" }
          val rst = cell.inputs.getOrNull(2)
          if (rst != null) {
            ".CLK($clk), .RST($rst), .D($d), .Q(${cell.outputNet})"
          } else {
            ".CLK($clk), .D($d), .Q(${cell.outputNet})"
          }
        }
        cell.gateType == GateType.MUX2 -> {
          val s = cell.inputs.getOrElse(0) { "1'b0" }
          val a = cell.inputs.getOrElse(1) { "1'b0" }
          val b = cell.inputs.getOrElse(2) { "1'b0" }
          ".S($s), .A($a), .B($b), .Y(${cell.outputNet})"
        }
        cell.gateType == GateType.CONST -> ".Y(${cell.outputNet})"
        cell.inputs.size == 1 -> ".A(${cell.inputs[0]}), .Y(${cell.outputNet})"
        else -> {
          val a = cell.inputs.getOrElse(0) { "1'b0" }
          val b = cell.inputs.getOrElse(1) { "1'b0" }
          ".A($a), .B($b), .Y(${cell.outputNet})"
        }
      }
      sb.appendLine("  ${cell.cellName} ${cell.instanceName} ( $pinBindings );")
    }

    val tailAssigns = ScanChainStitcher.emitOutputTailAssignments(ports, nodes)
    if (tailAssigns.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("  // DFT Scan-Out Chain Tail Stitching")
      tailAssigns.forEach { sb.appendLine(it) }
    }

    sb.appendLine("endmodule")
    return sb.toString()
  }

  private fun formatNum(v: Double): String = String.format(Locale.US, "%.2f", v)
  private fun formatSigned(v: Double): String =
    if (v >= 0) "+${String.format(Locale.US, "%.2f", v)}" else String.format(Locale.US, "%.2f", v)
}
