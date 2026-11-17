package com.example.domain.synth

import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.abs

enum class LecOverallStatus(val badge: String, val label: String) {
  EQUIVALENT("EQUIVALENT", "Functional Equivalence Verified"),
  NON_EQUIVALENT("NON-EQUIVALENT", "Functional Mismatch Detected"),
  INCONCLUSIVE("INCONCLUSIVE", "Incomplete / Abstracted Key Points"),
  UNAVAILABLE("UNAVAILABLE", "No Netlist or Parse Error"),
}

enum class ComparePointKind(val shortTag: String, val label: String) {
  PRIMARY_OUTPUT("PO", "Primary Output"),
  REGISTER_DFF("DFF", "Sequential Register"),
  LATCH("LAT", "Latch / Lockup"),
}

enum class KeyPointMatchStatus(val label: String) {
  MATCHED("Mapped"),
  GOLDEN_ONLY("Golden Only"),
  REVISED_ONLY("Revised Only"),
  WIDTH_MISMATCH("Width Mismatch"),
}

enum class VerificationProofMethod(val label: String) {
  EXACT_EXHAUSTIVE_MITER("Exhaustive Miter (2^N)"),
  BOUNDED_STIMULUS_CHECK("Bounded Seq Check (BMC)"),
  STRUCTURAL_KEYPOINT_ONLY("Abstracted Cell"),
  UNMAPPED_SKIPPED("Unmapped Key Point"),
}

enum class ComparePointVerdict(val badge: String, val label: String) {
  PROVEN_EQUIVALENT("PROVEN", "Proven Equivalent"),
  BOUNDED_EQUIVALENT("BOUNDED EQ", "Bounded Equivalent"),
  NON_EQUIVALENT("NON-EQ", "Non-Equivalent"),
  INCONCLUSIVE("INCONCL", "Inconclusive"),
}

data class CounterexampleStep(
  val cycleIndex: Int,
  val stimulusInputs: Map<String, String>,
  val goldenValueHex: String,
  val revisedValueHex: String,
  val goldenValueBin: String,
  val revisedValueBin: String,
)

data class LecComparePointResult(
  val id: String,
  val name: String,
  val kind: ComparePointKind,
  val bitWidth: Int,
  val matchStatus: KeyPointMatchStatus,
  val proofMethod: VerificationProofMethod,
  val verdict: ComparePointVerdict,
  val inputSupportBits: Int,
  val coneGateCount: Int,
  val diagnosticReason: String,
  val counterexample: CounterexampleStep? = null,
  val netlistNodeIds: List<String> = emptyList(),
  val rtlSourceLine: Int = 0,
)

data class LecConfig(
  val exhaustiveBitLimit: Int = 10,
  val bmcCycleDepth: Int = 24,
  val constrainScanEnableZero: Boolean = true,
  val customRevisedNetlistOverride: String? = null,
)

data class FormalEquivalenceReport(
  val moduleName: String,
  val targetLibrary: TargetLibrary,
  val overallStatus: LecOverallStatus,
  val setupSummary: String,
  val goldenPortCount: Int,
  val revisedPortCount: Int,
  val totalComparePoints: Int,
  val matchedCount: Int,
  val unmappedGoldenCount: Int,
  val unmappedRevisedCount: Int,
  val provenCount: Int,
  val boundedEquivalentCount: Int,
  val nonEquivalentCount: Int,
  val inconclusiveCount: Int,
  val abstractionReasons: List<String>,
  val isNetlistStale: Boolean,
  val dftScanConstrained: Boolean,
  val comparePoints: List<LecComparePointResult>,
  val executionDurationMs: Long,
  val logLines: List<String>,
)

/**
 * Formal Logic Equivalence Checker (LEC) comparing Golden RTL (Verilog/SystemVerilog/VHDL)
 * against a Synthesized Gate-Level Netlist.
 *
 * Performs:
 * 1. Key-point extraction & structural matching (Primary Outputs & Sequential Flip-Flops).
 * 2. Fan-in cone exactness & abstraction auditing (detects unmodeled FPGA LUT/CARRY INITs,
 *    abstracted comparators/slices/shifts, or non-zero reset constants honestly as INCONCLUSIVE).
 * 3. Exact 2^N SAT-style Exhaustive Miter equivalence checking for combinational cones.
 * 4. Lockstep Bounded Sequential Checking (BMC) across reset/corner/PRNG stimulus vectors.
 */
object FormalEquivalenceEngine {

  private data class RevisedModel(
    val moduleName: String,
    val ports: List<RtlPort>,
    val nodes: List<GateNode>,
    val usedCustomNetlist: Boolean,
  )

  private data class ConeAuditResult(
    val coneNodes: List<GateNode>,
    val supportInputs: Set<String>,
    val isSequentialCone: Boolean,
    val abstractionReason: String?,
  )

  fun verify(
    goldenRtlSource: String,
    report: SynthesisReport?,
    topModuleName: String? = null,
    isNetlistStale: Boolean = false,
    config: LecConfig = LecConfig(),
  ): FormalEquivalenceReport {
    val startedNs = System.nanoTime()
    val logs = mutableListOf<String>()

    if (report == null) {
      logs += "[LEC] Unavailable: Run synthesis first to generate a revised gate-level netlist."
      return emptyUnavailableReport(
        moduleName = topModuleName ?: "top",
        library = TargetLibrary.ASAP7_7NM,
        summary = "No synthesized netlist available.",
        isNetlistStale = isNetlistStale,
        logs = logs,
        startedNs = startedNs,
      )
    }

    logs += "[LEC-01] Reading Golden RTL design..."
    val canonicalGolden = if (VhdlFrontendParser.looksLikeVhdl(goldenRtlSource)) {
      when (val conv = VhdlFrontendParser.toCanonicalVerilog(goldenRtlSource)) {
        is VhdlConversionResult.Error -> {
          logs += "[ERROR] Golden VHDL Line ${conv.line}: ${conv.message}"
          return emptyUnavailableReport(
            moduleName = report.moduleName,
            library = report.targetLibrary,
            summary = "Golden VHDL parse error at line ${conv.line}: ${conv.message}",
            isNetlistStale = isNetlistStale,
            logs = logs,
            startedNs = startedNs,
          )
        }
        is VhdlConversionResult.Success -> conv.verilogSource
      }
    } else {
      goldenRtlSource
    }

    val goldenDesign = when (val parsed = VerilogHdlParser.parse(canonicalGolden, topModuleName ?: report.moduleName)) {
      is VerilogParseResult.Error -> {
        logs += "[ERROR] Golden RTL Line ${parsed.line}:${parsed.column}: ${parsed.message}"
        return emptyUnavailableReport(
          moduleName = report.moduleName,
          library = report.targetLibrary,
          summary = "Golden RTL parse error at line ${parsed.line}: ${parsed.message}",
          isNetlistStale = isNetlistStale,
          logs = logs,
          startedNs = startedNs,
        )
      }
      is VerilogParseResult.Success -> parsed.design
    }
    val goldenTop = goldenDesign.topModule
    logs += "[LEC-01] Golden top '${goldenTop.name}' (${goldenTop.ports.size} ports)."

    val revised = buildRevisedModel(report, config.customRevisedNetlistOverride)
    val hasDftScanCells = revised.nodes.any {
      it.gateType == GateType.SDFF || it.gateType == GateType.SDFFR || it.gateType == GateType.LOCKUP_LATCH
    }
    val dftConstrained = config.constrainScanEnableZero && hasDftScanCells
    logs += if (revised.usedCustomNetlist) {
      "[LEC-02] Parsed custom revised Verilog netlist '${revised.moduleName}' (${revised.nodes.count { !it.gateType.isPort }} cells)."
    } else {
      "[LEC-02] Loaded synthesized gate-level netlist '${revised.moduleName}' (${revised.nodes.count { !it.gateType.isPort }} cells, ${report.targetLibrary.shortName})."
    }
    if (dftConstrained) {
      logs += "[LEC-03] Applied DFT mode constraint: scan_en = 0 (ignore scan_in/scan_out test pins)."
    }
    if (isNetlistStale) {
      logs += "[WARN] Golden RTL was edited after last synthesis — comparing live RTL against previous netlist snapshot."
    }

    // Extract Golden & Revised key points
    val goldenInputs = goldenTop.ports.filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
    val revisedInputs = revised.ports.filter { it.direction == PortDirection.INPUT || it.direction == PortDirection.INOUT }
    val goldenInputMap = goldenInputs.associateBy { it.name }
    val revisedInputMap = revisedInputs.associateBy { it.name }

    val goldenOutputs = goldenTop.ports.filter { it.direction == PortDirection.OUTPUT }
    val revisedOutputs = revised.ports.filter { it.direction == PortDirection.OUTPUT }
    val goldenOutMap = goldenOutputs.associateBy { it.name }
    val revisedOutMap = revisedOutputs.associateBy { it.name }

    val goldenSeqTargets = collectGoldenSequentialRegisters(goldenTop)
    val revisedSeqNodes = revised.nodes.filter { it.gateType.isSequential }
    val revisedSeqMap = linkedMapOf<String, GateNode>()
    revisedSeqNodes.forEach { seq ->
      // Skip lockup latches injected solely on scan tail
      if (seq.gateType != GateType.LOCKUP_LATCH || !seq.instanceName.contains("lockup", ignoreCase = true)) {
        revisedSeqMap.putIfAbsent(seq.outputNet, seq)
      }
    }

    val goldenWidths = linkedMapOf<String, Int>().apply {
      goldenTop.ports.forEach { put(it.name, it.width) }
      goldenTop.logicSignals.forEach { putIfAbsent(it.name, it.width) }
    }
    val goldenSeqResetVals = collectGoldenSeqResetConstants(goldenTop)
    val goldenOpsPerTarget = analyzeGoldenUnsupportedOps(goldenDesign, goldenTop)

    val clockPort = detectClockPort(goldenTop)
    val (resetPort, resetActiveLow) = detectResetPort(goldenTop)

    val comparePoints = mutableListOf<LecComparePointResult>()
    val abstractionSet = linkedSetOf<String>()

    // Check Input Port Parity (non-DFT inputs)
    val extraRevInputs = revisedInputs.filter { p ->
      p.name !in goldenInputMap && !(dftConstrained && isScanTestPort(p.name))
    }
    val missingRevInputs = goldenInputs.filter { p -> p.name !in revisedInputMap }
    val mismatchedInputWidths = goldenInputs.mapNotNull { gp ->
      val rp = revisedInputMap[gp.name]
      if (rp != null && rp.width != gp.width) gp.name to (gp.width to rp.width) else null
    }
    if (extraRevInputs.isNotEmpty() || missingRevInputs.isNotEmpty() || mismatchedInputWidths.isNotEmpty()) {
      logs += "[WARN] Primary input interface discrepancy detected: missing=${missingRevInputs.map { it.name }}, extra=${extraRevInputs.map { it.name }}, widthErr=${mismatchedInputWidths.map { it.first }}."
    }

    // Precompute revised net driver map & non-port nodes
    val revDriverByNet = linkedMapOf<String, GateNode>()
    revised.nodes.filter { !it.gateType.isPort }.forEach { revDriverByNet[it.outputNet] = it }
    val revPortOutIndirection = linkedMapOf<String, String>()
    revised.nodes.filter { it.gateType == GateType.OUTPUT_PORT }.forEach { outNode ->
      val src = outNode.inputs.firstOrNull()
      if (src != null && src != outNode.outputNet) {
        revPortOutIndirection[outNode.outputNet] = src
      }
    }

    // 1. Primary Output Key-Point Mapping
    val allOutNames = (goldenOutMap.keys + revisedOutMap.keys).distinct()
    val poConeAudits = mutableMapOf<String, ConeAuditResult>()

    allOutNames.forEach { outName ->
      if (dftConstrained && isScanTestPort(outName) && outName !in goldenOutMap) {
        return@forEach
      }
      val gp = goldenOutMap[outName]
      val rp = revisedOutMap[outName]
      when {
        gp == null && rp != null -> {
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = rp.width,
            matchStatus = KeyPointMatchStatus.REVISED_ONLY,
            proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
            verdict = ComparePointVerdict.NON_EQUIVALENT,
            inputSupportBits = 0,
            coneGateCount = 0,
            diagnosticReason = "Output port '$outName' exists in revised netlist but is missing from Golden RTL.",
          )
        }
        gp != null && rp == null -> {
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.GOLDEN_ONLY,
            proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
            verdict = ComparePointVerdict.NON_EQUIVALENT,
            inputSupportBits = 0,
            coneGateCount = 0,
            diagnosticReason = "Golden output port '$outName' is missing from revised netlist.",
            rtlSourceLine = gp.line,
          )
        }
        gp != null && rp != null && gp.width != rp.width -> {
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.WIDTH_MISMATCH,
            proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
            verdict = ComparePointVerdict.NON_EQUIVALENT,
            inputSupportBits = 0,
            coneGateCount = 0,
            diagnosticReason = "Port bit-width mismatch: Golden RTL [${gp.width}b] vs Revised Netlist [${rp.width}b].",
            rtlSourceLine = gp.line,
          )
        }
        gp != null && rp != null -> {
          val startNet = revPortOutIndirection[outName] ?: outName
          val audit = auditRevisedCone(
            targetNet = startNet,
            revDriverByNet = revDriverByNet,
            revisedInputNames = revisedInputMap.keys,
            goldenAbstractionHint = goldenOpsPerTarget[outName],
            goldenSeqResetVals = goldenSeqResetVals,
            dftConstrained = dftConstrained,
          )
          poConeAudits[outName] = audit
          if (audit.abstractionReason != null) {
            abstractionSet += audit.abstractionReason
          }
        }
      }
    }

    // 2. Sequential Register Key-Point Mapping
    val allRegNames = (goldenSeqTargets.keys + revisedSeqMap.keys).distinct()
    val regConeAudits = mutableMapOf<String, ConeAuditResult>()

    allRegNames.forEach { regName ->
      val goldenPresent = regName in goldenSeqTargets
      val revSeqNode = revisedSeqMap[regName]
      when {
        !goldenPresent && revSeqNode != null -> {
          comparePoints += LecComparePointResult(
            id = "dff_$regName",
            name = regName,
            kind = if (revSeqNode.gateType == GateType.LOCKUP_LATCH) ComparePointKind.LATCH else ComparePointKind.REGISTER_DFF,
            bitWidth = revSeqNode.bitWidth,
            matchStatus = KeyPointMatchStatus.REVISED_ONLY,
            proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
            verdict = ComparePointVerdict.INCONCLUSIVE,
            inputSupportBits = 0,
            coneGateCount = 1,
            diagnosticReason = "Extra sequential cell '${revSeqNode.instanceName}' (${revSeqNode.cellName}) in netlist not mapped to Golden register.",
            netlistNodeIds = listOf(revSeqNode.id),
          )
        }
        goldenPresent && revSeqNode == null -> {
          val gWidth = goldenWidths[regName] ?: 1
          comparePoints += LecComparePointResult(
            id = "dff_$regName",
            name = regName,
            kind = ComparePointKind.REGISTER_DFF,
            bitWidth = gWidth,
            matchStatus = KeyPointMatchStatus.GOLDEN_ONLY,
            proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
            verdict = ComparePointVerdict.NON_EQUIVALENT,
            inputSupportBits = 0,
            coneGateCount = 0,
            diagnosticReason = "Golden sequential register '$regName' has no corresponding flip-flop in revised netlist.",
            rtlSourceLine = goldenSeqTargets[regName] ?: 1,
          )
        }
        goldenPresent && revSeqNode != null -> {
          val gWidth = goldenWidths[regName] ?: revSeqNode.bitWidth
          if (gWidth != revSeqNode.bitWidth) {
            comparePoints += LecComparePointResult(
              id = "dff_$regName",
              name = regName,
              kind = ComparePointKind.REGISTER_DFF,
              bitWidth = gWidth,
              matchStatus = KeyPointMatchStatus.WIDTH_MISMATCH,
              proofMethod = VerificationProofMethod.UNMAPPED_SKIPPED,
              verdict = ComparePointVerdict.NON_EQUIVALENT,
              inputSupportBits = 0,
              coneGateCount = 1,
              diagnosticReason = "Register width mismatch on '$regName': Golden [${gWidth}b] vs Netlist [${revSeqNode.bitWidth}b].",
              netlistNodeIds = listOf(revSeqNode.id),
              rtlSourceLine = goldenSeqTargets[regName] ?: 1,
            )
          } else {
            val dInputNet = revSeqNode.inputs.firstOrNull() ?: regName
            val rstInputNet = when (revSeqNode.gateType) {
              GateType.DFFR, GateType.SDFFR -> revSeqNode.inputs.getOrNull(2)
              else -> null
            }
            val combinedRoots = listOfNotNull(dInputNet, rstInputNet)
            val dAudit = auditRevisedConeMultiRoot(
              rootNets = combinedRoots,
              extraInitialNode = revSeqNode,
              revDriverByNet = revDriverByNet,
              revisedInputNames = revisedInputMap.keys,
              goldenAbstractionHint = goldenOpsPerTarget[regName],
              nonZeroResetConst = goldenSeqResetVals[regName],
              dftConstrained = dftConstrained,
            )
            regConeAudits[regName] = dAudit
            if (dAudit.abstractionReason != null) {
              abstractionSet += dAudit.abstractionReason
            }
          }
        }
      }
    }

    // 3. Run Exhaustive 2^N SAT-Style Miter on exact combinational Primary Outputs
    val combMatchedOutputs = allOutNames.filter { outName ->
      val audit = poConeAudits[outName] ?: return@filter false
      !audit.isSequentialCone && audit.abstractionReason == null &&
        missingRevInputs.isEmpty() && mismatchedInputWidths.isEmpty()
    }

    val miterVerifiedOutputs = mutableSetOf<String>()
    combMatchedOutputs.forEach { outName ->
      val gp = goldenOutMap[outName] ?: return@forEach
      val audit = poConeAudits[outName] ?: return@forEach
      val relevantInputs = goldenInputs.filter { it.name in audit.supportInputs && it.name != clockPort }
        .ifEmpty { goldenInputs.filter { it.name != clockPort && !(dftConstrained && isScanTestPort(it.name)) } }
      val totalBits = relevantInputs.sumOf { it.width }

      if (totalBits <= config.exhaustiveBitLimit.coerceIn(1, 14)) {
        val miterFailure = runExhaustiveCombMiter(
          targetOut = outName,
          outWidth = gp.width,
          supportInputs = relevantInputs,
          goldenDesign = goldenDesign,
          goldenWidths = goldenWidths,
          revisedNodes = revised.nodes,
          portIndirection = revPortOutIndirection,
          dftConstrained = dftConstrained,
        )
        miterVerifiedOutputs += outName
        if (miterFailure != null) {
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.EXACT_EXHAUSTIVE_MITER,
            verdict = ComparePointVerdict.NON_EQUIVALENT,
            inputSupportBits = totalBits,
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = "Exhaustive miter failed (Golden != Netlist) on ${1L shl totalBits}-vector check.",
            counterexample = miterFailure,
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = gp.line,
          )
        } else {
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.EXACT_EXHAUSTIVE_MITER,
            verdict = ComparePointVerdict.PROVEN_EQUIVALENT,
            inputSupportBits = totalBits,
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = "Formally proven across all ${1L shl totalBits} input combinations (Miter XOR == 0).",
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = gp.line,
          )
        }
      }
    }

    // 4. Add INCONCLUSIVE Compare Points for any PO or Reg whose cone contains lossy abstractions
    allOutNames.forEach { outName ->
      val gp = goldenOutMap[outName] ?: return@forEach
      val audit = poConeAudits[outName] ?: return@forEach
      if (audit.abstractionReason != null) {
        val supportBits = goldenInputs.filter { it.name in audit.supportInputs && it.name != clockPort }.sumOf { it.width }
        comparePoints += LecComparePointResult(
          id = "po_$outName",
          name = outName,
          kind = ComparePointKind.PRIMARY_OUTPUT,
          bitWidth = gp.width,
          matchStatus = KeyPointMatchStatus.MATCHED,
          proofMethod = VerificationProofMethod.STRUCTURAL_KEYPOINT_ONLY,
          verdict = ComparePointVerdict.INCONCLUSIVE,
          inputSupportBits = supportBits,
          coneGateCount = audit.coneNodes.size,
          diagnosticReason = audit.abstractionReason,
          netlistNodeIds = audit.coneNodes.map { it.id },
          rtlSourceLine = gp.line,
        )
      }
    }

    allRegNames.forEach { regName ->
      val audit = regConeAudits[regName] ?: return@forEach
      if (audit.abstractionReason != null) {
        val gWidth = goldenWidths[regName] ?: 1
        val supportBits = goldenInputs.filter { it.name in audit.supportInputs && it.name != clockPort }.sumOf { it.width }
        comparePoints += LecComparePointResult(
          id = "dff_$regName",
          name = regName,
          kind = ComparePointKind.REGISTER_DFF,
          bitWidth = gWidth,
          matchStatus = KeyPointMatchStatus.MATCHED,
          proofMethod = VerificationProofMethod.STRUCTURAL_KEYPOINT_ONLY,
          verdict = ComparePointVerdict.INCONCLUSIVE,
          inputSupportBits = supportBits,
          coneGateCount = audit.coneNodes.size,
          diagnosticReason = audit.abstractionReason,
          netlistNodeIds = audit.coneNodes.map { it.id },
          rtlSourceLine = goldenSeqTargets[regName] ?: 1,
        )
      }
    }

    // 5. Run Lockstep Bounded Functional / Sequential Verification (BMC) for remaining exact POs & Registers
    val bmcOutTargets = allOutNames.filter { outName ->
      outName !in miterVerifiedOutputs &&
        poConeAudits[outName]?.abstractionReason == null &&
        poConeAudits.containsKey(outName)
    }
    val bmcRegTargets = allRegNames.filter { regName ->
      regConeAudits[regName]?.abstractionReason == null &&
        regConeAudits.containsKey(regName)
    }

    if (bmcOutTargets.isNotEmpty() || bmcRegTargets.isNotEmpty()) {
      if (missingRevInputs.isNotEmpty() || mismatchedInputWidths.isNotEmpty()) {
        val ifaceReason = "Primary input interface mismatch prevents bounded lockstep comparison."
        abstractionSet += ifaceReason
        bmcOutTargets.forEach { outName ->
          val gp = goldenOutMap[outName] ?: return@forEach
          val audit = poConeAudits[outName]!!
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.STRUCTURAL_KEYPOINT_ONLY,
            verdict = ComparePointVerdict.INCONCLUSIVE,
            inputSupportBits = goldenInputs.filter { it.name in audit.supportInputs }.sumOf { it.width },
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = ifaceReason,
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = gp.line,
          )
        }
        bmcRegTargets.forEach { regName ->
          val audit = regConeAudits[regName]!!
          comparePoints += LecComparePointResult(
            id = "dff_$regName",
            name = regName,
            kind = ComparePointKind.REGISTER_DFF,
            bitWidth = goldenWidths[regName] ?: 1,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.STRUCTURAL_KEYPOINT_ONLY,
            verdict = ComparePointVerdict.INCONCLUSIVE,
            inputSupportBits = goldenInputs.filter { it.name in audit.supportInputs }.sumOf { it.width },
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = ifaceReason,
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = goldenSeqTargets[regName] ?: 1,
          )
        }
      } else {
        val depth = config.bmcCycleDepth.coerceIn(8, 64)
        val bmcCexMap = runBoundedLockstepCheck(
          goldenDesign = goldenDesign,
          goldenInputs = goldenInputs,
          goldenWidths = goldenWidths,
          revisedNodes = revised.nodes,
          portIndirection = revPortOutIndirection,
          outTargets = bmcOutTargets.map { it to (goldenOutMap[it]?.width ?: 1) },
          regTargets = bmcRegTargets.map { it to (goldenWidths[it] ?: 1) },
          clockPort = clockPort,
          resetPort = resetPort,
          resetActiveLow = resetActiveLow,
          dftConstrained = dftConstrained,
          cycleDepth = depth,
        )

        bmcOutTargets.forEach { outName ->
          val gp = goldenOutMap[outName] ?: return@forEach
          val audit = poConeAudits[outName]!!
          val cex = bmcCexMap["PO:$outName"]
          val supportBits = goldenInputs.filter { it.name in audit.supportInputs && it.name != clockPort }.sumOf { it.width }
          comparePoints += LecComparePointResult(
            id = "po_$outName",
            name = outName,
            kind = ComparePointKind.PRIMARY_OUTPUT,
            bitWidth = gp.width,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.BOUNDED_STIMULUS_CHECK,
            verdict = if (cex != null) ComparePointVerdict.NON_EQUIVALENT else ComparePointVerdict.BOUNDED_EQUIVALENT,
            inputSupportBits = supportBits,
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = if (cex != null) {
              "Bounded check mismatch at cycle ${cex.cycleIndex} (Golden ${cex.goldenValueHex} vs Netlist ${cex.revisedValueHex})."
            } else {
              "Verified across $depth deterministic reset, corner-case, and PRNG cycles with 0 mismatches."
            },
            counterexample = cex,
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = gp.line,
          )
        }

        bmcRegTargets.forEach { regName ->
          val audit = regConeAudits[regName]!!
          val cex = bmcCexMap["REG:$regName"]
          val supportBits = goldenInputs.filter { it.name in audit.supportInputs && it.name != clockPort }.sumOf { it.width }
          comparePoints += LecComparePointResult(
            id = "dff_$regName",
            name = regName,
            kind = ComparePointKind.REGISTER_DFF,
            bitWidth = goldenWidths[regName] ?: 1,
            matchStatus = KeyPointMatchStatus.MATCHED,
            proofMethod = VerificationProofMethod.BOUNDED_STIMULUS_CHECK,
            verdict = if (cex != null) ComparePointVerdict.NON_EQUIVALENT else ComparePointVerdict.BOUNDED_EQUIVALENT,
            inputSupportBits = supportBits,
            coneGateCount = audit.coneNodes.size,
            diagnosticReason = if (cex != null) {
              "Register state divergence at cycle ${cex.cycleIndex} (Golden ${cex.goldenValueHex} vs Netlist ${cex.revisedValueHex})."
            } else {
              "Register next-state & reset trajectories match Golden RTL across $depth BMC cycles."
            },
            counterexample = cex,
            netlistNodeIds = audit.coneNodes.map { it.id },
            rtlSourceLine = goldenSeqTargets[regName] ?: 1,
          )
        }
      }
    }

    val sortedPoints = comparePoints.sortedWith(
      compareBy<LecComparePointResult>(
        {
          when (it.verdict) {
            ComparePointVerdict.NON_EQUIVALENT -> 0
            ComparePointVerdict.INCONCLUSIVE -> 1
            ComparePointVerdict.BOUNDED_EQUIVALENT -> 2
            ComparePointVerdict.PROVEN_EQUIVALENT -> 3
          }
        },
        { it.kind.ordinal },
        { it.name },
      ),
    )

    val matchedCount = sortedPoints.count { it.matchStatus == KeyPointMatchStatus.MATCHED }
    val unmappedGolden = sortedPoints.count { it.matchStatus == KeyPointMatchStatus.GOLDEN_ONLY }
    val unmappedRevised = sortedPoints.count { it.matchStatus == KeyPointMatchStatus.REVISED_ONLY }
    val provenCount = sortedPoints.count { it.verdict == ComparePointVerdict.PROVEN_EQUIVALENT }
    val boundedCount = sortedPoints.count { it.verdict == ComparePointVerdict.BOUNDED_EQUIVALENT }
    val nonEqCount = sortedPoints.count { it.verdict == ComparePointVerdict.NON_EQUIVALENT }
    val inconclusiveCount = sortedPoints.count { it.verdict == ComparePointVerdict.INCONCLUSIVE }

    val overall = when {
      sortedPoints.isEmpty() -> LecOverallStatus.UNAVAILABLE
      nonEqCount > 0 -> LecOverallStatus.NON_EQUIVALENT
      inconclusiveCount > 0 -> LecOverallStatus.INCONCLUSIVE
      else -> LecOverallStatus.EQUIVALENT
    }

    logs += "[LEC-04] Compare summary: total=${sortedPoints.size}, mapped=$matchedCount, proven=$provenCount, bounded=$boundedCount, non_eq=$nonEqCount, inconclusive=$inconclusiveCount."
    abstractionSet.forEach { reason -> logs += "[LEC-NOTE] $reason" }
    sortedPoints.filter { it.verdict == ComparePointVerdict.NON_EQUIVALENT }.take(5).forEach { fail ->
      logs += "[FAIL] ${fail.kind.shortTag} '${fail.name}': ${fail.diagnosticReason}"
    }
    logs += "[LEC-DONE] Verdict = ${overall.badge}"

    val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
    val setupSummary = buildString {
      append("Golden '${goldenTop.name}' vs Revised ")
      append(if (revised.usedCustomNetlist) "Custom Netlist" else "${report.targetLibrary.shortName} Netlist")
      if (dftConstrained) append(" · SE=0")
      if (isNetlistStale) append(" · Stale Snapshot")
    }

    return FormalEquivalenceReport(
      moduleName = goldenTop.name,
      targetLibrary = report.targetLibrary,
      overallStatus = overall,
      setupSummary = setupSummary,
      goldenPortCount = goldenTop.ports.size,
      revisedPortCount = revised.ports.size,
      totalComparePoints = sortedPoints.size,
      matchedCount = matchedCount,
      unmappedGoldenCount = unmappedGolden,
      unmappedRevisedCount = unmappedRevised,
      provenCount = provenCount,
      boundedEquivalentCount = boundedCount,
      nonEquivalentCount = nonEqCount,
      inconclusiveCount = inconclusiveCount,
      abstractionReasons = abstractionSet.toList(),
      isNetlistStale = isNetlistStale,
      dftScanConstrained = dftConstrained,
      comparePoints = sortedPoints,
      executionDurationMs = elapsedMs,
      logLines = logs,
    )
  }

  private fun emptyUnavailableReport(
    moduleName: String,
    library: TargetLibrary,
    summary: String,
    isNetlistStale: Boolean,
    logs: List<String>,
    startedNs: Long,
  ): FormalEquivalenceReport = FormalEquivalenceReport(
    moduleName = moduleName,
    targetLibrary = library,
    overallStatus = LecOverallStatus.UNAVAILABLE,
    setupSummary = summary,
    goldenPortCount = 0,
    revisedPortCount = 0,
    totalComparePoints = 0,
    matchedCount = 0,
    unmappedGoldenCount = 0,
    unmappedRevisedCount = 0,
    provenCount = 0,
    boundedEquivalentCount = 0,
    nonEquivalentCount = 0,
    inconclusiveCount = 0,
    abstractionReasons = emptyList(),
    isNetlistStale = isNetlistStale,
    dftScanConstrained = false,
    comparePoints = emptyList(),
    executionDurationMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L),
    logLines = logs,
  )

  private fun buildRevisedModel(
    report: SynthesisReport,
    customOverride: String?,
  ): RevisedModel {
    val overrideText = customOverride?.trim().orEmpty()
    if (overrideText.isNotEmpty()) {
      val parsed = StructuralNetlistParser.parseVerilogNetlistStructure(overrideText)
      val ports = parsed.ports.map {
        RtlPort(
          name = it.name,
          direction = it.direction,
          width = it.width,
          isReg = false,
        )
      }
      val portMap = ports.associateBy { it.name }
      val nodes = mutableListOf<GateNode>()
      ports.forEach { p ->
        when (p.direction) {
          PortDirection.INPUT, PortDirection.INOUT -> nodes += GateNode(
            id = "in_${p.name}",
            cellName = "INPUT",
            gateType = GateType.INPUT_PORT,
            instanceName = p.name,
            inputs = emptyList(),
            outputNet = p.name,
            bitWidth = p.width,
            delayNs = 0.0,
            areaUm2 = 0.0,
            powerUw = 0.0,
          )
          PortDirection.OUTPUT -> nodes += GateNode(
            id = "out_${p.name}",
            cellName = "OUTPUT",
            gateType = GateType.OUTPUT_PORT,
            instanceName = p.name,
            inputs = listOf(p.name),
            outputNet = p.name,
            bitWidth = p.width,
            delayNs = 0.0,
            areaUm2 = 0.0,
            powerUw = 0.0,
          )
        }
      }
      parsed.instances.forEachIndexed { idx, inst ->
        val gateType = StructuralNetlistParser.classifyGateTypeFromCell(inst.cellType)
        val outPinNames = setOf("Y", "Q", "QN", "CO", "COUT", "O", "OUT", "Z", "ZN", "S_OUT")
        val namedOut = inst.namedPins.entries.firstOrNull { it.key.uppercase(Locale.ROOT) in outPinNames }?.value
        val outputNet = namedOut ?: inst.positionalPins.firstOrNull() ?: "_rev_out_$idx"
        val orderedInPins = listOf("S", "D", "A", "B", "C", "CLK", "CP", "GN", "G", "RST", "RN", "RESET", "SE", "SI")
        val inputNets = if (inst.namedPins.isNotEmpty()) {
          val nonOut = inst.namedPins.filterKeys { it.uppercase(Locale.ROOT) !in outPinNames }
          val seqOrdered = if (gateType.isSequential) {
            listOf("D", "CLK", "CP", "RST", "RN", "RESET", "SE", "SI", "GN", "G")
              .mapNotNull { k -> nonOut.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value }
          } else {
            val prioritized = orderedInPins
              .mapNotNull { k -> nonOut.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value }
            val rest = nonOut.filterKeys { k -> orderedInPins.none { it.equals(k, ignoreCase = true) } }.values
            prioritized + rest
          }
          seqOrdered.distinct()
        } else {
          inst.positionalPins.drop(1)
        }
        val width = (listOf(outputNet) + inputNets).mapNotNull { n ->
          val base = n.substringBefore('[')
          parsed.wireWidths[base] ?: portMap[base]?.width
        }.maxOrNull() ?: 1

        nodes += GateNode(
          id = "rev_${idx}_${inst.instanceName}",
          cellName = inst.cellType,
          gateType = gateType,
          instanceName = inst.instanceName,
          inputs = inputNets,
          outputNet = outputNet,
          bitWidth = width,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
        )
      }
      parsed.continuousAssigns.forEachIndexed { idx, (lhs, rhs) ->
        val w = portMap[lhs]?.width ?: parsed.wireWidths[lhs] ?: 1
        nodes += GateNode(
          id = "rev_assign_${idx}_$lhs",
          cellName = "BUF",
          gateType = GateType.BUF,
          instanceName = "u_assign_$lhs",
          inputs = listOf(rhs),
          outputNet = lhs,
          bitWidth = w,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
        )
      }
      return RevisedModel(
        moduleName = parsed.moduleName.ifBlank { report.moduleName },
        ports = ports,
        nodes = nodes,
        usedCustomNetlist = true,
      )
    }

    return RevisedModel(
      moduleName = report.moduleName,
      ports = report.ports,
      nodes = report.nodes,
      usedCustomNetlist = false,
    )
  }

  private fun isScanTestPort(name: String): Boolean {
    val low = name.lowercase(Locale.ROOT)
    return low == "scan_en" || low.startsWith("scan_in") || low.startsWith("scan_out") ||
      low == "tck" || low == "tms" || low == "tdi" || low == "tdo"
  }

  // ==================== CONE & ABSTRACTION AUDITING ====================

  private fun auditRevisedCone(
    targetNet: String,
    revDriverByNet: Map<String, GateNode>,
    revisedInputNames: Set<String>,
    goldenAbstractionHint: String?,
    goldenSeqResetVals: Map<String, Long>,
    dftConstrained: Boolean,
  ): ConeAuditResult = auditRevisedConeMultiRoot(
    rootNets = listOf(targetNet),
    extraInitialNode = null,
    revDriverByNet = revDriverByNet,
    revisedInputNames = revisedInputNames,
    goldenAbstractionHint = goldenAbstractionHint,
    nonZeroResetConst = goldenSeqResetVals[targetNet],
    dftConstrained = dftConstrained,
  )

  private fun auditRevisedConeMultiRoot(
    rootNets: List<String>,
    extraInitialNode: GateNode?,
    revDriverByNet: Map<String, GateNode>,
    revisedInputNames: Set<String>,
    goldenAbstractionHint: String?,
    nonZeroResetConst: Long?,
    dftConstrained: Boolean,
  ): ConeAuditResult {
    val visitedNets = mutableSetOf<String>()
    val coneNodes = mutableListOf<GateNode>()
    val supportInputs = mutableSetOf<String>()
    var isSeqCone = extraInitialNode != null
    var abstractionReason: String? = goldenAbstractionHint

    if (nonZeroResetConst != null && nonZeroResetConst != 0L && abstractionReason == null) {
      abstractionReason = "Sequential reset constant ($nonZeroResetConst) is not explicitly parameterized on DFFR primitive."
    }

    extraInitialNode?.let { seq ->
      coneNodes += seq
      if (seq.gateType == GateType.LOCKUP_LATCH) {
        abstractionReason = abstractionReason ?: "Sequential latch '${seq.instanceName}' requires cycle-level latch timing check."
      }
      if ((seq.gateType == GateType.SDFF || seq.gateType == GateType.SDFFR) && !dftConstrained) {
        abstractionReason = abstractionReason ?: "Scan-enabled flop '${seq.instanceName}' requires scan_en = 0 constraint for functional equivalence."
      }
    }

    val queue = ArrayDeque<String>()
    rootNets.forEach { queue.addLast(it) }

    while (queue.isNotEmpty()) {
      val net = queue.removeFirst()
      if (!visitedNets.add(net)) continue
      if (isConstantNetToken(net)) continue
      if (net in revisedInputNames) {
        supportInputs += net
        continue
      }
      val driver = revDriverByNet[net] ?: continue
      coneNodes += driver

      // Detect unmodeled/lossy abstractions in structural netlist
      if (abstractionReason == null) {
        abstractionReason = detectGateAbstraction(driver, revDriverByNet)
      }

      if (driver.gateType.isSequential) {
        isSeqCone = true
        val dNet = driver.inputs.firstOrNull()
        if (dNet != null) queue.addLast(dNet)
        if (driver.gateType == GateType.DFFR || driver.gateType == GateType.SDFFR) {
          driver.inputs.getOrNull(2)?.let { queue.addLast(it) }
        }
      } else {
        driver.inputs.forEach { inNet ->
          queue.addLast(inNet)
        }
      }
    }

    return ConeAuditResult(
      coneNodes = coneNodes.distinctBy { it.id },
      supportInputs = supportInputs,
      isSequentialCone = isSeqCone,
      abstractionReason = abstractionReason,
    )
  }

  private fun detectGateAbstraction(
    node: GateNode,
    revDriverByNet: Map<String, GateNode>,
  ): String? {
    val upperCell = node.cellName.uppercase(Locale.ROOT)
    return when {
      node.gateType == GateType.LUT4 || node.gateType == GateType.LUT6 || node.gateType == GateType.CARRY4 ||
        "LUT" in upperCell || "CARRY4" in upperCell ->
        "FPGA primitive '${node.cellName}' (${node.instanceName}) lacks INIT truth-table equation in structural netlist."
      node.gateType == GateType.CMP_CELL ->
        "Comparator macro '${node.cellName}' (${node.instanceName}) abstracts relational operator (==, !=, <, >=) in structural netlist."
      node.gateType == GateType.BUF && node.outputNet.startsWith("_slice_") ->
        "Bit-slice operator lowered to untagged BUF (${node.instanceName}) without [msb:lsb] selector in structural netlist."
      node.gateType == GateType.BUF && node.inputs.size > 1 ->
        "Multi-input macro/concat '${node.cellName}' (${node.instanceName}) has packed operands without explicit bit slice metadata."
      node.gateType == GateType.BUF &&
        !upperCell.contains("BUF") &&
        !upperCell.contains("ASSIGN") ->
        "Black-box/macro cell '${node.cellName}' (${node.instanceName}) lacks structural gate truth model."
      node.gateType == GateType.XOR2 && node.bitWidth > 1 &&
        node.inputs.any { revDriverByNet[it]?.let { drv -> drv.bitWidth == 1 && drv.gateType == GateType.OR2 } == true } ->
        "Bit-blasted wide arithmetic chain (${node.instanceName}) lacks per-bit wire indexing in structural netlist."
      else -> null
    }
  }

  private fun isConstantNetToken(net: String): Boolean {
    val t = net.trim()
    return t == "1'b0" || t == "1'b1" || t == "0" || t == "1" ||
      t.startsWith("_const_") || Regex("""^\d+'[bBdDhHoO][0-9a-fA-FxXzZ_]+$""").matches(t)
  }

  // ==================== EXHAUSTIVE 2^N MITER ====================

  private fun runExhaustiveCombMiter(
    targetOut: String,
    outWidth: Int,
    supportInputs: List<RtlPort>,
    goldenDesign: ParsedVerilogDesign,
    goldenWidths: Map<String, Int>,
    revisedNodes: List<GateNode>,
    portIndirection: Map<String, String>,
    dftConstrained: Boolean,
  ): CounterexampleStep? {
    val totalBits = supportInputs.sumOf { it.width }
    val totalVectors = 1L shl totalBits

    for (vec in 0L until totalVectors) {
      val piValues = linkedMapOf<String, Long>()
      var bitCursor = 0
      supportInputs.forEach { port ->
        val w = port.width.coerceIn(1, 32)
        val raw = (vec ushr bitCursor) and maskValue(-1L, w)
        piValues[port.name] = raw
        bitCursor += w
      }

      val goldenEnv = evaluateGoldenCombStep(goldenDesign, goldenWidths, piValues)
      val revisedEnv = evaluateRevisedCombStep(
        nodes = revisedNodes,
        inputValues = piValues,
        seqState = emptyMap(),
        portIndirection = portIndirection,
        dftConstrained = dftConstrained,
      )

      val gVal = maskValue(goldenEnv[targetOut] ?: 0L, outWidth)
      val rVal = maskValue(revisedEnv[targetOut] ?: 0L, outWidth)

      if ((gVal xor rVal) != 0L) {
        val stim = supportInputs.associate { p ->
          p.name to formatHex(piValues[p.name] ?: 0L, p.width)
        }
        return CounterexampleStep(
          cycleIndex = 0,
          stimulusInputs = stim,
          goldenValueHex = formatHex(gVal, outWidth),
          revisedValueHex = formatHex(rVal, outWidth),
          goldenValueBin = formatBin(gVal, outWidth),
          revisedValueBin = formatBin(rVal, outWidth),
        )
      }
    }
    return null
  }

  // ==================== BOUNDED LOCKSTEP SEQUENTIAL CHECK ====================

  private fun runBoundedLockstepCheck(
    goldenDesign: ParsedVerilogDesign,
    goldenInputs: List<RtlPort>,
    goldenWidths: Map<String, Int>,
    revisedNodes: List<GateNode>,
    portIndirection: Map<String, String>,
    outTargets: List<Pair<String, Int>>,
    regTargets: List<Pair<String, Int>>,
    clockPort: String?,
    resetPort: String?,
    resetActiveLow: Boolean,
    dftConstrained: Boolean,
    cycleDepth: Int,
  ): Map<String, CounterexampleStep> {
    val goldenTop = goldenDesign.topModule
    val goldenSignalEnv = linkedMapOf<String, Long>().apply {
      goldenWidths.keys.forEach { put(it, 0L) }
      goldenTop.parameters.forEach { put(it.name, it.value.toLong()) }
    }
    val subModuleStates = mutableMapOf<String, MutableMap<String, Long>>()

    val seqNodes = revisedNodes.filter { it.gateType.isSequential }
    val revisedRegState = linkedMapOf<String, Long>().apply {
      seqNodes.forEach { put(it.outputNet, 0L) }
    }

    val mismatches = linkedMapOf<String, CounterexampleStep>()
    var rngState = 0x5EED_C0DE_1234_5679L

    for (cycle in 0 until cycleDepth) {
      val piValues = linkedMapOf<String, Long>()
      val inResetPhase = cycle < 2 || cycle == (cycleDepth * 3) / 4

      goldenInputs.forEach { port ->
        val valForPort = when {
          port.name == clockPort -> (cycle % 2).toLong()
          port.name == resetPort -> {
            if (resetActiveLow) {
              if (inResetPhase) 0L else 1L
            } else {
              if (inResetPhase) 1L else 0L
            }
          }
          inResetPhase -> 0L
          cycle == 2 -> 0L
          cycle == 3 -> maskValue(-1L, port.width)
          cycle == 4 -> maskValue(0x5555_5555_5555_5555L, port.width)
          cycle == 5 -> maskValue(0x5555_5555_5555_5555L.inv(), port.width)
          else -> {
            rngState = nextSplitMix(rngState)
            maskValue(rngState, port.width)
          }
        }
        piValues[port.name] = valForPort
        goldenSignalEnv[port.name] = valForPort
      }

      // 1. Evaluate Golden combinational network at current state
      evaluateGoldenCombNetwork(
        design = goldenDesign,
        module = goldenTop,
        signalValues = goldenSignalEnv,
        widths = goldenWidths,
        subModuleStates = subModuleStates,
      )

      // 2. Evaluate Revised netlist combinational network at current state
      val revisedNetEnv = evaluateRevisedCombStep(
        nodes = revisedNodes,
        inputValues = piValues,
        seqState = revisedRegState,
        portIndirection = portIndirection,
        dftConstrained = dftConstrained,
      )

      val stimMap by lazy {
        goldenInputs.filter { it.name != clockPort }.associate { p ->
          p.name to formatHex(piValues[p.name] ?: 0L, p.width)
        }
      }

      // 3. Compare Primary Outputs
      outTargets.forEach { (outName, width) ->
        if ("PO:$outName" !in mismatches) {
          val gVal = maskValue(goldenSignalEnv[outName] ?: 0L, width)
          val rVal = maskValue(revisedNetEnv[outName] ?: 0L, width)
          if (gVal != rVal) {
            mismatches["PO:$outName"] = CounterexampleStep(
              cycleIndex = cycle,
              stimulusInputs = stimMap,
              goldenValueHex = formatHex(gVal, width),
              revisedValueHex = formatHex(rVal, width),
              goldenValueBin = formatBin(gVal, width),
              revisedValueBin = formatBin(rVal, width),
            )
          }
        }
      }

      // 4. Advance Rising Clock Edge on BOTH Golden & Revised Sequential Elements
      val goldenNextRegs = linkedMapOf<String, Long>()
      goldenTop.alwaysBlocks.filter { it.isSequential }.forEach { block ->
        val blockBlocking = linkedMapOf<String, Long>()
        execGoldenStmts(
          stmts = block.statements,
          env = goldenSignalEnv,
          widths = goldenWidths,
          blockingOut = blockBlocking,
          nonBlockingOut = goldenNextRegs,
          isSequential = true,
        )
      }
      goldenNextRegs.forEach { (name, value) ->
        goldenSignalEnv[name] = maskValue(value, goldenWidths[name] ?: 1)
      }

      val revNextRegs = linkedMapOf<String, Long>()
      seqNodes.forEach { seq ->
        val dVal = resolveNetToken(seq.inputs.getOrNull(0), revisedNetEnv)
        val hasRst = seq.gateType == GateType.DFFR || seq.gateType == GateType.SDFFR
        val rstVal = if (hasRst) {
          seq.inputs.getOrNull(2)?.let { resolveNetToken(it, revisedNetEnv) } ?: 0L
        } else {
          0L
        }
        val seIdx = if (hasRst) 3 else 2
        val siIdx = if (hasRst) 4 else 3
        val seVal = if (dftConstrained) 0L else seq.inputs.getOrNull(seIdx)?.let { resolveNetToken(it, revisedNetEnv) } ?: 0L
        val siVal = seq.inputs.getOrNull(siIdx)?.let { resolveNetToken(it, revisedNetEnv) } ?: 0L
        val effectiveD = if ((seVal and 1L) != 0L) siVal else dVal
        revNextRegs[seq.outputNet] = if ((rstVal and 1L) != 0L) 0L else maskValue(effectiveD, seq.bitWidth)
      }
      revNextRegs.forEach { (name, value) ->
        revisedRegState[name] = value
      }

      // 5. Compare Registered Q State Immediately After Clock Edge
      regTargets.forEach { (regName, width) ->
        if ("REG:$regName" !in mismatches) {
          val gVal = maskValue(goldenSignalEnv[regName] ?: 0L, width)
          val rVal = maskValue(revisedRegState[regName] ?: 0L, width)
          if (gVal != rVal) {
            mismatches["REG:$regName"] = CounterexampleStep(
              cycleIndex = cycle,
              stimulusInputs = stimMap,
              goldenValueHex = formatHex(gVal, width),
              revisedValueHex = formatHex(rVal, width),
              goldenValueBin = formatBin(gVal, width),
              revisedValueBin = formatBin(rVal, width),
            )
          }
        }
      }
    }

    return mismatches
  }

  private fun nextSplitMix(x: Long): Long {
    var z = x + -7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return z xor (z ushr 31)
  }

  // ==================== GOLDEN AST HELPERS ====================

  private fun collectGoldenSequentialRegisters(module: ParsedVerilogModule): Map<String, Int> {
    val map = linkedMapOf<String, Int>()
    module.alwaysBlocks.filter { it.isSequential }.forEach { block ->
      val targets = linkedSetOf<String>()
      collectAssignedTargets(block.statements, targets)
      targets.forEach { t -> map.putIfAbsent(t, block.line) }
    }
    return map
  }

  private fun collectGoldenSeqResetConstants(module: ParsedVerilogModule): Map<String, Long> {
    val result = linkedMapOf<String, Long>()
    module.alwaysBlocks.filter { it.isSequential }.forEach { block ->
      val firstIf = block.statements.firstOrNull() as? VerilogStmt.IfStmt ?: return@forEach
      if (firstIf.elseBranch.isEmpty()) return@forEach
      val condStr = firstIf.cond.toString().lowercase(Locale.ROOT)
      val isRst = "rst" in condStr || "reset" in condStr || firstIf.cond is VerilogExpr.Unary
      if (!isRst) return@forEach
      firstIf.thenBranch.forEach { stmt ->
        if (stmt is VerilogStmt.Assign && stmt.expr is VerilogExpr.Const) {
          result[stmt.target] = stmt.expr.value.toLong()
        }
      }
    }
    return result
  }

  private fun analyzeGoldenUnsupportedOps(
    design: ParsedVerilogDesign,
    top: ParsedVerilogModule,
  ): Map<String, String> {
    val hints = linkedMapOf<String, String>()
    val signalReasons = linkedMapOf<String, String>()

    fun inspectExpr(expr: VerilogExpr): String? = when (expr) {
      is VerilogExpr.Const, is VerilogExpr.Signal -> null
      is VerilogExpr.Slice -> if (expr.msbExpr != null && expr.msbExpr !is VerilogExpr.Const) {
        "Dynamic bit-slice '${expr.name}[...]' lowers to untagged MUX2/BUF without slice metadata."
      } else {
        "Constant bit-slice '${expr.name}[${expr.msb}:${expr.lsb}]' lowers to untagged BUF without bit-range metadata."
      }
      is VerilogExpr.Index -> "Bit-index expression lowers without bit selector metadata in structural netlist."
      is VerilogExpr.Concat -> "Bit concatenation '{...}' lowers to multi-input BUF without bit packing widths."
      is VerilogExpr.Replication -> "Bit replication '{N{...}}' lowers to BUF without repeat count metadata."
      is VerilogExpr.FuncCall -> "Function call '${expr.name}' is abstracted in structural netlist."
      is VerilogExpr.Unary -> {
        val inner = inspectExpr(expr.inner)
        when {
          inner != null -> inner
          expr.op == "!" && inferExprWidth(expr.inner, emptyMap()) > 1 ->
            "Logical NOT '!' on multi-bit operand differs from bitwise INV in structural netlist."
          else -> null
        }
      }
      is VerilogExpr.Binary -> inspectExpr(expr.left) ?: inspectExpr(expr.right) ?: when (expr.op) {
        "<<", ">>", "<<<", ">>>" -> "Shift operator '${expr.op}' lowers to untagged BUF in structural netlist."
        "==", "!=", "===", "!==", "==?", "!=?", "<", "<=", ">", ">=" ->
          "Relational operator '${expr.op}' lowers to generic CMP cell without operator tag."
        "/", "%", "**" -> "Arithmetic operator '${expr.op}' shares generic MUL cell in structural netlist."
        else -> null
      }
      is VerilogExpr.Ternary -> inspectExpr(expr.cond) ?: inspectExpr(expr.thenExpr) ?: inspectExpr(expr.elseExpr)
    }

    fun inspectStmts(stmts: List<VerilogStmt>): String? {
      stmts.forEach { s ->
        when (s) {
          is VerilogStmt.Assign -> {
            if (s.targetMsb != null || s.targetLsb != null) {
              return "Partial bit-slice LHS assignment '${s.target}[${s.targetMsb}:${s.targetLsb}]' is abstracted in structural netlist."
            }
            inspectExpr(s.expr)?.let { return it }
          }
          is VerilogStmt.IfStmt -> {
            inspectExpr(s.cond)?.let { return it }
            inspectStmts(s.thenBranch)?.let { return it }
            inspectStmts(s.elseBranch)?.let { return it }
          }
          is VerilogStmt.CaseStmt -> {
            inspectExpr(s.selector)?.let { return it }
            s.branches.forEach { (pats, body) ->
              pats.forEach { p -> inspectExpr(p)?.let { return it } }
              inspectStmts(body)?.let { return it }
            }
            inspectStmts(s.defaultBranch)?.let { return it }
          }
          is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
        }
      }
      return null
    }

    top.assignments.forEach { a ->
      val r = inspectExpr(a.expr) ?: if (a.targetMsb != null || a.targetLsb != null) {
        "Partial bit-slice LHS '${a.target}[...]' abstracted in structural netlist."
      } else {
        null
      }
      if (r != null) signalReasons[a.target] = r
    }
    top.alwaysBlocks.forEach { b ->
      val targets = mutableSetOf<String>()
      collectAssignedTargets(b.statements, targets)
      val r = inspectStmts(b.statements)
      if (r != null) {
        targets.forEach { t -> signalReasons.putIfAbsent(t, r) }
      }
    }
    if (top.instances.isNotEmpty()) {
      top.instances.forEach { inst ->
        val sub = design.modules.firstOrNull { it.name == inst.moduleType }
        if (sub != null) {
          sub.ports.filter { it.direction == PortDirection.OUTPUT }.forEachIndexed { idx, p ->
            val conn = inst.portConnections[p.name] ?: inst.positionalConnections.getOrNull(idx)
            if (conn is VerilogExpr.Signal) {
              val subHints = analyzeGoldenUnsupportedOps(design, sub)
              subHints[p.name]?.let { signalReasons.putIfAbsent(conn.name, it) }
            }
          }
        }
      }
    }

    hints.putAll(signalReasons)
    return hints
  }

  private fun evaluateGoldenCombStep(
    design: ParsedVerilogDesign,
    widths: Map<String, Int>,
    piValues: Map<String, Long>,
  ): Map<String, Long> {
    val env = linkedMapOf<String, Long>()
    widths.keys.forEach { env[it] = 0L }
    design.topModule.parameters.forEach { env[it.name] = it.value.toLong() }
    piValues.forEach { (k, v) -> env[k] = maskValue(v, widths[k] ?: 1) }
    evaluateGoldenCombNetwork(
      design = design,
      module = design.topModule,
      signalValues = env,
      widths = widths,
      subModuleStates = mutableMapOf(),
    )
    return env
  }

  private fun evaluateGoldenCombNetwork(
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
        val rhs = evalGoldenExpr(assign.expr, signalValues, widths)
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
        execGoldenStmts(block.statements, signalValues, widths, nextComb, nextComb, isSequential = false)
        nextComb.forEach { (k, v) ->
          val masked = maskValue(v, widths[k] ?: 1)
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
              subEnv[port.name] = maskValue(evalGoldenExpr(expr, signalValues, widths), port.width)
            }
          }
        }
        evaluateGoldenCombNetwork(design, subMod, subEnv, subWidths, subModuleStates)
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

  private fun execGoldenStmts(
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
          val rhs = evalGoldenExpr(stmt.expr, localEnv, widths)
          val base = nonBlockingOut[stmt.target] ?: localEnv[stmt.target] ?: 0L
          val updated = applyAssignedValue(base, rhs, w, stmt.targetMsb, stmt.targetLsb)
          if (stmt.nonBlocking && isSequential) {
            nonBlockingOut[stmt.target] = updated
          } else {
            blockingOut[stmt.target] = updated
            localEnv[stmt.target] = updated
            if (isSequential) nonBlockingOut[stmt.target] = updated
          }
        }
        is VerilogStmt.IfStmt -> {
          val cond = evalGoldenExpr(stmt.cond, localEnv, widths) != 0L
          val branch = if (cond) stmt.thenBranch else stmt.elseBranch
          if (branch.isNotEmpty()) {
            execGoldenStmts(branch, localEnv, widths, blockingOut, nonBlockingOut, isSequential)
          }
        }
        is VerilogStmt.CaseStmt -> {
          val selVal = evalGoldenExpr(stmt.selector, localEnv, widths)
          val matched = stmt.branches.firstOrNull { (pats, _) ->
            pats.any { evalGoldenExpr(it, localEnv, widths) == selVal }
          }?.second ?: stmt.defaultBranch
          if (matched.isNotEmpty()) {
            execGoldenStmts(matched, localEnv, widths, blockingOut, nonBlockingOut, isSequential)
          }
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }
  }

  private fun evalGoldenExpr(
    expr: VerilogExpr,
    env: Map<String, Long>,
    widths: Map<String, Int>,
  ): Long = when (expr) {
    is VerilogExpr.Const -> maskValue(expr.value.toLong(), expr.width)
    is VerilogExpr.Signal -> maskValue(env[expr.name] ?: 0L, widths[expr.name] ?: 32)
    is VerilogExpr.Slice -> {
      val raw = env[expr.name] ?: 0L
      val lo = minOf(expr.msb, expr.lsb).coerceAtLeast(0)
      val sw = abs(expr.msb - expr.lsb) + 1
      maskValue(raw ushr lo, sw)
    }
    is VerilogExpr.Index -> {
      val baseVal = evalGoldenExpr(expr.base, env, widths)
      val idx = evalGoldenExpr(expr.indexExpr, env, widths).toInt().coerceIn(0, 61)
      maskValue(baseVal ushr idx, expr.width)
    }
    is VerilogExpr.FuncCall -> {
      val args = expr.args.map { evalGoldenExpr(it, env, widths) }
      when (expr.name.lowercase(Locale.ROOT)) {
        "\$clog2" -> {
          val v = (args.firstOrNull() ?: 0L).toInt()
          if (v <= 1) 0L else (32 - Integer.numberOfLeadingZeros(v - 1)).toLong()
        }
        else -> args.firstOrNull() ?: 0L
      }
    }
    is VerilogExpr.Concat -> {
      var acc = 0L
      expr.parts.forEach { part ->
        val pw = inferExprWidth(part, widths)
        val pv = maskValue(evalGoldenExpr(part, env, widths), pw)
        acc = (acc shl pw.coerceAtMost(32)) or pv
      }
      acc
    }
    is VerilogExpr.Replication -> {
      val iw = inferExprWidth(expr.inner, widths)
      val iv = maskValue(evalGoldenExpr(expr.inner, env, widths), iw)
      var acc = 0L
      repeat(expr.count.coerceIn(1, 32)) {
        acc = (acc shl iw.coerceAtMost(32)) or iv
      }
      acc
    }
    is VerilogExpr.Unary -> {
      val w = inferExprWidth(expr.inner, widths)
      val v = maskValue(evalGoldenExpr(expr.inner, env, widths), w)
      when (expr.op) {
        "~" -> maskValue(v.inv(), w)
        "+" -> v
        "!" -> if (v == 0L) 1L else 0L
        "-" -> maskValue(-v, w)
        "&" -> if (v == maskValue(-1L, w)) 1L else 0L
        "~&" -> if (v == maskValue(-1L, w)) 0L else 1L
        "|" -> if (v != 0L) 1L else 0L
        "~|" -> if (v != 0L) 0L else 1L
        "^" -> (java.lang.Long.bitCount(v) % 2).toLong()
        "~^", "^~" -> ((java.lang.Long.bitCount(v) % 2) xor 1).toLong()
        else -> v
      }
    }
    is VerilogExpr.Binary -> {
      val w = maxOf(inferExprWidth(expr.left, widths), inferExprWidth(expr.right, widths))
      val l = evalGoldenExpr(expr.left, env, widths)
      val r = evalGoldenExpr(expr.right, env, widths)
      when (expr.op) {
        "+" -> maskValue(l + r, w)
        "-" -> maskValue(l - r, w)
        "*" -> maskValue(l * r, w * 2)
        "/" -> if (r == 0L) 0L else maskValue(l / r, w)
        "%" -> if (r == 0L) 0L else maskValue(l % r, w)
        "&" -> maskValue(l and r, w)
        "|" -> maskValue(l or r, w)
        "^" -> maskValue(l xor r, w)
        "~&" -> maskValue((l and r).inv(), w)
        "~|" -> maskValue((l or r).inv(), w)
        "^~", "~^" -> maskValue((l xor r).inv(), w)
        "&&" -> if (l != 0L && r != 0L) 1L else 0L
        "||" -> if (l != 0L || r != 0L) 1L else 0L
        "==", "===", "==?" -> if (l == r) 1L else 0L
        "!=", "!==", "!=?" -> if (l != r) 1L else 0L
        "<" -> if (l < r) 1L else 0L
        "<=" -> if (l <= r) 1L else 0L
        ">" -> if (l > r) 1L else 0L
        ">=" -> if (l >= r) 1L else 0L
        "<<", "<<<" -> maskValue(l shl r.toInt().coerceIn(0, 62), w)
        ">>" -> maskValue(l ushr r.toInt().coerceIn(0, 62), w)
        ">>>" -> maskValue(l shr r.toInt().coerceIn(0, 62), w)
        else -> 0L
      }
    }
    is VerilogExpr.Ternary -> {
      if (evalGoldenExpr(expr.cond, env, widths) != 0L) evalGoldenExpr(expr.thenExpr, env, widths)
      else evalGoldenExpr(expr.elseExpr, env, widths)
    }
  }

  private fun inferExprWidth(expr: VerilogExpr, widths: Map<String, Int>): Int = when (expr) {
    is VerilogExpr.Const -> expr.width
    is VerilogExpr.Signal -> widths[expr.name] ?: 1
    is VerilogExpr.Slice -> abs(expr.msb - expr.lsb) + 1
    is VerilogExpr.Index -> expr.width.coerceAtLeast(1)
    is VerilogExpr.FuncCall -> 32
    is VerilogExpr.Concat -> expr.parts.sumOf { inferExprWidth(it, widths) }.coerceAtLeast(1)
    is VerilogExpr.Replication -> (expr.count * inferExprWidth(expr.inner, widths)).coerceAtLeast(1)
    is VerilogExpr.Unary -> if (expr.op in setOf("!", "&", "|", "^", "~&", "~|", "~^", "^~")) 1 else inferExprWidth(expr.inner, widths)
    is VerilogExpr.Binary -> if (expr.op in setOf("==", "!=", "===", "!==", "==?", "!=?", "<", "<=", ">", ">=", "&&", "||")) 1
    else maxOf(inferExprWidth(expr.left, widths), inferExprWidth(expr.right, widths))
    is VerilogExpr.Ternary -> maxOf(inferExprWidth(expr.thenExpr, widths), inferExprWidth(expr.elseExpr, widths))
  }

  // ==================== REVISED NETLIST EVALUATOR ====================

  private fun evaluateRevisedCombStep(
    nodes: List<GateNode>,
    inputValues: Map<String, Long>,
    seqState: Map<String, Long>,
    portIndirection: Map<String, String>,
    dftConstrained: Boolean,
  ): Map<String, Long> {
    val netEnv = linkedMapOf<String, Long>()
    inputValues.forEach { (k, v) -> netEnv[k] = v }
    if (dftConstrained) {
      netEnv["scan_en"] = 0L
    }
    seqState.forEach { (k, v) -> netEnv[k] = v }

    val combNodes = nodes.filter { !it.gateType.isPort && !it.gateType.isSequential }
    val passes = combNodes.size.coerceIn(2, 128)
    repeat(passes) {
      var changed = false
      combNodes.forEach { node ->
        val w = node.bitWidth
        val a = resolveNetToken(node.inputs.getOrNull(0), netEnv)
        val b = resolveNetToken(node.inputs.getOrNull(1), netEnv)
        val c = resolveNetToken(node.inputs.getOrNull(2), netEnv)
        val rawOut = when (node.gateType) {
          GateType.CONST -> resolveConstNodeValue(node)
          GateType.INV -> a.inv()
          GateType.BUF -> a
          GateType.AND2 -> a and b
          GateType.OR2 -> a or b
          GateType.NAND2 -> (a and b).inv()
          GateType.NOR2 -> (a or b).inv()
          GateType.XOR2 -> a xor b
          GateType.XNOR2 -> (a xor b).inv()
          GateType.MUX2 -> if ((a and 1L) != 0L) b else c
          GateType.ADD_CELL -> a + b
          GateType.SUB_CELL -> a - b
          GateType.MUL_CELL -> a * b
          GateType.CMP_CELL -> if (a == b) 1L else 0L
          else -> a
        }
        val masked = maskValue(rawOut, w)
        if (netEnv[node.outputNet] != masked) {
          netEnv[node.outputNet] = masked
          changed = true
        }
      }
      if (!changed) return@repeat
    }

    portIndirection.forEach { (outPort, srcNet) ->
      if (!netEnv.containsKey(outPort)) {
        netEnv[outPort] = resolveNetToken(srcNet, netEnv)
      }
    }
    return netEnv
  }

  private fun resolveConstNodeValue(node: GateNode): Long {
    val match = Regex("""^_const_(\d+)b(-?\d+)$""").find(node.outputNet)
    if (match != null) {
      val v = match.groupValues[2].toLongOrNull() ?: 0L
      return maskValue(v, node.bitWidth)
    }
    val upper = node.cellName.uppercase(Locale.ROOT)
    return if ("TIE1" in upper || "LOGIC1" in upper || "VCC" in upper) 1L else 0L
  }

  private fun resolveNetToken(token: String?, netEnv: Map<String, Long>): Long {
    if (token.isNullOrBlank()) return 0L
    val t = token.trim()
    netEnv[t]?.let { return it }
    Regex("""^_const_(\d+)b(-?\d+)$""").find(t)?.let { m ->
      val w = m.groupValues[1].toIntOrNull() ?: 32
      val v = m.groupValues[2].toLongOrNull() ?: 0L
      return maskValue(v, w)
    }
    t.toLongOrNull()?.let { return it }
    val sized = Regex("""^(\d+)'([bBoOdDhH])([0-9a-fA-F_]+)$""").find(t)
    if (sized != null) {
      val w = sized.groupValues[1].toIntOrNull() ?: 32
      val radix = when (sized.groupValues[2].lowercase(Locale.ROOT)) {
        "b" -> 2
        "o" -> 8
        "h" -> 16
        else -> 10
      }
      val parsed = sized.groupValues[3].replace("_", "").toLongOrNull(radix) ?: 0L
      return maskValue(parsed, w)
    }
    return 0L
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
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
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

  private fun maskValue(value: Long, width: Int): Long {
    val safeW = width.coerceIn(1, 62)
    val mask = if (safeW >= 62) Long.MAX_VALUE else (1L shl safeW) - 1L
    return value and mask
  }

  private fun formatHex(value: Long, width: Int): String {
    val digits = ((width + 3) / 4).coerceAtLeast(1)
    return "0x" + maskValue(value, width).toString(16).uppercase(Locale.ROOT).padStart(digits, '0')
  }

  private fun formatBin(value: Long, width: Int): String {
    val safeW = width.coerceIn(1, 16)
    return maskValue(value, width).toString(2).padStart(safeW, '0')
  }
}
