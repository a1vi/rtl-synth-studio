package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary

enum class PortDirection {
  INPUT,
  OUTPUT,
  INOUT,
}

data class RtlPort(
  val name: String,
  val direction: PortDirection,
  val width: Int,
  val isReg: Boolean,
  val msb: Int = (width - 1).coerceAtLeast(0),
  val lsb: Int = 0,
  val isSigned: Boolean = false,
  val line: Int = 1,
)

enum class GateType(
  val shortLabel: String,
  val isSequential: Boolean = false,
  val isPort: Boolean = false,
) {
  INPUT_PORT("IN", isPort = true),
  OUTPUT_PORT("OUT", isPort = true),
  CONST("CONST"),
  INV("INV"),
  BUF("BUF"),
  AND2("AND2"),
  OR2("OR2"),
  NAND2("NAND2"),
  NOR2("NOR2"),
  XOR2("XOR2"),
  XNOR2("XNOR2"),
  MUX2("MUX2"),
  ADD_CELL("ADD"),
  SUB_CELL("SUB"),
  CMP_CELL("CMP"),
  MUL_CELL("MUL"),
  LUT4("LUT4"),
  LUT6("LUT6"),
  CARRY4("CARRY4"),
  DFF("DFF", isSequential = true),
  DFFR("DFFR", isSequential = true),
  SDFF("SDFF", isSequential = true),
  SDFFR("SDFFR", isSequential = true),
  LOCKUP_LATCH("DLAT", isSequential = true),
}

enum class DftScanStyle(
  val id: String,
  val displayName: String,
  val shortName: String,
  val areaOverheadRatio: Double,
  val delayOverheadNs: Double,
) {
  MUXED_D("MUXED_D", "Muxed-D Scan", "Muxed-D", 0.14, 0.035),
  CLOCKED_SCAN("CLOCKED_SCAN", "Clocked Scan", "Clocked", 0.18, 0.020),
  LSSD("LSSD", "LSSD Dual-Latch", "LSSD", 0.24, 0.025);

  companion object {
    fun fromId(id: String): DftScanStyle = entries.firstOrNull { it.id == id } ?: MUXED_D
  }
}

data class DftConfig(
  val scanInsertionEnabled: Boolean = false,
  val scanStyle: DftScanStyle = DftScanStyle.MUXED_D,
  val targetChainCount: Int = 1,
  val maxChainLength: Int = 16,
  val insertLockupLatches: Boolean = true,
  val insertJtagBoundaryTap: Boolean = false,
  val insertMbistCollar: Boolean = false,
  val atpgMaxPatterns: Int = 32,
)

enum class DftRuleSeverity {
  PASS,
  WARNING,
  VIOLATION,
}

data class DftRuleCheck(
  val ruleId: String,
  val title: String,
  val severity: DftRuleSeverity,
  val details: String,
  val affectedCount: Int = 0,
)

data class DftScanCell(
  val orderIndex: Int,
  val nodeId: String,
  val instanceName: String,
  val cellName: String,
  val outputNet: String,
  val clockDomain: String,
  val bitWidth: Int,
  val isLockupLatch: Boolean = false,
)

data class DftScanChain(
  val chainIndex: Int,
  val scanInPort: String,
  val scanOutPort: String,
  val scanEnablePort: String,
  val clockDomains: List<String>,
  val totalFlipFlopBits: Int,
  val hasLockupLatch: Boolean,
  val cells: List<DftScanCell>,
)

enum class FaultModelCategory(val displayName: String) {
  STUCK_AT("Stuck-At"),
  TRANSITION_DELAY("Transition Delay"),
}

enum class FaultType(
  val shortLabel: String,
  val fullName: String,
  val category: FaultModelCategory,
) {
  SA0("SA0", "Stuck-At 0", FaultModelCategory.STUCK_AT),
  SA1("SA1", "Stuck-At 1", FaultModelCategory.STUCK_AT),
  STR("STR", "Slow-to-Rise", FaultModelCategory.TRANSITION_DELAY),
  STF("STF", "Slow-to-Fall", FaultModelCategory.TRANSITION_DELAY),
}

enum class FaultStatus { DETECTED, UNDETECTED, UNTESTABLE }

data class AtpgFaultSite(
  val id: String,
  val nodeId: String,
  val nodeInstance: String,
  val netName: String,
  val faultType: FaultType,
  val status: FaultStatus,
  val detectedByVectorIndex: Int? = null,
)

data class ScanChainVectorSlice(
  val chainIndex: Int,
  val scanInPort: String,
  val scanOutPort: String,
  val scanInBitstream: String,
  val expectedScanOutBitstream: String,
  val cellNamesInOrder: List<String> = emptyList(),
)

data class AtpgTestVector(
  val index: Int,
  val piBits: Map<String, Int>,
  val scanInBits: Map<String, Int>,
  val expectedPoBits: Map<String, Int>,
  val newlyDetectedFaults: Int,
  val chainVectors: List<ScanChainVectorSlice> = emptyList(),
  val expectedScanOutBits: Map<String, Int> = emptyMap(),
  val detectedFaultIds: List<String> = emptyList(),
  val targetedAlgorithm: String = "D-SENSITIZE+LFSR",
)

data class DftReport(
  val config: DftConfig,
  val scanInserted: Boolean,
  val totalSeqElements: Int,
  val scanReplacedElements: Int,
  val scanChains: List<DftScanChain>,
  val lockupLatchCount: Int,
  val jtagTapInserted: Boolean,
  val mbistCollarInserted: Boolean,
  val scanAreaOverheadUm2: Double,
  val scanAreaOverheadPct: Double,
  val scanShiftFreqMhz: Double,
  val totalFaults: Int,
  val detectedFaults: Int,
  val untestableFaults: Int,
  val stuckAtCoveragePct: Double,
  val testCoveragePct: Double,
  val dftRuleChecks: List<DftRuleCheck>,
  val atpgVectors: List<AtpgTestVector>,
  val sampleFaults: List<AtpgFaultSite>,
  val stilPatternText: String,
  val scanDefText: String,
  val dftSummaryReport: String,
  val transitionFaults: List<AtpgFaultSite> = emptyList(),
  val transitionCoveragePct: Double = 0.0,
  val transitionTestCoveragePct: Double = 0.0,
) {
  val undetectedFaults: Int
    get() = (totalFaults - detectedFaults - untestableFaults).coerceAtLeast(0)
}

data class GateNode(
  val id: String,
  val cellName: String,
  val gateType: GateType,
  val instanceName: String,
  val inputs: List<String>,
  val outputNet: String,
  val bitWidth: Int,
  val delayNs: Double,
  val areaUm2: Double,
  val powerUw: Double,
  val level: Int = 0,
  val arrivalNs: Double = 0.0,
  val requiredNs: Double = 0.0,
  val slackNs: Double = 0.0,
  val isCriticalPath: Boolean = false,
  val isFalsePath: Boolean = false,
  val clockDomain: String = "clk",
  val sourceLine: Int = 0,
  val hierarchyPath: String = "",
)

data class NetEdge(
  val netName: String,
  val fromNodeId: String,
  val toNodeId: String,
  val width: Int,
  val isCritical: Boolean,
)

data class TimingPathStep(
  val nodeId: String,
  val instanceName: String,
  val cellName: String,
  val netName: String,
  val incrDelayNs: Double,
  val arrivalNs: Double,
  val sourceLine: Int = 0,
)

data class SynthesisReport(
  val moduleName: String,
  val ports: List<RtlPort>,
  val targetLibrary: TargetLibrary,
  val optimizationGoal: OptimizationGoal,
  val clockPeriodNs: Double,
  val nodes: List<GateNode>,
  val edges: List<NetEdge>,
  val criticalPath: List<TimingPathStep>,
  val criticalPathDelayNs: Double,
  val wnsNs: Double,
  val tnsNs: Double,
  val fmaxMhz: Double,
  val totalAreaUm2: Double,
  val cellCount: Int,
  val combCellCount: Int,
  val seqCellCount: Int,
  val dynamicPowerMw: Double,
  val leakagePowerMw: Double,
  val totalPowerMw: Double,
  val gateHistogram: Map<String, Int>,
  val netlistVerilog: String,
  val synthesisLog: List<String>,
  val warnings: List<String>,
  val sdcSummary: String = "",
  val appliedSdcConstraints: Int = 0,
  val clocksDetected: List<SdcClock> = emptyList(),
  val inputDelayNs: Double = 0.0,
  val outputDelayNs: Double = 0.0,
  val maxOutputLoadPf: Double = 0.0,
  val clockUncertaintyNs: Double = 0.0,
  val falsePathCount: Int = 0,
  val synthesisDurationMs: Long = 0L,
  val preOptCellCount: Int = 0,
  val postOptCellCount: Int = 0,
  val engineProfile: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
  val dftReport: DftReport? = null,
  val switchingPowerMw: Double = 0.0,
  val internalPowerMw: Double = 0.0,
  val powerAnalysis: PowerAnalysisReport? = null,
  val rtlDrcReport: RtlDrcReport? = null,
) {
  fun criticalPathSummaryText(): String {
    if (criticalPath.isEmpty()) return "No timing path (${"%.2f".format(criticalPathDelayNs)} ns)"
    val chain = criticalPath.take(5).joinToString(" -> ") { it.cellName }
    val suffix = if (criticalPath.size > 5) " -> ..." else ""
    return "$chain$suffix (${"%.2f".format(criticalPathDelayNs)} ns)"
  }

  val optimizationReductionPercent: Double
    get() = if (preOptCellCount > 0 && preOptCellCount >= postOptCellCount) {
      ((preOptCellCount - postOptCellCount).toDouble() / preOptCellCount) * 100.0
    } else {
      0.0
    }
}

enum class PowerActivitySource(val label: String) {
  VECTORLESS_DEFAULT("Vectorless Default (α=0.20)"),
  SIMULATION_VCD("Simulation Activity (VCD)"),
}

data class SignalActivityMetric(
  val signalName: String,
  val bitWidth: Int,
  val toggleCount: Int,
  val possibleTransitions: Int,
  val activityFactor: Double,
  val isClock: Boolean,
  val isReset: Boolean,
  val isSequentialReg: Boolean,
  val netCapacitanceFf: Double,
  val switchingPowerUw: Double,
)

data class NodePowerBreakdown(
  val nodeId: String,
  val instanceName: String,
  val cellName: String,
  val gateType: GateType,
  val outputNet: String,
  val activityFactor: Double,
  val switchingPowerUw: Double,
  val internalPowerUw: Double,
  val leakagePowerUw: Double,
) {
  val totalPowerUw: Double
    get() = switchingPowerUw + internalPowerUw + leakagePowerUw
}

enum class OperatingCorner(val label: String, val shortName: String, val leakageMult: Double, val speedMult: Double) {
  SLOW("Slow / SS (125°C, Low VDD)", "SS", 0.78, 0.85),
  TYPICAL("Typical / TT (25°C, Nom VDD)", "TT", 1.0, 1.0),
  FAST("Fast / FF (-40°C, High VDD)", "FF", 1.65, 1.15),
}

data class PowerEstimationConfig(
  val clockFreqOverrideMhz: Double? = null,
  val vddOverrideVolts: Double? = null,
  val defaultActivityFactor: Double = 0.20,
  val signalProbability: Double = 0.50,
  val temperatureC: Double = 25.0,
  val operatingCorner: OperatingCorner = OperatingCorner.TYPICAL,
)

data class PowerAnalysisReport(
  val moduleName: String,
  val library: TargetLibrary,
  val activitySource: PowerActivitySource,
  val simulatedCycles: Int,
  val clockFreqMhz: Double,
  val vddVolts: Double,
  val averageActivityFactor: Double,
  val totalToggles: Int,
  val peakCycleIndex: Int,
  val peakCycleToggles: Int,
  val switchingPowerMw: Double,
  val internalPowerMw: Double,
  val leakagePowerMw: Double,
  val clockTreePowerMw: Double,
  val combinationalPowerMw: Double,
  val sequentialPowerMw: Double,
  val totalPowerMw: Double,
  val energyPerCyclePj: Double,
  val signalActivities: List<SignalActivityMetric>,
  val topPowerNodes: List<NodePowerBreakdown>,
  val cycleToggleProfile: List<Int>,
  val bufferPowerMw: Double = 0.0,
  val averageCyclePowerMw: Double = totalPowerMw,
  val peakCyclePowerMw: Double? = null,
  val temperatureC: Double = 25.0,
  val operatingCorner: OperatingCorner = OperatingCorner.TYPICAL,
  val allPowerNodes: List<NodePowerBreakdown> = topPowerNodes,
) {
  val dynamicPowerMw: Double get() = switchingPowerMw + internalPowerMw
}

sealed interface SynthesisResult {
  data class Success(val report: SynthesisReport) : SynthesisResult
  data class Error(
    val line: Int,
    val message: String,
    val log: List<String>,
  ) : SynthesisResult
}
