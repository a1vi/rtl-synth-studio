package com.example.domain.synth

import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Semantic role of a DFT port injected during automatic scan-chain insertion.
 */
enum class ScanPortRole(val verilogComment: String) {
  SCAN_ENABLE("Active-high scan shift enable"),
  SCAN_IN("Serial scan-in chain input"),
  SCAN_OUT("Serial scan-out chain output"),
  JTAG_TCK("IEEE 1149.1 test clock"),
  JTAG_TMS("IEEE 1149.1 test mode select"),
  JTAG_TDI("IEEE 1149.1 test data input"),
  JTAG_TDO("IEEE 1149.1 test data output"),
  MBIST_EN("Memory BIST enable"),
  MBIST_DONE("Memory BIST completion flag"),
  MBIST_FAIL("Memory BIST comparator failure flag"),
}

/**
 * Complete port definition metadata emitted for DFT scan-in, scan-out, and control ports.
 */
data class ScanPortDefinition(
  val name: String,
  val direction: PortDirection,
  val width: Int = 1,
  val chainIndex: Int? = null,
  val role: ScanPortRole,
) {
  fun toRtlPort(line: Int = 1): RtlPort = RtlPort(
    name = name,
    direction = direction,
    width = width,
    isReg = false,
    msb = (width - 1).coerceAtLeast(0),
    lsb = 0,
    isSigned = false,
    line = line,
  )

  fun toVerilogDeclaration(): String {
    val dir = direction.name.lowercase(Locale.ROOT)
    val range = if (width > 1) " [${width - 1}:0]" else ""
    return "  $dir$range $name; // ${role.verilogComment}"
  }

  fun toAnsiPortFragment(): String {
    val dir = direction.name.lowercase(Locale.ROOT)
    val range = if (width > 1) " [${width - 1}:0]" else ""
    return "$dir wire$range $name"
  }
}

/**
 * Continuity verification summary produced after stitching scan chains into a netlist.
 */
data class ScanChainContinuityReport(
  val isValid: Boolean,
  val totalChains: Int,
  val verifiedChains: Int,
  val totalStitchedBits: Int,
  val addedPorts: List<ScanPortDefinition>,
  val issues: List<String>,
)

/**
 * Result of automatically inserting scan chains into an existing structural Verilog netlist.
 */
data class VerilogNetlistScanResult(
  val moduleName: String,
  val scanInsertedVerilog: String,
  val addedPorts: List<ScanPortDefinition>,
  val scanChains: List<DftScanChain>,
  val convertedFlopCount: Int,
  val lockupLatchCount: Int,
  val totalScanBits: Int,
  val continuityReport: ScanChainContinuityReport,
)

/**
 * Dedicated Design-for-Test (DFT) scan-chain insertion, port definition, and stitching utilities.
 * Supports both in-memory [GateNode] graphs (during RTL synthesis) and direct structural
 * Verilog netlist-to-scan-netlist rewriting.
 */
object ScanChainStitcher {

  /**
   * Allocates conflict-free scan-enable, scan-in, and scan-out port definitions for [chainCount] chains.
   */
  fun buildScanPortDefinitions(
    existingPortNames: Set<String>,
    chainCount: Int,
    includeJtagTap: Boolean = false,
    includeMbist: Boolean = false,
  ): List<ScanPortDefinition> {
    val used = existingPortNames.toMutableSet()
    val defs = mutableListOf<ScanPortDefinition>()

    fun uniqueName(base: String): String {
      if (used.add(base)) return base
      var idx = 1
      while (!used.add("${base}_dft$idx")) idx++
      return "${base}_dft$idx"
    }

    val seName = if ("scan_en" in existingPortNames) "scan_en" else uniqueName("scan_en")
    defs += ScanPortDefinition(
      name = seName,
      direction = PortDirection.INPUT,
      width = 1,
      chainIndex = null,
      role = ScanPortRole.SCAN_ENABLE,
    )

    for (i in 0 until chainCount.coerceAtLeast(1)) {
      val siName = if ("scan_in_$i" in existingPortNames) "scan_in_$i" else uniqueName("scan_in_$i")
      val soName = if ("scan_out_$i" in existingPortNames) "scan_out_$i" else uniqueName("scan_out_$i")
      defs += ScanPortDefinition(
        name = siName,
        direction = PortDirection.INPUT,
        width = 1,
        chainIndex = i,
        role = ScanPortRole.SCAN_IN,
      )
      defs += ScanPortDefinition(
        name = soName,
        direction = PortDirection.OUTPUT,
        width = 1,
        chainIndex = i,
        role = ScanPortRole.SCAN_OUT,
      )
    }

    if (includeJtagTap) {
      val jtagSpecs = listOf(
        Triple("tck", PortDirection.INPUT, ScanPortRole.JTAG_TCK),
        Triple("tms", PortDirection.INPUT, ScanPortRole.JTAG_TMS),
        Triple("tdi", PortDirection.INPUT, ScanPortRole.JTAG_TDI),
        Triple("tdo", PortDirection.OUTPUT, ScanPortRole.JTAG_TDO),
      )
      jtagSpecs.forEach { (base, dir, role) ->
        val name = if (base in existingPortNames) base else uniqueName(base)
        defs += ScanPortDefinition(name = name, direction = dir, width = 1, chainIndex = null, role = role)
      }
    }

    if (includeMbist) {
      val mbistSpecs = listOf(
        Triple("bist_en", PortDirection.INPUT, ScanPortRole.MBIST_EN),
        Triple("bist_done", PortDirection.OUTPUT, ScanPortRole.MBIST_DONE),
        Triple("bist_fail", PortDirection.OUTPUT, ScanPortRole.MBIST_FAIL),
      )
      mbistSpecs.forEach { (base, dir, role) ->
        val name = if (base in existingPortNames) base else uniqueName(base)
        defs += ScanPortDefinition(name = name, direction = dir, width = 1, chainIndex = null, role = role)
      }
    }

    return defs
  }

  /**
   * Computes balanced sequential element buckets grouped by clock domain & instance name.
   */
  fun <T> partitionIntoBalancedChains(
    items: List<T>,
    targetChainCount: Int,
    maxChainLength: Int,
    clockDomainOf: (T) -> String,
    instanceNameOf: (T) -> String,
  ): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val safeMax = maxChainLength.coerceAtLeast(1)
    val minChainsByLen = ceil(items.size.toDouble() / safeMax).toInt().coerceAtLeast(1)
    val chainCount = max(targetChainCount.coerceIn(1, 16), minChainsByLen)
      .coerceIn(1, items.size)

    val sorted = items.sortedWith(
      compareBy<T> { clockDomainOf(it) }
        .thenBy { instanceNameOf(it) }
    )
    val buckets = MutableList(chainCount) { mutableListOf<T>() }
    sorted.forEachIndexed { idx, item ->
      buckets[idx % chainCount] += item
    }
    return buckets.filter { it.isNotEmpty() }
  }

  /**
   * Generates `assign <scan_out_i> = <tail_net>;` Verilog statements for any aliased OUTPUT_PORT nodes.
   */
  fun emitOutputTailAssignments(ports: List<RtlPort>, nodes: List<GateNode>): List<String> {
    val portDirMap = ports.associate { it.name to it.direction }
    return nodes
      .filter { it.gateType == GateType.OUTPUT_PORT }
      .mapNotNull { outNode ->
        val driver = outNode.inputs.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (driver == outNode.outputNet) return@mapNotNull null
        if (portDirMap[outNode.outputNet] == PortDirection.OUTPUT || outNode.outputNet.startsWith("scan_out_")) {
          "  assign ${outNode.outputNet} = $driver;"
        } else {
          null
        }
      }
      .distinct()
  }

  /**
   * Verifies scan chain electrical stitching continuity on a GateNode netlist graph.
   */
  fun verifyChainContinuity(
    ports: List<RtlPort>,
    nodes: List<GateNode>,
    chains: List<DftScanChain>,
    config: DftConfig,
  ): ScanChainContinuityReport {
    val portMap = ports.associateBy { it.name }
    val nodeById = nodes.associateBy { it.id }
    val issues = mutableListOf<String>()
    var verified = 0
    var totalBits = 0

    chains.forEach { chain ->
      var chainOk = true
      val siPort = portMap[chain.scanInPort]
      if (siPort == null || siPort.direction != PortDirection.INPUT) {
        issues += "Chain ${chain.chainIndex}: missing INPUT port '${chain.scanInPort}'."
        chainOk = false
      }
      val sePort = portMap[chain.scanEnablePort]
      if (sePort == null || sePort.direction != PortDirection.INPUT) {
        issues += "Chain ${chain.chainIndex}: missing INPUT scan-enable port '${chain.scanEnablePort}'."
        chainOk = false
      }
      val soPort = portMap[chain.scanOutPort]
      if (soPort == null || soPort.direction != PortDirection.OUTPUT) {
        issues += "Chain ${chain.chainIndex}: missing OUTPUT port '${chain.scanOutPort}'."
        chainOk = false
      }

      var expectedPrevNet = chain.scanInPort
      var prevClk: String? = null
      chain.cells.forEach { cell ->
        val node = nodeById[cell.nodeId]
        if (node == null) {
          issues += "Chain ${chain.chainIndex}: cell '${cell.instanceName}' missing from netlist graph."
          chainOk = false
          return@forEach
        }
        if (cell.isLockupLatch) {
          val latchD = node.inputs.firstOrNull()
          if (latchD != expectedPrevNet) {
            issues += "Chain ${chain.chainIndex}: lockup latch '${cell.instanceName}' D='$latchD' expected '$expectedPrevNet'."
            chainOk = false
          }
        } else {
          if (node.gateType != GateType.SDFF && node.gateType != GateType.SDFFR) {
            issues += "Chain ${chain.chainIndex}: cell '${cell.instanceName}' is ${node.gateType}, expected SDFF/SDFFR."
            chainOk = false
          }
          if (config.insertLockupLatches && prevClk != null && prevClk != cell.clockDomain) {
            issues += "Chain ${chain.chainIndex}: clock-domain change $prevClk -> ${cell.clockDomain} before '${cell.instanceName}' without lockup latch."
            chainOk = false
          }
          val siIdx = if (node.gateType == GateType.SDFFR) 4 else 3
          val actualSi = node.inputs.getOrNull(siIdx)
          if (actualSi != expectedPrevNet) {
            issues += "Chain ${chain.chainIndex}: cell '${cell.instanceName}' .SI('$actualSi') expected '$expectedPrevNet'."
            chainOk = false
          }
          totalBits += cell.bitWidth.coerceAtLeast(1)
        }
        expectedPrevNet = node.outputNet
        prevClk = cell.clockDomain
      }

      val outNode = nodes.firstOrNull { it.gateType == GateType.OUTPUT_PORT && it.outputNet == chain.scanOutPort }
      val tailDriver = outNode?.inputs?.firstOrNull()
      if (chain.cells.isNotEmpty() && tailDriver != expectedPrevNet) {
        issues += "Chain ${chain.chainIndex}: scan_out '${chain.scanOutPort}' driven by '$tailDriver' expected '$expectedPrevNet'."
        chainOk = false
      }

      if (chainOk) verified++
    }

    val addedDefs = buildList {
      val se = chains.firstOrNull()?.scanEnablePort
      if (se != null) {
        add(ScanPortDefinition(se, PortDirection.INPUT, 1, null, ScanPortRole.SCAN_ENABLE))
      }
      chains.forEach { ch ->
        add(ScanPortDefinition(ch.scanInPort, PortDirection.INPUT, 1, ch.chainIndex, ScanPortRole.SCAN_IN))
        add(ScanPortDefinition(ch.scanOutPort, PortDirection.OUTPUT, 1, ch.chainIndex, ScanPortRole.SCAN_OUT))
      }
    }

    return ScanChainContinuityReport(
      isValid = issues.isEmpty() && (chains.isEmpty() || verified == chains.size),
      totalChains = chains.size,
      verifiedChains = verified,
      totalStitchedBits = totalBits,
      addedPorts = addedDefs,
      issues = issues,
    )
  }

  /**
   * Automatically parses an existing structural/gate-level Verilog netlist, injects scan-enable
   * and scan-in/scan-out port definitions, converts DFF/DFFR instances into stitched scan cells
   * (`SDFF`/`SDFFR`) across balanced chains with optional lockup latches, and returns the updated netlist.
   */
  fun insertScanChainsIntoVerilogNetlist(
    netlistVerilog: String,
    library: TargetLibrary = TargetLibrary.SKY130_130NM,
    config: DftConfig = DftConfig(scanInsertionEnabled = true),
  ): VerilogNetlistScanResult {
    val effectiveConfig = if (config.scanInsertionEnabled) config else config.copy(scanInsertionEnabled = true)
    val headerRegex = Regex("""(?s)\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)\s*\((.*?)\)\s*;""")
    val headerMatch = headerRegex.find(netlistVerilog)
      ?: throw IllegalArgumentException("Cannot find Verilog module header `module <name>(...);` in netlist.")

    val moduleName = headerMatch.groupValues[1]
    val rawPortList = headerMatch.groupValues[2]

    // Parse existing ports (handles both ANSI `input wire a` and non-ANSI `a, b` port lists)
    val existingPorts = extractModulePorts(rawPortList, netlistVerilog)
    val existingPortNames = existingPorts.map { it.name }.toSet()

    // Extract endmodule position & body statements
    val endModIdx = netlistVerilog.lastIndexOf("endmodule")
    val bodyStartIdx = headerMatch.range.last + 1
    val rawBody = if (endModIdx > bodyStartIdx) {
      netlistVerilog.substring(bodyStartIdx, endModIdx)
    } else {
      netlistVerilog.substring(bodyStartIdx)
    }

    // Match structural cell instantiations: `<cell_type> [#(...)] <inst_name> ( .PIN(sig), ... );`
    val instRegex = Regex("""(?m)^\s*([A-Za-z_][A-Za-z0-9_$]*)\s+(?:#\s*\([^;]*?\)\s+)?([A-Za-z_][A-Za-z0-9_$]*)\s*\(([^;]*)\)\s*;""")
    val parsedInstances = instRegex.findAll(rawBody).mapIndexed { idx, m ->
      val cellType = m.groupValues[1]
      val instName = m.groupValues[2]
      val pinText = m.groupValues[3]
      ParsedCellInstance(
        index = idx,
        range = m.range,
        cellType = cellType,
        instanceName = instName,
        pins = parseNamedPinConnections(pinText),
        isFlop = isSequentialFlipFlopCell(cellType),
      )
    }.toList()

    val flopInstances = parsedInstances.filter { it.isFlop }
    val buckets = partitionIntoBalancedChains(
      items = flopInstances,
      targetChainCount = effectiveConfig.targetChainCount,
      maxChainLength = effectiveConfig.maxChainLength,
      clockDomainOf = { it.clockNet() },
      instanceNameOf = { it.instanceName },
    )

    val chainCount = buckets.size.coerceAtLeast(1)
    val addedPortDefs = buildScanPortDefinitions(
      existingPortNames = existingPortNames,
      chainCount = chainCount,
      includeJtagTap = effectiveConfig.insertJtagBoundaryTap,
      includeMbist = effectiveConfig.insertMbistCollar,
    )
    val sePortName = addedPortDefs.first { it.role == ScanPortRole.SCAN_ENABLE }.name

    // Build stitched chain plans & rewritten instance text per original instance index
    val replacementByInstIdx = mutableMapOf<Int, String>()
    val stitchedChains = mutableListOf<DftScanChain>()
    val tailAssignments = mutableListOf<String>()
    val injectedLockupWires = mutableListOf<String>()
    val injectedLockupDecls = mutableListOf<String>()
    val verifiedNodes = mutableListOf<GateNode>()
    var lockupCounter = 0

    // Seed verifiedNodes with input/output ports for formal continuity verification
    val allPorts = (existingPorts + addedPortDefs.filter { it.name !in existingPortNames }.map { it.toRtlPort() })
    allPorts.forEach { p ->
      verifiedNodes += GateNode(
        id = if (p.direction == PortDirection.INPUT) "in_${p.name}" else "out_${p.name}",
        cellName = if (p.direction == PortDirection.INPUT) "INPUT" else "OUTPUT",
        gateType = if (p.direction == PortDirection.INPUT) GateType.INPUT_PORT else GateType.OUTPUT_PORT,
        instanceName = p.name,
        inputs = if (p.direction == PortDirection.INPUT) emptyList() else listOf(p.name),
        outputNet = p.name,
        bitWidth = p.width,
        delayNs = 0.0,
        areaUm2 = 0.0,
        powerUw = 0.0,
      )
    }

    buckets.forEachIndexed { chainIdx, bucket ->
      val siPort = addedPortDefs.first { it.role == ScanPortRole.SCAN_IN && it.chainIndex == chainIdx }.name
      val soPort = addedPortDefs.first { it.role == ScanPortRole.SCAN_OUT && it.chainIndex == chainIdx }.name

      var prevScanNet = siPort
      var prevClk: String? = null
      var hasLockup = false
      val chainCells = mutableListOf<DftScanCell>()

      bucket.forEach { inst ->
        val clkNet = inst.clockNet()
        val dNet = inst.dataNet()
        val rstPin = inst.resetPinName()
        val rstNet = inst.resetNet()
        val qNet = inst.qNet()
        val hasReset = rstNet != null || inst.cellType.contains("R", ignoreCase = true)

        val extraLockupsBefore = StringBuilder()
        if (effectiveConfig.insertLockupLatches && prevClk != null && prevClk != clkNet) {
          val latchNet = "_dft_lockup_c${chainIdx}_$lockupCounter"
          val latchInst = "u_dft_lockup_${chainIdx}_$lockupCounter"
          val latchCell = lockupCellForLibrary(library)
          injectedLockupWires += "  wire $latchNet;"
          extraLockupsBefore.appendLine("  $latchCell $latchInst ( .D($prevScanNet), .GN($clkNet), .Q($latchNet) );")
          val latchNodeId = "dft_lockup_${chainIdx}_$lockupCounter"
          verifiedNodes += GateNode(
            id = latchNodeId,
            cellName = latchCell,
            gateType = GateType.LOCKUP_LATCH,
            instanceName = latchInst,
            inputs = listOf(prevScanNet, clkNet),
            outputNet = latchNet,
            bitWidth = 1,
            delayNs = 0.04,
            areaUm2 = 1.0,
            powerUw = 1.0,
            clockDomain = clkNet,
          )
          chainCells += DftScanCell(
            orderIndex = chainCells.size,
            nodeId = latchNodeId,
            instanceName = latchInst,
            cellName = latchCell,
            outputNet = latchNet,
            clockDomain = clkNet,
            bitWidth = 1,
            isLockupLatch = true,
          )
          prevScanNet = latchNet
          lockupCounter++
          hasLockup = true
        }

        val scanCell = scanCellForLibrary(library, effectiveConfig.scanStyle, hasReset)
        val rstBinding = if (rstNet != null) ", .${rstPin ?: "RST"}($rstNet)" else ""
        val rewritten = buildString {
          if (extraLockupsBefore.isNotEmpty()) append(extraLockupsBefore.toString())
          append("  $scanCell ${inst.instanceName} ( .CLK($clkNet)$rstBinding, .D($dNet), .SE($sePortName), .SI($prevScanNet), .Q($qNet) );")
        }
        replacementByInstIdx[inst.index] = rewritten

        val gType = if (hasReset) GateType.SDFFR else GateType.SDFF
        val nodeId = "scan_inst_${inst.index}"
        val nodeInputs = buildList {
          add(dNet)
          add(clkNet)
          if (hasReset) add(rstNet ?: "rst")
          add(sePortName)
          add(prevScanNet)
        }
        verifiedNodes += GateNode(
          id = nodeId,
          cellName = scanCell,
          gateType = gType,
          instanceName = inst.instanceName,
          inputs = nodeInputs,
          outputNet = qNet,
          bitWidth = 1,
          delayNs = 0.1,
          areaUm2 = 2.0,
          powerUw = 2.0,
          clockDomain = clkNet,
        )
        chainCells += DftScanCell(
          orderIndex = chainCells.size,
          nodeId = nodeId,
          instanceName = inst.instanceName,
          cellName = scanCell,
          outputNet = qNet,
          clockDomain = clkNet,
          bitWidth = 1,
          isLockupLatch = false,
        )
        prevScanNet = qNet
        prevClk = clkNet
      }

      if (effectiveConfig.insertLockupLatches && !hasLockup && bucket.size >= 2) {
        val tailClk = prevClk ?: "clk"
        val latchNet = "_dft_lockup_tail_c$chainIdx"
        val latchInst = "u_dft_lockup_tail_$chainIdx"
        val latchCell = lockupCellForLibrary(library)
        injectedLockupWires += "  wire $latchNet;"
        injectedLockupDecls += "  $latchCell $latchInst ( .D($prevScanNet), .GN($tailClk), .Q($latchNet) );"
        val latchNodeId = "dft_lockup_tail_$chainIdx"
        verifiedNodes += GateNode(
          id = latchNodeId,
          cellName = latchCell,
          gateType = GateType.LOCKUP_LATCH,
          instanceName = latchInst,
          inputs = listOf(prevScanNet, tailClk),
          outputNet = latchNet,
          bitWidth = 1,
          delayNs = 0.04,
          areaUm2 = 1.0,
          powerUw = 1.0,
          clockDomain = tailClk,
        )
        chainCells += DftScanCell(
          orderIndex = chainCells.size,
          nodeId = latchNodeId,
          instanceName = latchInst,
          cellName = latchCell,
          outputNet = latchNet,
          clockDomain = tailClk,
          bitWidth = 1,
          isLockupLatch = true,
        )
        prevScanNet = latchNet
        lockupCounter++
        hasLockup = true
      }

      // Stitch scan_out output port to tail of chain
      val outIdx = verifiedNodes.indexOfFirst { it.gateType == GateType.OUTPUT_PORT && it.outputNet == soPort }
      if (outIdx >= 0) {
        verifiedNodes[outIdx] = verifiedNodes[outIdx].copy(inputs = listOf(prevScanNet))
      }
      tailAssignments += "  assign $soPort = $prevScanNet;"

      stitchedChains += DftScanChain(
        chainIndex = chainIdx,
        scanInPort = siPort,
        scanOutPort = soPort,
        scanEnablePort = sePortName,
        clockDomains = chainCells.map { it.clockDomain }.distinct(),
        totalFlipFlopBits = bucket.size,
        hasLockupLatch = hasLockup,
        cells = chainCells,
      )
    }

    // Rewrite module header port list
    val newPortsOnly = addedPortDefs.filter { it.name !in existingPortNames }
    val isAnsiHeader = rawPortList.contains(Regex("""\b(input|output|inout)\b"""))
    val updatedPortList = buildString {
      val trimmed = rawPortList.trim().trimEnd(',')
      append(trimmed)
      if (newPortsOnly.isNotEmpty()) {
        if (trimmed.isNotEmpty()) append(", ")
        append(
          newPortsOnly.joinToString(", ") {
            if (isAnsiHeader) it.toAnsiPortFragment() else it.name
          }
        )
      }
    }

    // Rewrite body with scan-converted instances
    val rewrittenBodyBuilder = StringBuilder()
    if (!isAnsiHeader && newPortsOnly.isNotEmpty()) {
      rewrittenBodyBuilder.appendLine()
      rewrittenBodyBuilder.appendLine("  // DFT Scan Ports (Auto-Inserted)")
      newPortsOnly.forEach { rewrittenBodyBuilder.appendLine(it.toVerilogDeclaration()) }
    }
    if (injectedLockupWires.isNotEmpty()) {
      rewrittenBodyBuilder.appendLine()
      rewrittenBodyBuilder.appendLine("  // DFT Lockup Latch Nets")
      injectedLockupWires.forEach { rewrittenBodyBuilder.appendLine(it) }
    }

    var cursor = 0
    parsedInstances.forEach { inst ->
      rewrittenBodyBuilder.append(rawBody.substring(cursor, inst.range.first))
      val replacement = replacementByInstIdx[inst.index]
      if (replacement != null) {
        rewrittenBodyBuilder.append(replacement)
      } else {
        rewrittenBodyBuilder.append(rawBody.substring(inst.range.first, inst.range.last + 1))
      }
      cursor = inst.range.last + 1
    }
    rewrittenBodyBuilder.append(rawBody.substring(cursor))

    if (injectedLockupDecls.isNotEmpty() || tailAssignments.isNotEmpty()) {
      rewrittenBodyBuilder.appendLine()
      if (injectedLockupDecls.isNotEmpty()) {
        rewrittenBodyBuilder.appendLine("  // DFT Scan Chain Lockup Latches")
        injectedLockupDecls.forEach { rewrittenBodyBuilder.appendLine(it) }
      }
      if (tailAssignments.isNotEmpty()) {
        rewrittenBodyBuilder.appendLine("  // DFT Scan-Out Chain Tail Stitching")
        tailAssignments.forEach { rewrittenBodyBuilder.appendLine(it) }
      }
    }

    val prefixBeforeHeader = netlistVerilog.substring(0, headerMatch.range.first)
    val suffixAfterEndmodule = if (endModIdx >= 0) netlistVerilog.substring(endModIdx) else "\nendmodule\n"

    val finalVerilog = buildString {
      append(prefixBeforeHeader)
      append("module $moduleName ($updatedPortList);")
      append(rewrittenBodyBuilder.toString().trimEnd())
      appendLine()
      append(suffixAfterEndmodule)
    }

    val continuity = verifyChainContinuity(
      ports = allPorts,
      nodes = verifiedNodes,
      chains = stitchedChains,
      config = effectiveConfig,
    )

    return VerilogNetlistScanResult(
      moduleName = moduleName,
      scanInsertedVerilog = finalVerilog,
      addedPorts = addedPortDefs,
      scanChains = stitchedChains,
      convertedFlopCount = flopInstances.size,
      lockupLatchCount = lockupCounter,
      totalScanBits = flopInstances.size,
      continuityReport = continuity,
    )
  }

  // ==================== INTERNAL NETLIST PARSING HELPERS ====================

  private data class ParsedCellInstance(
    val index: Int,
    val range: IntRange,
    val cellType: String,
    val instanceName: String,
    val pins: Map<String, String>,
    val isFlop: Boolean,
  ) {
    fun clockNet(): String = pins["CLK"] ?: pins["C"] ?: pins["CK"] ?: pins["clk"] ?: "clk"
    fun dataNet(): String = pins["D"] ?: pins["d"] ?: "1'b0"
    fun qNet(): String = pins["Q"] ?: pins["QN"] ?: pins["q"] ?: "q_$index"
    fun resetPinName(): String? = listOf("RST", "RN", "RESET", "RESET_B", "CLR", "R").firstOrNull { it in pins }
    fun resetNet(): String? = resetPinName()?.let { pins[it] }
  }

  private fun isSequentialFlipFlopCell(cellType: String): Boolean {
    val upper = cellType.uppercase(Locale.ROOT)
    if (upper.startsWith("INPUT") || upper.startsWith("OUTPUT")) return false
    return upper.startsWith("DFF") ||
      upper.startsWith("SDFF") ||
      upper.startsWith("FDRE") ||
      upper.startsWith("FDCE") ||
      upper.startsWith("FDSE") ||
      upper.startsWith("FDPE") ||
      "_DF" in upper ||
      "_SDF" in upper ||
      "DFF" in upper
  }

  private fun parseNamedPinConnections(pinList: String): Map<String, String> {
    val pinRegex = Regex("""\.([A-Za-z_][A-Za-z0-9_$]*)\s*\(\s*([^)]+?)\s*\)""")
    val map = linkedMapOf<String, String>()
    pinRegex.findAll(pinList).forEach { m ->
      map[m.groupValues[1].uppercase(Locale.ROOT)] = m.groupValues[2].trim()
    }
    return map
  }

  private fun extractModulePorts(rawPortList: String, fullSource: String): List<RtlPort> {
    if (rawPortList.contains(Regex("""\b(input|output|inout)\b"""))) {
      val itemRegex = Regex("""(?:(input|output|inout)\b(?:\s+(?:wire|reg|logic|signed))*\s*(?:\[(\d+)\s*:\s*(\d+)\])?\s*)?([A-Za-z_][A-Za-z0-9_$]*)""")
      var currentDir = PortDirection.INPUT
      var currentMsb = 0
      var currentLsb = 0
      val parsed = mutableListOf<RtlPort>()
      rawPortList.split(',').forEach { token ->
        val cleaned = token.replace(Regex("""//[^\n]*"""), "").trim()
        if (cleaned.isEmpty()) return@forEach
        val m = itemRegex.find(cleaned) ?: return@forEach
        val dirStr = m.groupValues[1]
        if (dirStr.isNotEmpty()) {
          currentDir = PortDirection.valueOf(dirStr.uppercase(Locale.ROOT))
          currentMsb = m.groupValues[2].toIntOrNull() ?: 0
          currentLsb = m.groupValues[3].toIntOrNull() ?: 0
        }
        val name = m.groupValues[4]
        val width = if (dirStr.isNotEmpty() && m.groupValues[2].isEmpty()) {
          1
        } else if (currentMsb != currentLsb) {
          kotlin.math.abs(currentMsb - currentLsb) + 1
        } else {
          1
        }
        if (name.isNotEmpty()) {
          parsed += RtlPort(name = name, direction = currentDir, width = width, isReg = false, msb = currentMsb, lsb = currentLsb)
        }
      }
      if (parsed.isNotEmpty()) return parsed
    }

    val portNames = rawPortList.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val bodyDirRegex = Regex("""(?m)^\s*(input|output|inout)\b(?:\s+(?:wire|reg|logic))?\s*(?:\[(\d+)\s*:\s*(\d+)\])?\s*([^;]+);""")
    val dirByName = mutableMapOf<String, Triple<PortDirection, Int, Int>>()
    bodyDirRegex.findAll(fullSource).forEach { m ->
      val dir = PortDirection.valueOf(m.groupValues[1].uppercase(Locale.ROOT))
      val msb = m.groupValues[2].toIntOrNull() ?: 0
      val lsb = m.groupValues[3].toIntOrNull() ?: 0
      m.groupValues[4].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
        dirByName[name] = Triple(dir, msb, lsb)
      }
    }

    return portNames.map { pName ->
      val info = dirByName[pName]
      val dir = info?.first ?: PortDirection.INPUT
      val msb = info?.second ?: 0
      val lsb = info?.third ?: 0
      val width = if (info != null && msb != lsb) kotlin.math.abs(msb - lsb) + 1 else 1
      RtlPort(name = pName, direction = dir, width = width, isReg = false, msb = msb, lsb = lsb)
    }
  }

  private fun scanCellForLibrary(library: TargetLibrary, style: DftScanStyle, hasReset: Boolean): String {
    val prefix = when (style) {
      DftScanStyle.MUXED_D -> "SDFF"
      DftScanStyle.CLOCKED_SCAN -> "CSDF"
      DftScanStyle.LSSD -> "LSDFF"
    }
    return when (library) {
      TargetLibrary.ASAP7_7NM -> if (hasReset) "${prefix}Rx1_ASAP7" else "${prefix}x1_ASAP7"
      TargetLibrary.SKY130_130NM -> if (hasReset) "sky130_sdfrtp_1" else "sky130_sdfxtp_1"
      TargetLibrary.GF180MCU_180NM -> if (hasReset) "gf180_sdffrq_1" else "gf180_sdffq_1"
      TargetLibrary.NANGATE45_45NM -> if (hasReset) "${prefix}R_X1" else "${prefix}_X1"
      TargetLibrary.ARTIX7_FPGA -> if (hasReset) "FDCE_SCAN" else "FDRE_SCAN"
    }
  }

  private fun lockupCellForLibrary(library: TargetLibrary): String = when (library) {
    TargetLibrary.ASAP7_7NM -> "DLLATCHx1_ASAP7"
    TargetLibrary.SKY130_130NM -> "sky130_dlxtn_1"
    TargetLibrary.GF180MCU_180NM -> "gf180_dlatn_1"
    TargetLibrary.NANGATE45_45NM -> "DLL_X1"
    TargetLibrary.ARTIX7_FPGA -> "LDCE"
  }
}
