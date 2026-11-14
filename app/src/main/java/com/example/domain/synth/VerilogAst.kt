package com.example.domain.synth

enum class LogicSignalKind(val label: String) {
  WIRE("wire"),
  REG("reg"),
  LOGIC("logic"),
  INTEGER("integer"),
}

data class VerilogParameter(
  val name: String,
  val value: Int,
  val isLocalparam: Boolean,
  val line: Int,
)

data class VerilogLogicSignal(
  val name: String,
  val kind: LogicSignalKind,
  val width: Int,
  val msb: Int,
  val lsb: Int,
  val isSigned: Boolean,
  val isPort: Boolean,
  val line: Int,
)

data class VerilogAssignStmt(
  val target: String,
  val targetMsb: Int? = null,
  val targetLsb: Int? = null,
  val expr: VerilogExpr,
  val line: Int,
  val targetMsbExpr: VerilogExpr? = null,
  val targetLsbExpr: VerilogExpr? = null,
)

data class VerilogAlwaysBlock(
  val isSequential: Boolean,
  val clockName: String?,
  val resetName: String?,
  val resetActiveLow: Boolean,
  val sensitivitySignals: List<String>,
  val statements: List<VerilogStmt>,
  val line: Int,
)

data class VerilogModuleInstance(
  val moduleType: String,
  val instanceName: String,
  val portConnections: Map<String, VerilogExpr>,
  val positionalConnections: List<VerilogExpr>,
  val line: Int,
  val paramOverrides: Map<String, Int> = emptyMap(),
)

sealed interface VerilogStmt {
  data class Assign(
    val target: String,
    val expr: VerilogExpr,
    val nonBlocking: Boolean,
    val line: Int,
    val targetMsb: Int? = null,
    val targetLsb: Int? = null,
    val targetMsbExpr: VerilogExpr? = null,
    val targetLsbExpr: VerilogExpr? = null,
  ) : VerilogStmt

  data class IfStmt(
    val cond: VerilogExpr,
    val thenBranch: List<VerilogStmt>,
    val elseBranch: List<VerilogStmt>,
    val line: Int,
  ) : VerilogStmt

  data class CaseStmt(
    val selector: VerilogExpr,
    val branches: List<Pair<List<VerilogExpr>, List<VerilogStmt>>>,
    val defaultBranch: List<VerilogStmt>,
    val line: Int,
  ) : VerilogStmt
}

sealed interface VerilogExpr {
  data class Const(val value: Int, val width: Int = 1) : VerilogExpr
  data class Signal(val name: String) : VerilogExpr
  data class Slice(
    val name: String,
    val msb: Int,
    val lsb: Int,
    val msbExpr: VerilogExpr? = null,
    val lsbExpr: VerilogExpr? = null,
  ) : VerilogExpr
  data class Index(
    val base: VerilogExpr,
    val indexExpr: VerilogExpr,
    val width: Int = 1,
  ) : VerilogExpr
  data class Concat(val parts: List<VerilogExpr>) : VerilogExpr
  data class Replication(val count: Int, val inner: VerilogExpr) : VerilogExpr
  data class Unary(val op: String, val inner: VerilogExpr) : VerilogExpr
  data class Binary(val op: String, val left: VerilogExpr, val right: VerilogExpr) : VerilogExpr
  data class Ternary(val cond: VerilogExpr, val thenExpr: VerilogExpr, val elseExpr: VerilogExpr) : VerilogExpr
  data class FuncCall(val name: String, val args: List<VerilogExpr>) : VerilogExpr
}

data class ParsedVerilogModule(
  val name: String,
  val line: Int,
  val parameters: List<VerilogParameter>,
  val ports: List<RtlPort>,
  val logicSignals: List<VerilogLogicSignal>,
  val assignments: List<VerilogAssignStmt>,
  val alwaysBlocks: List<VerilogAlwaysBlock>,
  val instances: List<VerilogModuleInstance>,
) {
  val inputPorts: List<RtlPort>
    get() = ports.filter { it.direction == PortDirection.INPUT }

  val outputPorts: List<RtlPort>
    get() = ports.filter { it.direction == PortDirection.OUTPUT }

  val inoutPorts: List<RtlPort>
    get() = ports.filter { it.direction == PortDirection.INOUT }

  val internalSignals: List<VerilogLogicSignal>
    get() = logicSignals.filter { !it.isPort }

  fun signalWidth(name: String): Int =
    logicSignals.firstOrNull { it.name == name }?.width
      ?: ports.firstOrNull { it.name == name }?.width
      ?: 1
}

data class ParsedVerilogDesign(
  val modules: List<ParsedVerilogModule>,
  val topModule: ParsedVerilogModule,
  val diagnostics: List<String>,
)

sealed interface VerilogParseResult {
  data class Success(val design: ParsedVerilogDesign) : VerilogParseResult
  data class Error(
    val line: Int,
    val column: Int,
    val message: String,
    val partialModules: List<ParsedVerilogModule> = emptyList(),
  ) : VerilogParseResult
}
