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
  val clockUncertaintyNs: Double = 0.0,
  val falsePathCount: Int = 0,
  val synthesisDurationMs: Long = 0L,
  val preOptCellCount: Int = 0,
  val postOptCellCount: Int = 0,
  val engineProfile: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
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

sealed interface SynthesisResult {
  data class Success(val report: SynthesisReport) : SynthesisResult
  data class Error(
    val line: Int,
    val message: String,
    val log: List<String>,
  ) : SynthesisResult
}
