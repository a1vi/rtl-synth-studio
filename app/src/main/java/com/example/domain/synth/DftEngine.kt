package com.example.domain.synth

import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class ScanInsertionOutcome(
  val ports: List<RtlPort>,
  val nodes: List<GateNode>,
  val scanChains: List<DftScanChain>,
  val lockupLatchCount: Int,
  val jtagTapInserted: Boolean,
  val mbistCollarInserted: Boolean,
  val scanAreaOverheadUm2: Double,
  val scanInsertedCount: Int,
)

object DftEngine {

  /**
   * Applies Scan Chain Insertion (SDFF / SDFFR), Lockup Latches, JTAG Boundary TAP,
   * and MBIST Collar instrumentation onto already technology-mapped GateNodes.
   */
  fun applyScanInsertion(
    ports: List<RtlPort>,
    mappedNodes: List<GateNode>,
    library: TargetLibrary,
    config: DftConfig,
    warnings: MutableList<String>,
    log: MutableList<String>,
  ): ScanInsertionOutcome {
    if (!config.scanInsertionEnabled) {
      return ScanInsertionOutcome(
        ports = ports,
        nodes = mappedNodes,
        scanChains = emptyList(),
        lockupLatchCount = 0,
        jtagTapInserted = false,
        mbistCollarInserted = false,
        scanAreaOverheadUm2 = 0.0,
        scanInsertedCount = 0,
      )
    }

    val updatedPorts = ports.toMutableList()
    val updatedNodes = mappedNodes.toMutableList()
    val portNames = updatedPorts.map { it.name }.toMutableSet()
    val seqIndices = updatedNodes.indices.filter {
      val g = updatedNodes[it].gateType
      g == GateType.DFF || g == GateType.DFFR
    }

    var areaOverhead = 0.0
    var replacedCount = 0
    var lockupCount = 0
    var jtagInserted = false
    var mbistInserted = false

    fun addPortAndNodeIfAbsent(name: String, direction: PortDirection, width: Int = 1) {
      if (portNames.add(name)) {
        updatedPorts += RtlPort(
          name = name,
          direction = direction,
          width = width,
          isReg = false,
        )
        val nodeId = if (direction == PortDirection.INPUT) "in_$name" else "out_$name"
        if (updatedNodes.none { it.id == nodeId }) {
          updatedNodes += GateNode(
            id = nodeId,
            cellName = if (direction == PortDirection.INPUT) "INPUT" else "OUTPUT",
            gateType = if (direction == PortDirection.INPUT) GateType.INPUT_PORT else GateType.OUTPUT_PORT,
            instanceName = name,
            inputs = if (direction == PortDirection.INPUT) emptyList() else listOf(name),
            outputNet = name,
            bitWidth = width,
            delayNs = 0.0,
            areaUm2 = 0.0,
            powerUw = 0.0,
          )
        }
      }
    }

    val tempBuckets = if (seqIndices.isNotEmpty()) {
      ScanChainStitcher.partitionIntoBalancedChains(
        items = seqIndices,
        targetChainCount = config.targetChainCount,
        maxChainLength = config.maxChainLength,
        clockDomainOf = { idx -> updatedNodes[idx].inputs.getOrNull(1) ?: "clk" },
        instanceNameOf = { idx -> updatedNodes[idx].instanceName },
      )
    } else {
      emptyList()
    }

    val allocatedPorts = ScanChainStitcher.buildScanPortDefinitions(
      existingPortNames = portNames.toSet(),
      chainCount = tempBuckets.size.coerceAtLeast(1),
      includeJtagTap = config.insertJtagBoundaryTap,
      includeMbist = config.insertMbistCollar,
    )
    val scanEnPort = allocatedPorts.first { it.role == ScanPortRole.SCAN_ENABLE }.name
    addPortAndNodeIfAbsent(scanEnPort, PortDirection.INPUT)

    val chains = mutableListOf<DftScanChain>()

    if (seqIndices.isEmpty()) {
      warnings += "DFT Scan Insertion enabled, but top design has 0 sequential flip-flops; skipped SDFF substitution."
      log += "[DFT] 0 flip-flops found; scan chain stitching skipped."
    } else {
      tempBuckets.forEachIndexed { chainIdx, bucket ->
        if (bucket.isEmpty()) return@forEachIndexed
        val siPort = allocatedPorts.first { it.role == ScanPortRole.SCAN_IN && it.chainIndex == chainIdx }.name
        val soPort = allocatedPorts.first { it.role == ScanPortRole.SCAN_OUT && it.chainIndex == chainIdx }.name
        addPortAndNodeIfAbsent(siPort, PortDirection.INPUT)
        addPortAndNodeIfAbsent(soPort, PortDirection.OUTPUT)

        var prevScanNet = siPort
        var prevClockDomain: String? = null
        var chainLockup = false
        var totalBits = 0
        val chainCells = mutableListOf<DftScanCell>()

        bucket.forEachIndexed { posInChain, nodeIdx ->
          val orig = updatedNodes[nodeIdx]
          val clkDom = orig.inputs.getOrNull(1) ?: "clk"

          // Insert negative-edge / hold Lockup Latch across clock-domain crossings
          if (config.insertLockupLatches && prevClockDomain != null && prevClockDomain != clkDom) {
            val latchNet = "_dft_lockup_c${chainIdx}_$lockupCount"
            val latchId = "dft_lockup_${chainIdx}_$lockupCount"
            val latchInst = "u_dft_lockup_${chainIdx}_$lockupCount"
            val latchCell = lockupCellName(library)
            val latchArea = lockupCellArea(library)
            updatedNodes += GateNode(
              id = latchId,
              cellName = latchCell,
              gateType = GateType.LOCKUP_LATCH,
              instanceName = latchInst,
              inputs = listOf(prevScanNet, clkDom),
              outputNet = latchNet,
              bitWidth = 1,
              delayNs = 0.04,
              areaUm2 = latchArea,
              powerUw = latchArea * 2.1,
              clockDomain = clkDom,
              sourceLine = orig.sourceLine,
            )
            areaOverhead += latchArea
            lockupCount++
            chainLockup = true
            chainCells += DftScanCell(
              orderIndex = chainCells.size,
              nodeId = latchId,
              instanceName = latchInst,
              cellName = latchCell,
              outputNet = latchNet,
              clockDomain = clkDom,
              bitWidth = 1,
              isLockupLatch = true,
            )
            prevScanNet = latchNet
          }

          val hasReset = orig.gateType == GateType.DFFR
          val newType = if (hasReset) GateType.SDFFR else GateType.SDFF
          val newCellName = scanCellName(library, config.scanStyle, hasReset)
          val extraArea = orig.areaUm2 * config.scanStyle.areaOverheadRatio
          val extraPower = orig.powerUw * (config.scanStyle.areaOverheadRatio * 0.75)
          val dNet = orig.inputs.getOrElse(0) { "1'b0" }
          val rstNet = if (hasReset) orig.inputs.getOrNull(2) else null

          // Scan inputs encoded cleanly in inputs list for downstream graph & Verilog emission:
          // [0]=D, [1]=CLK, optional [2]=RST, scanEnablePort, prevScanNet
          val newInputs = buildList {
            add(dNet)
            add(clkDom)
            if (rstNet != null) add(rstNet)
            add(scanEnPort)
            add(prevScanNet)
          }

          val updatedNode = orig.copy(
            cellName = newCellName,
            gateType = newType,
            inputs = newInputs,
            delayNs = orig.delayNs + config.scanStyle.delayOverheadNs,
            areaUm2 = orig.areaUm2 + extraArea,
            powerUw = orig.powerUw + extraPower,
            clockDomain = clkDom,
          )
          updatedNodes[nodeIdx] = updatedNode
          areaOverhead += extraArea
          replacedCount++
          totalBits += orig.bitWidth.coerceAtLeast(1)

          chainCells += DftScanCell(
            orderIndex = chainCells.size,
            nodeId = updatedNode.id,
            instanceName = updatedNode.instanceName,
            cellName = updatedNode.cellName,
            outputNet = updatedNode.outputNet,
            clockDomain = clkDom,
            bitWidth = updatedNode.bitWidth,
            isLockupLatch = false,
          )

          prevScanNet = updatedNode.outputNet
          prevClockDomain = clkDom
        }

        // Optional end-of-chain tester hold lockup latch when requested & chain has multiple stages
        if (config.insertLockupLatches && !chainLockup && bucket.size >= 2) {
          val tailClk = prevClockDomain ?: "clk"
          val latchNet = "_dft_lockup_tail_c$chainIdx"
          val latchId = "dft_lockup_tail_$chainIdx"
          val latchCell = lockupCellName(library)
          val latchArea = lockupCellArea(library)
          updatedNodes += GateNode(
            id = latchId,
            cellName = latchCell,
            gateType = GateType.LOCKUP_LATCH,
            instanceName = "u_dft_lockup_tail_$chainIdx",
            inputs = listOf(prevScanNet, tailClk),
            outputNet = latchNet,
            bitWidth = 1,
            delayNs = 0.04,
            areaUm2 = latchArea,
            powerUw = latchArea * 2.1,
            clockDomain = tailClk,
          )
          areaOverhead += latchArea
          lockupCount++
          chainLockup = true
          chainCells += DftScanCell(
            orderIndex = chainCells.size,
            nodeId = latchId,
            instanceName = "u_dft_lockup_tail_$chainIdx",
            cellName = latchCell,
            outputNet = latchNet,
            clockDomain = tailClk,
            bitWidth = 1,
            isLockupLatch = true,
          )
          prevScanNet = latchNet
        }

        // Drive scan_out_i output port from tail of chain
        val soIdx = updatedNodes.indexOfFirst { it.id == "out_$soPort" }
        if (soIdx >= 0) {
          updatedNodes[soIdx] = updatedNodes[soIdx].copy(inputs = listOf(prevScanNet))
        } else {
          updatedNodes += GateNode(
            id = "out_$soPort",
            cellName = "OUTPUT",
            gateType = GateType.OUTPUT_PORT,
            instanceName = soPort,
            inputs = listOf(prevScanNet),
            outputNet = soPort,
            bitWidth = 1,
            delayNs = 0.0,
            areaUm2 = 0.0,
            powerUw = 0.0,
          )
        }

        chains += DftScanChain(
          chainIndex = chainIdx,
          scanInPort = siPort,
          scanOutPort = soPort,
          scanEnablePort = scanEnPort,
          clockDomains = chainCells.map { it.clockDomain }.distinct(),
          totalFlipFlopBits = totalBits,
          hasLockupLatch = chainLockup,
          cells = chainCells,
        )
      }
    }

    if (config.insertJtagBoundaryTap) {
      addPortAndNodeIfAbsent("tck", PortDirection.INPUT)
      addPortAndNodeIfAbsent("tms", PortDirection.INPUT)
      addPortAndNodeIfAbsent("tdi", PortDirection.INPUT)
      addPortAndNodeIfAbsent("tdo", PortDirection.OUTPUT)
      val tapArea = lockupCellArea(library) * 6.5
      updatedNodes += GateNode(
        id = "dft_jtag_tap",
        cellName = "IEEE1149_TAP",
        gateType = GateType.BUF,
        instanceName = "u_dft_jtag_tap",
        inputs = listOf("tdi", "tms", "tck"),
        outputNet = "tdo",
        bitWidth = 1,
        delayNs = 0.08,
        areaUm2 = tapArea,
        powerUw = tapArea * 3.0,
        clockDomain = "tck",
      )
      areaOverhead += tapArea
      jtagInserted = true
    }

    if (config.insertMbistCollar) {
      addPortAndNodeIfAbsent("bist_en", PortDirection.INPUT)
      addPortAndNodeIfAbsent("bist_done", PortDirection.OUTPUT)
      addPortAndNodeIfAbsent("bist_fail", PortDirection.OUTPUT)
      val bistArea = lockupCellArea(library) * 8.0
      val clkRef = updatedPorts.firstOrNull { "clk" in it.name.lowercase(Locale.ROOT) }?.name ?: "clk"
      updatedNodes += GateNode(
        id = "dft_mbist_ctrl",
        cellName = "MBIST_COLLAR",
        gateType = GateType.BUF,
        instanceName = "u_dft_mbist_collar",
        inputs = listOf("bist_en", clkRef),
        outputNet = "bist_done",
        bitWidth = 1,
        delayNs = 0.09,
        areaUm2 = bistArea,
        powerUw = bistArea * 3.2,
        clockDomain = clkRef,
      )
      updatedNodes += GateNode(
        id = "dft_mbist_cmp",
        cellName = "MBIST_CMP",
        gateType = GateType.AND2,
        instanceName = "u_dft_mbist_cmp",
        inputs = listOf("bist_done", "bist_en"),
        outputNet = "bist_fail",
        bitWidth = 1,
        delayNs = 0.05,
        areaUm2 = lockupCellArea(library),
        powerUw = 4.0,
        clockDomain = clkRef,
      )
      areaOverhead += bistArea + lockupCellArea(library)
      mbistInserted = true
    }

    if (replacedCount > 0) {
      log += "[DFT] Inserted ${chains.size} ${config.scanStyle.displayName} chain(s) across $replacedCount sequential cells ($lockupCount lockup latches, +${"%.2f".format(Locale.US, areaOverhead)} um^2)."
    }

    return ScanInsertionOutcome(
      ports = updatedPorts,
      nodes = updatedNodes,
      scanChains = chains,
      lockupLatchCount = lockupCount,
      jtagTapInserted = jtagInserted,
      mbistCollarInserted = mbistInserted,
      scanAreaOverheadUm2 = areaOverhead,
      scanInsertedCount = replacedCount,
    )
  }

  /**
   * Executes Structural DFT Rule Checking (DRC-DFT), Collapsed Stuck-At Fault Extraction,
   * Topological Gate-Level Fault Simulation / ATPG, STIL (IEEE 1450) & SCANDEF emission.
   */
  fun analyzeAndGenerateAtpg(
    moduleName: String,
    ports: List<RtlPort>,
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    library: TargetLibrary,
    clockPeriodNs: Double,
    totalAreaUm2: Double,
    config: DftConfig,
    scanInsertion: ScanInsertionOutcome,
  ): DftReport {
    val seqNodes = nodes.filter { it.gateType.isSequential && it.gateType != GateType.LOCKUP_LATCH }
    val scanReplaced = nodes.count { it.gateType == GateType.SDFF || it.gateType == GateType.SDFFR }
    val piNames = ports.filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
      .map { it.name }.toSet()

    // 1. Structural DFT Rule Checker (DRC-DFT) + Scan Continuity Check
    val dftChecks = buildDftRuleChecks(
      nodes = nodes,
      edges = edges,
      piNames = piNames,
      seqNodes = seqNodes,
      scanReplaced = scanReplaced,
      scanInsertion = scanInsertion,
      config = config,
    ).toMutableList()
    if (scanInsertion.scanChains.isNotEmpty()) {
      val continuity = ScanChainStitcher.verifyChainContinuity(
        ports = ports,
        nodes = nodes,
        chains = scanInsertion.scanChains,
        config = config,
      )
      dftChecks += if (continuity.isValid) {
        DftRuleCheck(
          ruleId = "DFT-06",
          title = "Scan Chain Stitching Continuity",
          severity = DftRuleSeverity.PASS,
          details = "Verified ${continuity.verifiedChains}/${continuity.totalChains} chain(s) (${continuity.totalStitchedBits} bits) from scan_in -> SDFF -> scan_out.",
          affectedCount = continuity.totalStitchedBits,
        )
      } else {
        DftRuleCheck(
          ruleId = "DFT-06",
          title = "Broken Scan Chain Stitch",
          severity = DftRuleSeverity.VIOLATION,
          details = continuity.issues.take(2).joinToString(" | "),
          affectedCount = continuity.issues.size,
        )
      }
    }

    // 2. Real Gate-Level ATPG & Scan-Chain Fault Simulation
    val atpgOutcome = AtpgEngine.generatePatterns(
      nodes = nodes,
      edges = edges,
      ports = ports,
      scanChains = scanInsertion.scanChains,
      scanEnabled = scanReplaced > 0 || seqNodes.isEmpty(),
      maxPatterns = config.atpgMaxPatterns.coerceIn(4, 128),
    )

    val totalFaults = atpgOutcome.faultSites.size
    val detected = atpgOutcome.faultSites.count { it.status == FaultStatus.DETECTED }
    val untestable = atpgOutcome.faultSites.count { it.status == FaultStatus.UNTESTABLE }
    val stuckAtCov = if (totalFaults > 0) (detected * 100.0) / totalFaults else 100.0
    val testableFaults = (totalFaults - untestable).coerceAtLeast(1)
    val testCov = if (totalFaults > 0) (detected * 100.0) / testableFaults else 100.0

    val transTotal = atpgOutcome.transitionFaultSites.size
    val transDetected = atpgOutcome.transitionFaultSites.count { it.status == FaultStatus.DETECTED }
    val transUntestable = atpgOutcome.transitionFaultSites.count { it.status == FaultStatus.UNTESTABLE }
    val transCov = if (transTotal > 0) (transDetected * 100.0) / transTotal else 100.0
    val transTestable = (transTotal - transUntestable).coerceAtLeast(1)
    val transTestCov = if (transTotal > 0) (transDetected * 100.0) / transTestable else 100.0

    val areaPct = if (totalAreaUm2 > 0.001 && scanInsertion.scanAreaOverheadUm2 > 0.0) {
      (scanInsertion.scanAreaOverheadUm2 / totalAreaUm2) * 100.0
    } else {
      0.0
    }

    // Shift frequency typically conservative relative to functional Fmax (cap at 50-125 MHz tester channel)
    val maxShiftMHz = (1000.0 / (clockPeriodNs * 2.0)).coerceIn(10.0, 125.0)

    val stilText = emitStilPatterns(
      moduleName = moduleName,
      ports = ports,
      chains = scanInsertion.scanChains,
      vectors = atpgOutcome.vectors,
      stuckAtCovPct = stuckAtCov,
    )
    val scanDefText = emitScanDef(
      moduleName = moduleName,
      chains = scanInsertion.scanChains,
    )
    val reportText = buildDftSummaryText(
      moduleName = moduleName,
      library = library,
      config = config,
      seqCount = seqNodes.size,
      scanReplaced = scanReplaced,
      chains = scanInsertion.scanChains,
      lockupCount = scanInsertion.lockupLatchCount,
      jtagInserted = scanInsertion.jtagTapInserted,
      mbistInserted = scanInsertion.mbistCollarInserted,
      areaOverheadUm2 = scanInsertion.scanAreaOverheadUm2,
      areaOverheadPct = areaPct,
      maxShiftMHz = maxShiftMHz,
      totalFaults = totalFaults,
      detected = detected,
      untestable = untestable,
      stuckAtCov = stuckAtCov,
      testCov = testCov,
      vectorCount = atpgOutcome.vectors.size,
      checks = dftChecks,
    )

    return DftReport(
      config = config,
      scanInserted = scanReplaced > 0,
      totalSeqElements = seqNodes.size,
      scanReplacedElements = scanReplaced,
      scanChains = scanInsertion.scanChains,
      lockupLatchCount = scanInsertion.lockupLatchCount,
      jtagTapInserted = scanInsertion.jtagTapInserted,
      mbistCollarInserted = scanInsertion.mbistCollarInserted,
      scanAreaOverheadUm2 = scanInsertion.scanAreaOverheadUm2,
      scanAreaOverheadPct = areaPct,
      scanShiftFreqMhz = maxShiftMHz,
      totalFaults = totalFaults,
      detectedFaults = detected,
      untestableFaults = untestable,
      stuckAtCoveragePct = stuckAtCov,
      testCoveragePct = testCov,
      dftRuleChecks = dftChecks,
      atpgVectors = atpgOutcome.vectors,
      sampleFaults = atpgOutcome.faultSites,
      stilPatternText = stilText,
      scanDefText = scanDefText,
      dftSummaryReport = reportText,
      transitionFaults = atpgOutcome.transitionFaultSites,
      transitionCoveragePct = transCov,
      transitionTestCoveragePct = transTestCov,
    )
  }

  private fun buildDftRuleChecks(
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    piNames: Set<String>,
    seqNodes: List<GateNode>,
    scanReplaced: Int,
    scanInsertion: ScanInsertionOutcome,
    config: DftConfig,
  ): List<DftRuleCheck> {
    val checks = mutableListOf<DftRuleCheck>()

    // DFT-01: Clock Controllability
    val gatedClockNodes = seqNodes.filter { ff ->
      val clkNet = ff.inputs.getOrNull(1) ?: "clk"
      clkNet !in piNames
    }
    checks += if (seqNodes.isEmpty()) {
      DftRuleCheck("DFT-01", "Clock Controllability", DftRuleSeverity.PASS, "Combinational design — no clock pins require ATE mux bypass.", 0)
    } else if (gatedClockNodes.isEmpty()) {
      DftRuleCheck("DFT-01", "Clock Controllability", DftRuleSeverity.PASS, "All ${seqNodes.size} flip-flop clock pins directly controllable from primary inputs.", 0)
    } else {
      DftRuleCheck(
        "DFT-01",
        "Derived / Gated Clock Detected",
        DftRuleSeverity.WARNING,
        "${gatedClockNodes.size} sequential cell(s) driven by internal clock net (${gatedClockNodes.take(2).joinToString { it.inputs.getOrNull(1).orEmpty() }}); add clock-gating test bypass.",
        gatedClockNodes.size,
      )
    }

    // DFT-02: Reset Controllability
    val resetSeqs = seqNodes.filter { it.gateType == GateType.DFFR || it.gateType == GateType.SDFFR }
    val internalResets = resetSeqs.filter { ff ->
      val rstNet = ff.inputs.getOrNull(2).orEmpty()
      rstNet.isNotBlank() && rstNet !in piNames && !rstNet.startsWith("_n").not()
    }
    checks += if (resetSeqs.isEmpty() || internalResets.isEmpty()) {
      DftRuleCheck("DFT-02", "Async/Sync Reset Controllability", DftRuleSeverity.PASS, "${resetSeqs.size} resettable register(s) cleanly controllable during scan shift.", 0)
    } else {
      DftRuleCheck(
        "DFT-02",
        "Internally Gated Reset Net",
        DftRuleSeverity.WARNING,
        "${internalResets.size} register(s) use internally derived reset logic; gate with scan_en for scan shift safety.",
        internalResets.size,
      )
    }

    // DFT-03: Combinational Feedback Loop Check
    val combNodes = nodes.filter { !it.gateType.isPort && !it.gateType.isSequential }
    val combIds = combNodes.map { it.id }.toSet()
    val combEdges = edges.filter { it.fromNodeId in combIds && it.toNodeId in combIds }
    val inDeg = combIds.associateWith { 0 }.toMutableMap()
    val adj = combIds.associateWith { mutableListOf<String>() }
    combEdges.forEach { e ->
      adj[e.fromNodeId]?.add(e.toNodeId)
      inDeg[e.toNodeId] = (inDeg[e.toNodeId] ?: 0) + 1
    }
    val q = ArrayDeque<String>()
    inDeg.forEach { (id, d) -> if (d == 0) q.addLast(id) }
    var visited = 0
    while (q.isNotEmpty()) {
      val cur = q.removeFirst()
      visited++
      adj[cur].orEmpty().forEach { nxt ->
        val rem = (inDeg[nxt] ?: 0) - 1
        inDeg[nxt] = rem
        if (rem == 0) q.addLast(nxt)
      }
    }
    val cycleCount = (combIds.size - visited).coerceAtLeast(0)
    checks += if (cycleCount == 0) {
      DftRuleCheck("DFT-03", "Combinational Loop Free", DftRuleSeverity.PASS, "Verified acyclic combinational cones across ${combNodes.size} gates.", 0)
    } else {
      DftRuleCheck("DFT-03", "Combinational Feedback Cycle", DftRuleSeverity.VIOLATION, "$cycleCount combinational node(s) form zero-delay loop(s), blocking ATPG convergence.", cycleCount)
    }

    // DFT-04: Sequential Scan Coverage
    checks += when {
      seqNodes.isEmpty() ->
        DftRuleCheck("DFT-04", "Scan Replacement Ratio", DftRuleSeverity.PASS, "Purely combinational logic — 100% directly observable at primary outputs.", 0)
      scanReplaced == seqNodes.size ->
        DftRuleCheck("DFT-04", "Scan Replacement Ratio", DftRuleSeverity.PASS, "100% (${scanReplaced}/${seqNodes.size}) flip-flops stitched into ${scanInsertion.scanChains.size} scan chain(s).", scanReplaced)
      scanReplaced == 0 ->
        DftRuleCheck("DFT-04", "Non-Scan Sequential Logic", DftRuleSeverity.WARNING, "${seqNodes.size} non-scan DFFs present. Enable Scan Chain Insertion for full controllability & observability.", seqNodes.size)
      else ->
        DftRuleCheck("DFT-04", "Partial Scan Coverage", DftRuleSeverity.WARNING, "$scanReplaced / ${seqNodes.size} registers on scan chains.", seqNodes.size - scanReplaced)
    }

    // DFT-05: Multi-Clock Lockup Latch Verification
    val clockCount = seqNodes.map { it.inputs.getOrNull(1) ?: "clk" }.distinct().size
    checks += if (clockCount > 1 && config.scanInsertionEnabled && !config.insertLockupLatches) {
      DftRuleCheck("DFT-05", "Multi-Clock Scan Crossing", DftRuleSeverity.VIOLATION, "$clockCount clock domains share scan chains without lockup latches — risk of shift hold violation.", clockCount)
    } else {
      DftRuleCheck("DFT-05", "Lockup Latch Hold Integrity", DftRuleSeverity.PASS, "${scanInsertion.lockupLatchCount} lockup latch(es) inserted across $clockCount clock domain(s).", scanInsertion.lockupLatchCount)
    }

    return checks
  }

  private fun emitStilPatterns(
    moduleName: String,
    ports: List<RtlPort>,
    chains: List<DftScanChain>,
    vectors: List<AtpgTestVector>,
    stuckAtCovPct: Double,
  ): String = buildString {
    appendLine("STIL 1.0;")
    appendLine("// Generated by RTL Synth Studio ATPG Engine")
    appendLine("// Design: $moduleName | Stuck-At Coverage: ${"%.2f".format(Locale.US, stuckAtCovPct)}%")
    appendLine()
    appendLine("Header {")
    appendLine("  Title \"${moduleName}_atpg_vectors\";")
    appendLine("  Source \"RTL Synth Studio DFT v1.0\";")
    appendLine("}")
    appendLine()
    appendLine("Signals {")
    ports.forEach { p ->
      val dir = when (p.direction) {
        PortDirection.INPUT -> "In"
        PortDirection.OUTPUT -> "Out"
        PortDirection.INOUT -> "InOut"
      }
      appendLine("  \"${p.name}\" $dir;")
    }
    appendLine("}")
    appendLine()
    if (chains.isNotEmpty()) {
      appendLine("ScanStructures {")
      chains.forEach { ch ->
        appendLine("  ScanChain \"chain_${ch.chainIndex}\" {")
        appendLine("    ScanLength ${ch.cells.size};")
        appendLine("    ScanIn \"${ch.scanInPort}\";")
        appendLine("    ScanOut \"${ch.scanOutPort}\";")
        appendLine("    ScanEnable \"${ch.scanEnablePort}\";")
        appendLine("  }")
      }
      appendLine("}")
      appendLine()
    }
    appendLine("PatternBurst \"all_patterns\" { PatList { \"stuck_at_pat\"; } }")
    appendLine("PatternExec { PatternBurst \"all_patterns\"; }")
    appendLine()
    appendLine("Pattern \"stuck_at_pat\" {")
    vectors.take(24).forEach { vec ->
      val piStr = vec.piBits.entries.joinToString(" ") { "${it.key}=0x${it.value.toString(16)}" }
      val poStr = vec.expectedPoBits.entries.joinToString(" ") { "${it.key}=0x${it.value.toString(16)}" }
      appendLine("  // Pattern #${vec.index} [${vec.targetedAlgorithm}] (+${vec.newlyDetectedFaults} faults)")
      appendLine("  W \"_default_WFT_\";")
      if (vec.chainVectors.isNotEmpty()) {
        val siAssigns = vec.chainVectors.joinToString(" ") { "\"${it.scanInPort}\"=${it.scanInBitstream};" }
        val soExpects = vec.chainVectors.joinToString(" ") { "\"${it.scanOutPort}\"=${it.expectedScanOutBitstream};" }
        appendLine("  Shift { V { $siAssigns } }")
        appendLine("  V { /* PI: $piStr -> PO: $poStr */ }")
        appendLine("  Shift { V { /* Expect */ $soExpects } }")
      } else {
        appendLine("  V { /* PI: $piStr -> PO: $poStr */ }")
      }
    }
    appendLine("}")
  }

  private fun emitScanDef(
    moduleName: String,
    chains: List<DftScanChain>,
  ): String = buildString {
    appendLine("VERSION 5.8 ;")
    appendLine("DESIGN $moduleName ;")
    appendLine("SCANCHAINS ${chains.size} ;")
    chains.forEach { ch ->
      appendLine("  - chain_${ch.chainIndex}")
      appendLine("    + START PIN ${ch.scanInPort}")
      val ordered = ch.cells.joinToString(" ") { "( ${it.instanceName} IN SI OUT SO )" }
      if (ordered.isNotEmpty()) {
        appendLine("    + ORDERED $ordered")
      }
      appendLine("    + STOP PIN ${ch.scanOutPort} ;")
    }
    appendLine("END SCANCHAINS")
    appendLine("END DESIGN")
  }

  private fun buildDftSummaryText(
    moduleName: String,
    library: TargetLibrary,
    config: DftConfig,
    seqCount: Int,
    scanReplaced: Int,
    chains: List<DftScanChain>,
    lockupCount: Int,
    jtagInserted: Boolean,
    mbistInserted: Boolean,
    areaOverheadUm2: Double,
    areaOverheadPct: Double,
    maxShiftMHz: Double,
    totalFaults: Int,
    detected: Int,
    untestable: Int,
    stuckAtCov: Double,
    testCov: Double,
    vectorCount: Int,
    checks: List<DftRuleCheck>,
  ): String = buildString {
    appendLine("==================================================================")
    appendLine("  RTL Synth Studio — DFT, Scan Insertion & ATPG Coverage Report")
    appendLine("==================================================================")
    appendLine("Design           : $moduleName")
    appendLine("PDK Library      : ${library.displayName}")
    appendLine("Scan Insertion   : ${if (scanReplaced > 0) "ENABLED (${config.scanStyle.displayName})" else "DISABLED (Audit Only)"}")
    appendLine("Sequential Flops : $seqCount total | $scanReplaced scan-stitched")
    appendLine("Scan Chains      : ${chains.size} (Max Len <= ${config.maxChainLength})")
    appendLine("Lockup Latches   : $lockupCount | JTAG TAP: $jtagInserted | MBIST Collar: $mbistInserted")
    appendLine("Area Overhead    : +${"%.2f".format(Locale.US, areaOverheadUm2)} um^2 (+${"%.2f".format(Locale.US, areaOverheadPct)}%)")
    appendLine("Max Shift Clock  : ${"%.1f".format(Locale.US, maxShiftMHz)} MHz")
    appendLine("ATPG Faults      : $detected detected / $totalFaults total ($untestable untestable)")
    appendLine("Stuck-At Cov     : ${"%.2f".format(Locale.US, stuckAtCov)}% (Test Cov: ${"%.2f".format(Locale.US, testCov)}%)")
    appendLine("ATPG Vectors     : $vectorCount compacted patterns")
    appendLine()
    appendLine("--- DFT Rule Checks ---")
    checks.forEach { c ->
      appendLine("  [${c.ruleId}] ${c.severity.name} - ${c.title}: ${c.details}")
    }
    if (chains.isNotEmpty()) {
      appendLine()
      appendLine("--- Scan Chain Order ---")
      chains.forEach { ch ->
        val path = ch.cells.joinToString(" -> ") { it.instanceName }
        appendLine("  Chain ${ch.chainIndex} (${ch.totalFlipFlopBits}b): ${ch.scanInPort} -> $path -> ${ch.scanOutPort}")
      }
    }
  }

  private fun scanPrefix(style: DftScanStyle): String = when (style) {
    DftScanStyle.MUXED_D -> "SDFF"
    DftScanStyle.CLOCKED_SCAN -> "CSDF"
    DftScanStyle.LSSD -> "LSDFF"
  }

  private fun scanCellName(library: TargetLibrary, style: DftScanStyle, hasReset: Boolean): String {
    val prefix = scanPrefix(style)
    return when (library) {
      TargetLibrary.ASAP7_7NM -> if (hasReset) "${prefix}Rx1_ASAP7" else "${prefix}x1_ASAP7"
      TargetLibrary.SKY130_130NM -> if (hasReset) "sky130_sdfrtp_1" else "sky130_sdfxtp_1"
      TargetLibrary.GF180MCU_180NM -> if (hasReset) "gf180_sdffrq_1" else "gf180_sdffq_1"
      TargetLibrary.NANGATE45_45NM -> if (hasReset) "${prefix}R_X1" else "${prefix}_X1"
      TargetLibrary.ARTIX7_FPGA -> if (hasReset) "FDCE_SCAN" else "FDRE_SCAN"
    }
  }

  private fun lockupCellName(library: TargetLibrary): String = when (library) {
    TargetLibrary.ASAP7_7NM -> "DLLATCHx1_ASAP7"
    TargetLibrary.SKY130_130NM -> "sky130_dlxtn_1"
    TargetLibrary.GF180MCU_180NM -> "gf180_dlatn_1"
    TargetLibrary.NANGATE45_45NM -> "DLL_X1"
    TargetLibrary.ARTIX7_FPGA -> "LDCE"
  }

  private fun lockupCellArea(library: TargetLibrary): Double = when (library) {
    TargetLibrary.ASAP7_7NM -> 0.24
    TargetLibrary.SKY130_130NM -> 11.2
    TargetLibrary.GF180MCU_180NM -> 16.4
    TargetLibrary.NANGATE45_45NM -> 2.38
    TargetLibrary.ARTIX7_FPGA -> 1.0
  }
}
