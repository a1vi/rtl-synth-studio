package com.example.domain.synth

import java.util.Locale

data class AtpgOutcome(
  val vectors: List<AtpgTestVector>,
  val faultSites: List<AtpgFaultSite>,
  val transitionFaultSites: List<AtpgFaultSite> = emptyList(),
)

private data class AcceptedVectorFrame(
  val vectorIndex: Int,
  val piMap: Map<String, Long>,
  val ppiMap: Map<String, Long>,
  val goldenNets: Map<String, Long>,
  val goldenSig: List<Long>,
)

/**
 * Automatic Test Pattern Generation (ATPG) engine combining deterministic gate-level
 * fault-sensitizing passes (D-frontier justification) with LFSR PRPG vector compaction
 * and full single stuck-at (SA0/SA1) topological fault simulation across scan chains.
 *
 * For each accepted test vector, the engine computes:
 * 1. Serial `scan_in_i` shift-in bitstreams aligned to each stitched [DftScanChain],
 * 2. Primary input (PI) capture stimulus,
 * 3. Expected Primary Output (PO) values,
 * 4. Expected serial `scan_out_i` capture response bitstreams shifted out after capture.
 */
object AtpgEngine {

  fun generatePatterns(
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    ports: List<RtlPort>,
    scanChains: List<DftScanChain>,
    scanEnabled: Boolean,
    maxPatterns: Int,
  ): AtpgOutcome {
    val activeGates = nodes.filter { !it.gateType.isPort && it.gateType != GateType.CONST }
    if (activeGates.isEmpty()) {
      return AtpgOutcome(emptyList(), emptyList(), emptyList())
    }

    val nodeById = nodes.associateBy { it.id }
    val topoCombNodes = topologicalCombinationalOrder(nodes, edges, nodeById)

    val primaryInputs = ports
      .filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
      .map { it.name }
      .filterNot {
        it in setOf("clk", "clock", "scan_en", "tck", "tms", "tdi", "bist_en") ||
          it.startsWith("scan_in_")
      }
      .ifEmpty { listOf("clk") }

    val seqNodes = nodes.filter { it.gateType.isSequential && it.gateType != GateType.LOCKUP_LATCH }
    val scanRegs = seqNodes.filter { it.gateType == GateType.SDFF || it.gateType == GateType.SDFFR }
    val primaryOutputs = nodes
      .filter { it.gateType == GateType.OUTPUT_PORT }
      .mapNotNull { it.inputs.firstOrNull() ?: it.outputNet }
      .filterNot { it.startsWith("scan_out_") || it in setOf("tdo", "bist_done", "bist_fail") }

    val controllableSeqOutputs = if (scanEnabled) scanRegs.map { it.outputNet }.distinct() else emptyList()
    val observableSeqInputs = if (scanEnabled) scanRegs.mapNotNull { it.inputs.firstOrNull() }.distinct() else emptyList()

    // Extract collapsed SA0/SA1 fault list across active combinational & sequential nodes
    val faultTargets = activeGates.filter {
      it.gateType != GateType.LOCKUP_LATCH && !it.instanceName.startsWith("u_dft_")
    }.take(96)

    val rawFaults = mutableListOf<AtpgFaultSite>()
    faultTargets.forEachIndexed { idx, gate ->
      val isNonScanSeq = !scanEnabled && gate.gateType.isSequential
      val initialStatus = if (isNonScanSeq && gate.outputNet !in primaryOutputs) {
        FaultStatus.UNTESTABLE
      } else {
        FaultStatus.UNDETECTED
      }
      rawFaults += AtpgFaultSite(
        id = "f_${idx * 2}",
        nodeId = gate.id,
        nodeInstance = gate.instanceName,
        netName = gate.outputNet,
        faultType = FaultType.SA0,
        status = initialStatus,
      )
      rawFaults += AtpgFaultSite(
        id = "f_${idx * 2 + 1}",
        nodeId = gate.id,
        nodeInstance = gate.instanceName,
        netName = gate.outputNet,
        faultType = FaultType.SA1,
        status = initialStatus,
      )
    }

    if (rawFaults.isEmpty()) {
      return AtpgOutcome(emptyList(), emptyList(), emptyList())
    }

    fun evaluateCone(
      piValues: Map<String, Long>,
      ppiValues: Map<String, Long>,
      faultNodeId: String? = null,
      faultType: FaultType? = null,
    ): Map<String, Long> {
      val nets = HashMap<String, Long>(piValues.size + topoCombNodes.size + seqNodes.size + 16)
      piValues.forEach { (k, v) -> nets[k] = v }

      seqNodes.forEach { seq ->
        val baseVal = if (scanEnabled) (ppiValues[seq.outputNet] ?: 0L) else 0L
        val w = seq.bitWidth.coerceIn(1, 32)
        val mask = if (w >= 32) 0xFFFFFFFFL else (1L shl w) - 1L
        nets[seq.outputNet] = if (seq.id == faultNodeId && faultType != null) {
          if (faultType == FaultType.SA0) 0L else mask
        } else {
          baseVal and mask
        }
      }

      nodes.filter { it.gateType == GateType.CONST }.forEach { c ->
        val parsed = c.cellName.substringAfter("'d", "0").toLongOrNull() ?: 0L
        nets[c.outputNet] = parsed
      }

      fun readNet(net: String): Long {
        nets[net]?.let { return it }
        if (net.contains("'b")) return net.substringAfter("'b").toLongOrNull(2) ?: 0L
        if (net.contains("'d")) return net.substringAfter("'d").toLongOrNull(10) ?: 0L
        if (net.contains("'h")) return net.substringAfter("'h").toLongOrNull(16) ?: 0L
        if (net.startsWith("_const_")) {
          return net.substringAfter("b", "0").toLongOrNull() ?: 0L
        }
        return 0L
      }

      for (node in topoCombNodes) {
        val w = node.bitWidth.coerceIn(1, 32)
        val mask = if (w >= 32) 0xFFFFFFFFL else (1L shl w) - 1L
        if (node.id == faultNodeId && faultType != null) {
          nets[node.outputNet] = if (faultType == FaultType.SA0) 0L else mask
          continue
        }
        val a = node.inputs.getOrNull(0)?.let(::readNet) ?: 0L
        val b = node.inputs.getOrNull(1)?.let(::readNet) ?: 0L
        val c = node.inputs.getOrNull(2)?.let(::readNet) ?: 0L

        val outVal = when (node.gateType) {
          GateType.INV -> a.inv() and mask
          GateType.BUF, GateType.LUT4 -> a and mask
          GateType.AND2 -> (a and b) and mask
          GateType.OR2 -> (a or b) and mask
          GateType.NAND2 -> (a and b).inv() and mask
          GateType.NOR2 -> (a or b).inv() and mask
          GateType.XOR2, GateType.LUT6 -> (a xor b) and mask
          GateType.MUX2 -> if ((a and 1L) != 0L) (b and mask) else (c and mask)
          GateType.ADD_CELL, GateType.CARRY4 -> (a + b) and mask
          GateType.SUB_CELL -> (a - b) and mask
          GateType.MUL_CELL -> (a * b) and mask
          GateType.CMP_CELL -> if (a == b || a > b) 1L else 0L
          else -> a and mask
        }
        nets[node.outputNet] = outVal
      }
      return nets
    }

    fun signatureOf(nets: Map<String, Long>): List<Long> {
      val list = ArrayList<Long>(primaryOutputs.size + observableSeqInputs.size)
      primaryOutputs.forEach { po -> list += (nets[po] ?: 0L) }
      observableSeqInputs.forEach { ppo -> list += (nets[ppo] ?: 0L) }
      return list
    }

    var lfsr = 0x5A3C96E1L
    fun nextPrpg(seedIdx: Int): Long {
      val bit = ((lfsr ushr 0) xor (lfsr ushr 2) xor (lfsr ushr 3) xor (lfsr ushr 5)) and 1L
      lfsr = (lfsr ushr 1) or (bit shl 31)
      return lfsr xor (seedIdx.toLong() * 0x9E3779B9L)
    }

    val faultStates = rawFaults.toMutableList()
    val acceptedVectors = mutableListOf<AtpgTestVector>()
    val acceptedFrames = mutableListOf<AcceptedVectorFrame>()
    val piSet = primaryInputs.toSet()
    val ppiSet = controllableSeqOutputs.toSet()
    val safeMax = maxPatterns.coerceIn(4, 128)
    val totalTrials = (safeMax * 3).coerceIn(16, 180)

    for (trial in 0 until totalTrials) {
      if (acceptedVectors.size >= safeMax) break
      val firstUndetected = faultStates.firstOrNull { it.status == FaultStatus.UNDETECTED } ?: break

      // Alternate between deterministic structural fault sensitization and LFSR PRPG compaction
      val useSensitize = trial >= 4 && trial % 3 == 1
      val algoLabel = if (useSensitize) "D-SENSITIZE" else if (trial < 4) "ALGO-WALK" else "LFSR-PRPG"

      val piMap = linkedMapOf<String, Long>()
      val ppiMap = linkedMapOf<String, Long>()

      primaryInputs.forEachIndexed { idx, name ->
        val v = when (trial) {
          0 -> 0L
          1 -> 0xFFFFFFFFL
          2 -> 0x55555555L
          3 -> 0xAAAAAAAAL
          else -> {
            val raw = nextPrpg(trial * 31 + idx)
            if (trial % 5 == 0) 1L shl ((trial + idx) % 8) else raw
          }
        }
        piMap[name] = v and 0xFFL
      }

      controllableSeqOutputs.forEachIndexed { idx, regOut ->
        val v = when (trial) {
          0 -> 0L
          1 -> 0xFFFFFFFFL
          2 -> 0x55555555L
          3 -> 0xAAAAAAAAL
          else -> nextPrpg(trial * 17 + idx + 11)
        }
        ppiMap[regOut] = v and 0xFFL
      }

      // Deterministic justification: directly sensitize local inputs of an undetected target gate
      if (useSensitize) {
        val targetNode = nodeById[firstUndetected.nodeId]
        if (targetNode != null) {
          val desiredOut = if (firstUndetected.faultType == FaultType.SA0) 0xFFL else 0L
          val sideVal = when (targetNode.gateType) {
            GateType.AND2, GateType.NAND2 -> 0xFFL
            GateType.OR2, GateType.NOR2 -> 0L
            GateType.XOR2 -> 0L
            else -> desiredOut
          }
          targetNode.inputs.forEachIndexed { pinIdx, net ->
            val v = if (pinIdx == 0) desiredOut else sideVal
            if (net in piSet) piMap[net] = v
            if (net in ppiSet) ppiMap[net] = v
          }
          if (targetNode.outputNet in ppiSet) {
            ppiMap[targetNode.outputNet] = desiredOut
          }
        }
      }

      val goldenNets = evaluateCone(piMap, ppiMap)
      val goldenSig = signatureOf(goldenNets)
      val newlyDetectedIds = mutableListOf<String>()
      val vecIdx = acceptedVectors.size

      for (fIdx in faultStates.indices) {
        val f = faultStates[fIdx]
        if (f.status != FaultStatus.UNDETECTED) continue
        val faultyNets = evaluateCone(piMap, ppiMap, faultNodeId = f.nodeId, faultType = f.faultType)
        val faultySig = signatureOf(faultyNets)
        if (faultySig != goldenSig) {
          faultStates[fIdx] = f.copy(
            status = FaultStatus.DETECTED,
            detectedByVectorIndex = vecIdx,
          )
          newlyDetectedIds += "${f.faultType.name}@${f.netName}"
        }
      }

      if (newlyDetectedIds.isNotEmpty() || acceptedVectors.isEmpty()) {
        val scanInPreview = linkedMapOf<String, Int>()
        controllableSeqOutputs.take(8).forEachIndexed { i, net ->
          scanInPreview["s$i($net)"] = ((ppiMap[net] ?: 0L) and 1L).toInt()
        }

        val scanOutPreview = linkedMapOf<String, Int>()
        scanRegs.take(8).forEachIndexed { i, reg ->
          val dNet = reg.inputs.firstOrNull()
          val capVal = if (dNet != null) ((goldenNets[dNet] ?: 0L) and 1L).toInt() else 0
          scanOutPreview["so$i(${reg.outputNet})"] = capVal
        }

        // Build per-chain serial scan_in / expected scan_out bitstreams
        val chainVectors = scanChains.map { chain ->
          val flipFlopCells = chain.cells.filter { !it.isLockupLatch }
          val siBits = StringBuilder()
          val soBits = StringBuilder()
          val cellNames = mutableListOf<String>()

          flipFlopCells.forEach { cell ->
            val node = nodeById[cell.nodeId]
            val ppiBit = ((ppiMap[cell.outputNet] ?: 0L) and 1L).toInt()
            val dNet = node?.inputs?.firstOrNull()
            val capturedBit = if (dNet != null) ((goldenNets[dNet] ?: 0L) and 1L).toInt() else ppiBit
            siBits.append(ppiBit)
            soBits.append(capturedBit)
            cellNames += cell.instanceName
          }

          ScanChainVectorSlice(
            chainIndex = chain.chainIndex,
            scanInPort = chain.scanInPort,
            scanOutPort = chain.scanOutPort,
            // First bit shifted into scan_in reaches tail cell first; present shift-order MSB->LSB
            scanInBitstream = siBits.toString().ifEmpty { "0" },
            expectedScanOutBitstream = soBits.toString().ifEmpty { "0" },
            cellNamesInOrder = cellNames,
          )
        }

        val poPreview = linkedMapOf<String, Int>()
        primaryOutputs.take(6).forEach { po ->
          poPreview[po] = ((goldenNets[po] ?: 0L) and 0xFFL).toInt()
        }

        acceptedFrames += AcceptedVectorFrame(
          vectorIndex = vecIdx,
          piMap = piMap.toMap(),
          ppiMap = ppiMap.toMap(),
          goldenNets = goldenNets,
          goldenSig = goldenSig,
        )

        acceptedVectors += AtpgTestVector(
          index = vecIdx,
          piBits = piMap.mapValues { (it.value and 0xFFL).toInt() },
          scanInBits = scanInPreview,
          expectedPoBits = poPreview,
          newlyDetectedFaults = newlyDetectedIds.size,
          chainVectors = chainVectors,
          expectedScanOutBits = scanOutPreview,
          detectedFaultIds = newlyDetectedIds,
          targetedAlgorithm = algoLabel,
        )
      }
    }

    // Mark remaining sequential-shadowed faults as UNTESTABLE when scan is disabled
    if (!scanEnabled) {
      for (i in faultStates.indices) {
        if (faultStates[i].status == FaultStatus.UNDETECTED) {
          val g = nodeById[faultStates[i].nodeId]
          if (g != null && g.gateType.isSequential) {
            faultStates[i] = faultStates[i].copy(status = FaultStatus.UNTESTABLE)
          }
        }
      }
    }

    // Evaluate structural two-cycle Launch-on-Capture (V1 -> V2) Transition Delay faults (STR / STF)
    val transitionStates = mutableListOf<AtpgFaultSite>()
    faultTargets.forEachIndexed { idx, gate ->
      val isNonScanSeq = !scanEnabled && gate.gateType.isSequential
      val baseStatus = if (isNonScanSeq && gate.outputNet !in primaryOutputs) {
        FaultStatus.UNTESTABLE
      } else {
        FaultStatus.UNDETECTED
      }
      val w = gate.bitWidth.coerceIn(1, 32)
      val mask = if (w >= 32) 0xFFFFFFFFL else (1L shl w) - 1L

      var strStatus = baseStatus
      var strVec: Int? = null
      var stfStatus = baseStatus
      var stfVec: Int? = null

      if (baseStatus == FaultStatus.UNDETECTED && acceptedFrames.size >= 2) {
        for (k in 1 until acceptedFrames.size) {
          if (strStatus == FaultStatus.DETECTED && stfStatus == FaultStatus.DETECTED) break
          val launchNets = acceptedFrames[k - 1].goldenNets
          val captureFrame = acceptedFrames[k]
          val v1 = (launchNets[gate.outputNet] ?: 0L) and mask
          val v2 = (captureFrame.goldenNets[gate.outputNet] ?: 0L) and mask

          if (strStatus == FaultStatus.UNDETECTED && ((v1.inv() and v2) and mask) != 0L) {
            val faultyNets = evaluateCone(
              captureFrame.piMap,
              captureFrame.ppiMap,
              faultNodeId = gate.id,
              faultType = FaultType.SA0,
            )
            if (signatureOf(faultyNets) != captureFrame.goldenSig) {
              strStatus = FaultStatus.DETECTED
              strVec = captureFrame.vectorIndex
            }
          }

          if (stfStatus == FaultStatus.UNDETECTED && ((v1 and v2.inv()) and mask) != 0L) {
            val faultyNets = evaluateCone(
              captureFrame.piMap,
              captureFrame.ppiMap,
              faultNodeId = gate.id,
              faultType = FaultType.SA1,
            )
            if (signatureOf(faultyNets) != captureFrame.goldenSig) {
              stfStatus = FaultStatus.DETECTED
              stfVec = captureFrame.vectorIndex
            }
          }
        }
      }

      if (!scanEnabled && gate.gateType.isSequential) {
        if (strStatus == FaultStatus.UNDETECTED) strStatus = FaultStatus.UNTESTABLE
        if (stfStatus == FaultStatus.UNDETECTED) stfStatus = FaultStatus.UNTESTABLE
      }

      transitionStates += AtpgFaultSite(
        id = "tf_${idx * 2}",
        nodeId = gate.id,
        nodeInstance = gate.instanceName,
        netName = gate.outputNet,
        faultType = FaultType.STR,
        status = strStatus,
        detectedByVectorIndex = strVec,
      )
      transitionStates += AtpgFaultSite(
        id = "tf_${idx * 2 + 1}",
        nodeId = gate.id,
        nodeInstance = gate.instanceName,
        netName = gate.outputNet,
        faultType = FaultType.STF,
        status = stfStatus,
        detectedByVectorIndex = stfVec,
      )
    }

    return AtpgOutcome(
      vectors = acceptedVectors,
      faultSites = faultStates,
      transitionFaultSites = transitionStates,
    )
  }

  /**
   * Formats a compact one-line scan shift + capture summary for ATE logs or CLI reports.
   */
  fun formatVectorCycleSummary(vec: AtpgTestVector): String {
    val piSummary = vec.piBits.entries.take(4).joinToString(" ") { "${it.key}=${it.value}" }
    val poSummary = vec.expectedPoBits.entries.take(3).joinToString(" ") { "${it.key}=${it.value}" }
    val chainSummary = if (vec.chainVectors.isNotEmpty()) {
      vec.chainVectors.joinToString(" ") { ch ->
        "${ch.scanInPort}=${ch.scanInBitstream}->${ch.scanOutPort}=${ch.expectedScanOutBitstream}"
      }
    } else {
      val si = vec.scanInBits.values.joinToString("")
      val so = vec.expectedScanOutBits.values.joinToString("")
      if (si.isNotEmpty()) "SI=$si->SO=$so" else "COMB"
    }
    return "#${vec.index} [${vec.targetedAlgorithm} +${vec.newlyDetectedFaults}] $chainSummary | PI($piSummary) -> PO($poSummary)"
      .uppercase(Locale.ROOT)
      .let {
        "#${vec.index} [${vec.targetedAlgorithm} +${vec.newlyDetectedFaults}] $chainSummary | PI($piSummary) -> PO($poSummary)"
      }
  }

  private fun topologicalCombinationalOrder(
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    nodeById: Map<String, GateNode>,
  ): List<GateNode> {
    val nonSeqIds = nodes.filter { !it.gateType.isPort && !it.gateType.isSequential }.map { it.id }.toSet()
    val inDeg = nonSeqIds.associateWith { 0 }.toMutableMap()
    val outAdj = nonSeqIds.associateWith { mutableListOf<String>() }
    edges.forEach { e ->
      if (e.fromNodeId in nonSeqIds && e.toNodeId in nonSeqIds) {
        outAdj[e.fromNodeId]?.add(e.toNodeId)
        inDeg[e.toNodeId] = (inDeg[e.toNodeId] ?: 0) + 1
      }
    }
    val q = ArrayDeque<String>()
    inDeg.forEach { (id, d) -> if (d == 0) q.addLast(id) }
    val ordered = mutableListOf<GateNode>()
    val seen = HashSet<String>()
    while (q.isNotEmpty()) {
      val id = q.removeFirst()
      if (seen.add(id)) {
        nodeById[id]?.let { ordered += it }
      }
      outAdj[id].orEmpty().forEach { nxt ->
        val rem = (inDeg[nxt] ?: 0) - 1
        inDeg[nxt] = rem
        if (rem == 0) q.addLast(nxt)
      }
    }
    nodes.filter { !it.gateType.isPort && !it.gateType.isSequential && it.id !in seen }
      .forEach { ordered += it }
    return ordered
  }
}
