package com.example.domain.synth

import kotlin.math.abs
import kotlin.math.max

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
  data class Concat(val parts: List<VerilogExpr>) : VerilogExpr
  data class Replication(val count: Int, val inner: VerilogExpr) : VerilogExpr
  data class Unary(val op: String, val inner: VerilogExpr) : VerilogExpr
  data class Binary(val op: String, val left: VerilogExpr, val right: VerilogExpr) : VerilogExpr
  data class Ternary(val cond: VerilogExpr, val thenExpr: VerilogExpr, val elseExpr: VerilogExpr) : VerilogExpr
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

/**
 * Standalone Verilog HDL Lexer & Recursive-Descent Parser.
 *
 * Identifies:
 * - Single or multiple `module ... endmodule` declarations and selects the top-level module
 * - ANSI-style and Verilog-1995 `input`, `output`, and `inout` ports with vector `[msb:lsb]` ranges & `signed` flags
 * - Logic signals (`wire`, `reg`, `logic`, `integer`, `parameter`, `localparam`)
 * - Continuous `assign` statements, combinational/sequential `always` blocks, and submodule instantiations
 */
object VerilogHdlParser {

  private enum class TokenType {
    IDENT, NUMBER, STRING, SYMBOL, EOF
  }

  private data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
  )

  private class ParseException(
    val line: Int,
    val column: Int,
    message: String,
  ) : Exception(message)

  private val verilogKeywords = setOf(
    "module", "endmodule", "input", "output", "inout", "wire", "reg", "logic",
    "integer", "signed", "unsigned", "parameter", "localparam", "assign",
    "always", "always_comb", "always_ff", "always_latch", "initial",
    "begin", "end", "if", "else", "case", "casez", "casex", "default",
    "endcase", "posedge", "negedge", "or", "for", "generate", "endgenerate",
    "genvar", "function", "endfunction", "task", "endtask",
  )

  fun parse(source: String, preferredTopModule: String? = null): VerilogParseResult {
    if (source.isBlank()) {
      return VerilogParseResult.Error(1, 1, "Verilog source is empty. Declare at least one module.")
    }

    val preprocessed = VerilogPreprocessor.preprocess(source)
    val tokens = try {
      tokenize(preprocessed.processedSource)
    } catch (e: ParseException) {
      return VerilogParseResult.Error(e.line, e.column, e.message ?: "Lexical error")
    }

    val parser = Parser(tokens)
    val modules = mutableListOf<ParsedVerilogModule>()

    try {
      while (parser.hasMoreModules()) {
        modules += parser.parseModule()
      }
      if (modules.isEmpty()) {
        return VerilogParseResult.Error(
          line = 1,
          column = 1,
          message = "No 'module' declaration found. Verilog code must contain a 'module ... endmodule' block.",
        )
      }
    } catch (e: ParseException) {
      return VerilogParseResult.Error(
        line = e.line,
        column = e.column,
        message = e.message ?: "Syntax error",
        partialModules = modules,
      )
    }

    val instantiatedTypes = modules.flatMap { m -> m.instances.map { it.moduleType } }.toSet()
    val topModule = when {
      preferredTopModule != null -> modules.firstOrNull { it.name == preferredTopModule }
        ?: modules.firstOrNull { it.name !in instantiatedTypes }
        ?: modules.first()
      else -> modules.firstOrNull { it.name !in instantiatedTypes } ?: modules.first()
    }

    val diagnostics = (preprocessed.diagnostics + analyzeDiagnostics(modules)).distinct()
    return VerilogParseResult.Success(
      ParsedVerilogDesign(
        modules = modules,
        topModule = topModule,
        diagnostics = diagnostics,
      )
    )
  }

  private fun analyzeDiagnostics(modules: List<ParsedVerilogModule>): List<String> {
    val warnings = mutableListOf<String>()
    modules.forEach { mod ->
      if (mod.ports.none { it.direction == PortDirection.OUTPUT }) {
        warnings += "Module '${mod.name}' declares no output ports."
      }
      val drivenTargets = mutableSetOf<String>()
      mod.assignments.forEach { drivenTargets += it.target }
      mod.alwaysBlocks.forEach { collectAssignedNames(it.statements, drivenTargets) }
      mod.instances.forEach { inst ->
        inst.portConnections.values.forEach { expr ->
          if (expr is VerilogExpr.Signal) drivenTargets += expr.name
        }
      }
      mod.outputPorts.forEach { outPort ->
        if (outPort.name !in drivenTargets) {
          warnings += "Output port '${outPort.name}' in '${mod.name}' has no continuous or procedural driver."
        }
      }
    }
    return warnings
  }

  private fun collectAssignedNames(stmts: List<VerilogStmt>, out: MutableSet<String>) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> out += stmt.target
        is VerilogStmt.IfStmt -> {
          collectAssignedNames(stmt.thenBranch, out)
          collectAssignedNames(stmt.elseBranch, out)
        }
        is VerilogStmt.CaseStmt -> {
          stmt.branches.forEach { (_, body) -> collectAssignedNames(body, out) }
          collectAssignedNames(stmt.defaultBranch, out)
        }
      }
    }
  }

  private fun tokenize(source: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    var line = 1
    var col = 1
    val n = source.length
    val twoCharOps = setOf("<=", ">=", "==", "!=", "&&", "||", "<<", ">>", "~&", "~|", "~^", "^~", "++", "--")

    while (i < n) {
      val c = source[i]
      when {
        c == '\n' -> {
          line++
          col = 1
          i++
        }
        c.isWhitespace() -> {
          i++
          col++
        }
        c == '/' && i + 1 < n && source[i + 1] == '/' -> {
          while (i < n && source[i] != '\n') {
            i++
          }
        }
        c == '/' && i + 1 < n && source[i + 1] == '*' -> {
          val startLine = line
          val startCol = col
          i += 2
          col += 2
          var closed = false
          while (i + 1 < n) {
            if (source[i] == '*' && source[i + 1] == '/') {
              i += 2
              col += 2
              closed = true
              break
            }
            if (source[i] == '\n') {
              line++
              col = 1
            } else {
              col++
            }
            i++
          }
          if (!closed) {
            throw ParseException(startLine, startCol, "Unterminated block comment '/* ... */'")
          }
        }
        c == '`' -> {
          while (i < n && source[i] != '\n') i++
        }
        c == '"' -> {
          val startLine = line
          val startCol = col
          val start = i++
          col++
          while (i < n && source[i] != '"') {
            if (source[i] == '\n') {
              line++
              col = 1
            } else {
              col++
            }
            i++
          }
          if (i >= n) {
            throw ParseException(startLine, startCol, "Unterminated string literal")
          }
          i++
          col++
          tokens += Token(TokenType.STRING, source.substring(start, i), startLine, startCol)
        }
        c.isLetter() || c == '_' -> {
          val start = i
          val startCol = col
          while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) {
            i++
            col++
          }
          tokens += Token(TokenType.IDENT, source.substring(start, i), line, startCol)
        }
        c.isDigit() || (c == '\'' && i + 1 < n && source[i + 1] in listOf('b', 'B', 'h', 'H', 'd', 'D', 'o', 'O')) -> {
          val start = i
          val startCol = col
          while (i < n && (source[i].isLetterOrDigit() || source[i] == '\'' || source[i] == '_')) {
            i++
            col++
          }
          tokens += Token(TokenType.NUMBER, source.substring(start, i), line, startCol)
        }
        i + 1 < n && source.substring(i, i + 2) in twoCharOps -> {
          tokens += Token(TokenType.SYMBOL, source.substring(i, i + 2), line, col)
          i += 2
          col += 2
        }
        else -> {
          tokens += Token(TokenType.SYMBOL, c.toString(), line, col)
          i++
          col++
        }
      }
    }
    tokens += Token(TokenType.EOF, "", line, col)
    return tokens
  }

  private class Parser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(offset: Int = 0): Token =
      tokens[(pos + offset).coerceAtMost(tokens.lastIndex)]

    private fun consume(): Token = tokens[pos++].also {
      if (pos > tokens.lastIndex) pos = tokens.lastIndex
    }

    private fun match(text: String): Boolean {
      if (peek().text == text) {
        pos++
        return true
      }
      return false
    }

    private fun expect(text: String): Token {
      val t = peek()
      if (t.text != text) {
        throw ParseException(t.line, t.column, "Expected '$text' but found '${t.text.ifEmpty { "EOF" }}'")
      }
      pos++
      return t
    }

    private fun expectIdent(): Token {
      val t = peek()
      if (t.type != TokenType.IDENT) {
        throw ParseException(t.line, t.column, "Expected identifier but found '${t.text.ifEmpty { "EOF" }}'")
      }
      pos++
      return t
    }

    fun hasMoreModules(): Boolean {
      while (peek().type != TokenType.EOF && peek().text != "module") {
        pos++
      }
      return peek().text == "module"
    }

    fun parseModule(): ParsedVerilogModule {
      val modTok = expect("module")
      val moduleName = expectIdent().text

      val parameters = mutableListOf<VerilogParameter>()
      val paramMap = mutableMapOf<String, Int>()
      if (match("#")) {
        expect("(")
        while (peek().text != ")" && peek().type != TokenType.EOF) {
          val isLocal = match("localparam")
          if (!isLocal) match("parameter")
          parseParamDecls(parameters, paramMap, isLocal)
          match(",")
        }
        expect(")")
      }

      val ports = mutableListOf<RtlPort>()
      val signals = linkedMapOf<String, VerilogLogicSignal>()

      if (match("(")) {
        parsePortList(ports, signals, paramMap)
        expect(")")
      }
      expect(";")

      val assigns = mutableListOf<VerilogAssignStmt>()
      val alwaysBlocks = mutableListOf<VerilogAlwaysBlock>()
      val instances = mutableListOf<VerilogModuleInstance>()
      val genvars = mutableSetOf<String>()

      while (peek().type != TokenType.EOF && peek().text != "endmodule") {
        parseModuleItem(
          ports = ports,
          signals = signals,
          parameters = parameters,
          paramMap = paramMap,
          genvars = genvars,
          assigns = assigns,
          alwaysBlocks = alwaysBlocks,
          instances = instances,
          endKeywords = setOf("endmodule"),
        )
      }

      if (peek().text != "endmodule") {
        throw ParseException(
          peek().line,
          peek().column,
          "Missing 'endmodule' keyword for module '$moduleName'.",
        )
      }
      expect("endmodule")

      // Ensure every port also appears in logicSignals list
      ports.forEach { port ->
        signals.putIfAbsent(
          port.name,
          VerilogLogicSignal(
            name = port.name,
            kind = if (port.isReg) LogicSignalKind.REG else LogicSignalKind.WIRE,
            width = port.width,
            msb = port.msb,
            lsb = port.lsb,
            isSigned = port.isSigned,
            isPort = true,
            line = port.line,
          ),
        )
      }

      return ParsedVerilogModule(
        name = moduleName,
        line = modTok.line,
        parameters = parameters,
        ports = ports,
        logicSignals = signals.values.toList(),
        assignments = assigns,
        alwaysBlocks = alwaysBlocks,
        instances = instances,
      )
    }

    private fun parseParamDecls(
      params: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      isLocal: Boolean,
    ) {
      while (peek().type != TokenType.EOF && peek().text != ";" && peek().text != ")") {
        if (peek().type == TokenType.IDENT) {
          val tok = consume()
          if (match("=")) {
            val value = evalConstInt(parseExpr(paramMap), paramMap)
            paramMap[tok.text] = value
            params += VerilogParameter(tok.text, value, isLocal, tok.line)
          }
        } else {
          consume()
        }
        if (peek().text == ",") {
          // Stop if next token starts a new parameter keyword inside #( ... )
          if (peek(1).text == "parameter" || peek(1).text == "localparam") break
          consume()
        } else {
          break
        }
      }
    }

    private fun parsePortList(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
    ) {
      var lastDir: PortDirection? = null
      var lastKind = LogicSignalKind.WIRE
      var lastSigned = false
      var lastRange = Triple(1, 0, 0) // width, msb, lsb

      while (peek().text != ")" && peek().type != TokenType.EOF) {
        val dir = when (peek().text) {
          "input" -> { consume(); PortDirection.INPUT }
          "output" -> { consume(); PortDirection.OUTPUT }
          "inout" -> { consume(); PortDirection.INOUT }
          else -> null
        }

        if (dir != null) {
          lastDir = dir
          lastKind = when {
            match("reg") -> LogicSignalKind.REG
            match("logic") -> LogicSignalKind.LOGIC
            else -> {
              match("wire")
              LogicSignalKind.WIRE
            }
          }
          lastSigned = match("signed")
          lastRange = if (peek().text == "[") parseVectorRange(params) else Triple(1, 0, 0)
          val nameTok = expectIdent()
          val port = RtlPort(
            name = nameTok.text,
            direction = dir,
            width = lastRange.first,
            isReg = lastKind == LogicSignalKind.REG || lastKind == LogicSignalKind.LOGIC,
            msb = lastRange.second,
            lsb = lastRange.third,
            isSigned = lastSigned,
            line = nameTok.line,
          )
          ports += port
          signals[port.name] = VerilogLogicSignal(
            name = port.name,
            kind = lastKind,
            width = port.width,
            msb = port.msb,
            lsb = port.lsb,
            isSigned = port.isSigned,
            isPort = true,
            line = port.line,
          )
        } else if (lastDir != null) {
          if (match("reg")) lastKind = LogicSignalKind.REG
          else if (match("logic")) lastKind = LogicSignalKind.LOGIC
          else if (match("wire")) lastKind = LogicSignalKind.WIRE
          if (match("signed")) lastSigned = true
          if (peek().text == "[") lastRange = parseVectorRange(params)

          val nameTok = expectIdent()
          val port = RtlPort(
            name = nameTok.text,
            direction = lastDir,
            width = lastRange.first,
            isReg = lastKind == LogicSignalKind.REG || lastKind == LogicSignalKind.LOGIC,
            msb = lastRange.second,
            lsb = lastRange.third,
            isSigned = lastSigned,
            line = nameTok.line,
          )
          ports += port
          signals[port.name] = VerilogLogicSignal(
            name = port.name,
            kind = lastKind,
            width = port.width,
            msb = port.msb,
            lsb = port.lsb,
            isSigned = port.isSigned,
            isPort = true,
            line = port.line,
          )
        } else {
          // Verilog-1995 header: direction & width defined inside module body
          val nameTok = expectIdent()
          ports += RtlPort(nameTok.text, PortDirection.INPUT, 1, false, 0, 0, false, nameTok.line)
        }
        if (!match(",")) break
      }
    }

    private fun parseBodyPortDecl(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
    ) {
      val dir = when (consume().text) {
        "input" -> PortDirection.INPUT
        "output" -> PortDirection.OUTPUT
        else -> PortDirection.INOUT
      }
      val kind = when {
        match("reg") -> LogicSignalKind.REG
        match("logic") -> LogicSignalKind.LOGIC
        else -> {
          match("wire")
          LogicSignalKind.WIRE
        }
      }
      val isSigned = match("signed")
      val (width, msb, lsb) = if (peek().text == "[") parseVectorRange(params) else Triple(1, 0, 0)
      while (peek().type != TokenType.EOF && peek().text != ";") {
        val nameTok = expectIdent()
        val updated = RtlPort(
          name = nameTok.text,
          direction = dir,
          width = width,
          isReg = kind == LogicSignalKind.REG || kind == LogicSignalKind.LOGIC,
          msb = msb,
          lsb = lsb,
          isSigned = isSigned,
          line = nameTok.line,
        )
        val existingIdx = ports.indexOfFirst { it.name == nameTok.text }
        if (existingIdx >= 0) ports[existingIdx] = updated else ports += updated
        signals[nameTok.text] = VerilogLogicSignal(
          name = nameTok.text,
          kind = kind,
          width = width,
          msb = msb,
          lsb = lsb,
          isSigned = isSigned,
          isPort = true,
          line = nameTok.line,
        )
        if (!match(",")) break
      }
      expect(";")
    }

    private fun parseSignalDecl(
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
    ) {
      val kind = when (consume().text) {
        "reg" -> LogicSignalKind.REG
        "logic" -> LogicSignalKind.LOGIC
        "integer" -> LogicSignalKind.INTEGER
        else -> LogicSignalKind.WIRE
      }
      val isSigned = match("signed")
      val (width, msb, lsb) = when {
        kind == LogicSignalKind.INTEGER -> Triple(32, 31, 0)
        peek().text == "[" -> parseVectorRange(params)
        else -> Triple(1, 0, 0)
      }
      while (peek().type != TokenType.EOF && peek().text != ";") {
        val nameTok = expectIdent()
        // Optional unpacked array dimensions e.g. reg [7:0] mem [0:15];
        if (peek().text == "[") {
          while (peek().text == "[") {
            consume()
            while (peek().text != "]" && peek().type != TokenType.EOF) consume()
            expect("]")
          }
        }
        val existing = signals[nameTok.text]
        signals[nameTok.text] = VerilogLogicSignal(
          name = nameTok.text,
          kind = kind,
          width = if (existing?.isPort == true && width == 1) existing.width else width,
          msb = if (existing?.isPort == true && width == 1) existing.msb else msb,
          lsb = if (existing?.isPort == true && width == 1) existing.lsb else lsb,
          isSigned = isSigned,
          isPort = existing?.isPort ?: false,
          line = nameTok.line,
        )
        if (match("=")) {
          parseExpr(params)
        }
        if (!match(",")) break
      }
      expect(";")
    }

    private fun parseAssignStmt(params: Map<String, Int>): VerilogAssignStmt {
      val line = expect("assign").line
      val target = expectIdent().text
      var tMsb: Int? = null
      var tLsb: Int? = null
      var tMsbExpr: VerilogExpr? = null
      var tLsbExpr: VerilogExpr? = null
      if (match("[")) {
        val mExpr = parseExpr(params)
        val lExpr = if (match(":")) parseExpr(params) else mExpr
        expect("]")
        tMsbExpr = mExpr
        tLsbExpr = lExpr
        tMsb = evalConstInt(mExpr, params)
        tLsb = evalConstInt(lExpr, params)
      }
      expect("=")
      val expr = parseExpr(params)
      expect(";")
      return VerilogAssignStmt(target, tMsb, tLsb, expr, line, tMsbExpr, tLsbExpr)
    }

    private fun parseModuleInstance(params: Map<String, Int>): VerilogModuleInstance {
      val modTypeTok = expectIdent()
      if (match("#")) {
        expect("(")
        var depth = 1
        while (depth > 0 && peek().type != TokenType.EOF) {
          when (consume().text) {
            "(" -> depth++
            ")" -> depth--
          }
        }
      }
      val instTok = expectIdent()
      expect("(")
      val named = linkedMapOf<String, VerilogExpr>()
      val positional = mutableListOf<VerilogExpr>()
      while (peek().text != ")" && peek().type != TokenType.EOF) {
        if (match(".")) {
          val portName = expectIdent().text
          expect("(")
          if (peek().text != ")") {
            named[portName] = parseExpr(params)
          }
          expect(")")
        } else {
          positional += parseExpr(params)
        }
        if (!match(",")) break
      }
      expect(")")
      expect(";")
      return VerilogModuleInstance(
        moduleType = modTypeTok.text,
        instanceName = instTok.text,
        portConnections = named,
        positionalConnections = positional,
        line = modTypeTok.line,
      )
    }

    private fun parseVectorRange(params: Map<String, Int>): Triple<Int, Int, Int> {
      expect("[")
      val msb = evalConstInt(parseExpr(params), params)
      expect(":")
      val lsb = evalConstInt(parseExpr(params), params)
      expect("]")
      val width = max(1, abs(msb - lsb) + 1)
      return Triple(width, msb, lsb)
    }

    private fun evalConstInt(expr: VerilogExpr, params: Map<String, Int>): Int = when (expr) {
      is VerilogExpr.Const -> expr.value
      is VerilogExpr.Signal -> params[expr.name] ?: 0
      is VerilogExpr.Binary -> when (expr.op) {
        "+" -> evalConstInt(expr.left, params) + evalConstInt(expr.right, params)
        "-" -> evalConstInt(expr.left, params) - evalConstInt(expr.right, params)
        "*" -> evalConstInt(expr.left, params) * evalConstInt(expr.right, params)
        "/" -> evalConstInt(expr.right, params).let { d -> if (d == 0) 0 else evalConstInt(expr.left, params) / d }
        "<<" -> evalConstInt(expr.left, params) shl evalConstInt(expr.right, params).coerceIn(0, 30)
        ">>" -> evalConstInt(expr.left, params) shr evalConstInt(expr.right, params).coerceIn(0, 30)
        "==" -> if (evalConstInt(expr.left, params) == evalConstInt(expr.right, params)) 1 else 0
        "!=" -> if (evalConstInt(expr.left, params) != evalConstInt(expr.right, params)) 1 else 0
        "<" -> if (evalConstInt(expr.left, params) < evalConstInt(expr.right, params)) 1 else 0
        "<=" -> if (evalConstInt(expr.left, params) <= evalConstInt(expr.right, params)) 1 else 0
        ">" -> if (evalConstInt(expr.left, params) > evalConstInt(expr.right, params)) 1 else 0
        ">=" -> if (evalConstInt(expr.left, params) >= evalConstInt(expr.right, params)) 1 else 0
        "&&" -> if (evalConstInt(expr.left, params) != 0 && evalConstInt(expr.right, params) != 0) 1 else 0
        "||" -> if (evalConstInt(expr.left, params) != 0 || evalConstInt(expr.right, params) != 0) 1 else 0
        "&" -> evalConstInt(expr.left, params) and evalConstInt(expr.right, params)
        "|" -> evalConstInt(expr.left, params) or evalConstInt(expr.right, params)
        "^" -> evalConstInt(expr.left, params) xor evalConstInt(expr.right, params)
        else -> 0
      }
      is VerilogExpr.Unary -> when (expr.op) {
        "-" -> -evalConstInt(expr.inner, params)
        "!" -> if (evalConstInt(expr.inner, params) == 0) 1 else 0
        "~" -> evalConstInt(expr.inner, params).inv()
        else -> evalConstInt(expr.inner, params)
      }
      is VerilogExpr.Ternary -> {
        if (evalConstInt(expr.cond, params) != 0) evalConstInt(expr.thenExpr, params)
        else evalConstInt(expr.elseExpr, params)
      }
      else -> 0
    }

    private fun parseAlwaysBlock(params: Map<String, Int>): VerilogAlwaysBlock {
      val kwTok = consume()
      var isSeq = kwTok.text == "always_ff"
      var clockName: String? = null
      var resetName: String? = null
      var resetActiveLow = false
      val sensSignals = mutableListOf<String>()

      if (match("@")) {
        if (match("*")) {
          isSeq = false
        } else {
          expect("(")
          if (match("*")) {
            isSeq = false
          } else {
            while (peek().text != ")" && peek().type != TokenType.EOF) {
              when (peek().text) {
                "posedge" -> {
                  consume()
                  val sig = expectIdent().text
                  isSeq = true
                  sensSignals += sig
                  if (clockName == null) clockName = sig else resetName = sig
                }
                "negedge" -> {
                  consume()
                  val sig = expectIdent().text
                  isSeq = true
                  sensSignals += sig
                  if (clockName == null) clockName = sig else {
                    resetName = sig
                    resetActiveLow = true
                  }
                }
                "or", "," -> consume()
                else -> {
                  if (peek().type == TokenType.IDENT) {
                    sensSignals += consume().text
                  } else {
                    consume()
                  }
                }
              }
            }
          }
          expect(")")
        }
      }
      val stmts = parseStmtOrBlock(params)
      return VerilogAlwaysBlock(
        isSequential = isSeq,
        clockName = clockName,
        resetName = resetName,
        resetActiveLow = resetActiveLow,
        sensitivitySignals = sensSignals.distinct(),
        statements = stmts,
        line = kwTok.line,
      )
    }

    private fun parseStmtOrBlock(params: Map<String, Int>): List<VerilogStmt> {
      if (match("begin")) {
        if (match(":")) expectIdent()
        val list = mutableListOf<VerilogStmt>()
        while (peek().text != "end" && peek().type != TokenType.EOF) {
          list += parseSingleStmtExpanded(params)
        }
        expect("end")
        return list
      }
      return parseSingleStmtExpanded(params)
    }

    private fun parseSingleStmtExpanded(params: Map<String, Int>): List<VerilogStmt> {
      val t = peek()
      return when (t.text) {
        "if" -> {
          val line = consume().line
          expect("(")
          val cond = parseExpr(params)
          expect(")")
          val thenB = parseStmtOrBlock(params)
          val elseB = if (match("else")) parseStmtOrBlock(params) else emptyList()
          listOf(VerilogStmt.IfStmt(cond, thenB, elseB, line))
        }
        "case", "casez", "casex" -> {
          val line = consume().line
          expect("(")
          val sel = parseExpr(params)
          expect(")")
          val branches = mutableListOf<Pair<List<VerilogExpr>, List<VerilogStmt>>>()
          var defaultB = emptyList<VerilogStmt>()
          while (peek().text != "endcase" && peek().type != TokenType.EOF) {
            if (match("default")) {
              match(":")
              defaultB = parseStmtOrBlock(params)
            } else {
              val patterns = mutableListOf<VerilogExpr>()
              patterns += parseExpr(params)
              while (match(",")) patterns += parseExpr(params)
              expect(":")
              val body = parseStmtOrBlock(params)
              branches += patterns to body
            }
          }
          expect("endcase")
          listOf(VerilogStmt.CaseStmt(sel, branches, defaultB, line))
        }
        "for" -> parseForLoopStmts(params)
        else -> {
          val line = t.line
          val target = expectIdent().text
          var tMsb: Int? = null
          var tLsb: Int? = null
          var tMsbExpr: VerilogExpr? = null
          var tLsbExpr: VerilogExpr? = null
          if (match("[")) {
            val mExpr = parseExpr(params)
            val lExpr = if (match(":")) parseExpr(params) else mExpr
            expect("]")
            tMsbExpr = mExpr
            tLsbExpr = lExpr
            tMsb = evalConstInt(mExpr, params)
            tLsb = evalConstInt(lExpr, params)
          }
          val nonBlocking = match("<=")
          if (!nonBlocking) expect("=")
          val expr = parseExpr(params)
          expect(";")
          listOf(VerilogStmt.Assign(target, expr, nonBlocking, line, tMsb, tLsb, tMsbExpr, tLsbExpr))
        }
      }
    }

    private data class ForLoopHeader(
      val varName: String,
      val initValue: Int,
      val condExpr: VerilogExpr,
      val stepOp: String, // "+", "-", "=", etc.
      val stepExpr: VerilogExpr?,
    )

    private fun parseForLoopHeader(params: Map<String, Int>): ForLoopHeader {
      expect("for")
      expect("(")
      // Optional inline integer/genvar type
      if (peek().text in setOf("integer", "int", "genvar")) consume()
      val varName = expectIdent().text
      expect("=")
      val initVal = evalConstInt(parseExpr(params), params)
      expect(";")
      val condExpr = parseExpr(params)
      expect(";")
      val stepVar = expectIdent().text
      val (stepOp, stepExpr) = when {
        match("++") -> "+" to VerilogExpr.Const(1, 32)
        match("--") -> "-" to VerilogExpr.Const(1, 32)
        match("+=") -> "+" to parseExpr(params)
        match("-=") -> "-" to parseExpr(params)
        else -> {
          expect("=")
          "=" to parseExpr(params)
        }
      }
      expect(")")
      val resolvedStepExpr = if (stepOp == "=" && stepExpr == null) VerilogExpr.Signal(stepVar) else stepExpr
      return ForLoopHeader(varName, initVal, condExpr, stepOp, resolvedStepExpr)
    }

    private fun advanceForLoopVar(header: ForLoopHeader, curr: Int, params: Map<String, Int>): Int {
      val env = params + (header.varName to curr)
      return when (header.stepOp) {
        "+" -> curr + evalConstInt(header.stepExpr ?: VerilogExpr.Const(1, 32), env)
        "-" -> curr - evalConstInt(header.stepExpr ?: VerilogExpr.Const(1, 32), env)
        "=" -> evalConstInt(header.stepExpr ?: VerilogExpr.Const(curr + 1, 32), env)
        else -> curr + 1
      }
    }

    private fun parseForLoopStmts(params: Map<String, Int>): List<VerilogStmt> {
      val header = parseForLoopHeader(params)
      val templateStmts = parseStmtOrBlock(params)
      val unrolled = mutableListOf<VerilogStmt>()
      var loopVal = header.initValue
      var guard = 0
      while (guard < 128) {
        val env = params + (header.varName to loopVal)
        if (evalConstInt(header.condExpr, env) == 0) break
        templateStmts.forEach { stmt ->
          unrolled += substituteLoopStmt(stmt, header.varName, loopVal, params)
        }
        loopVal = advanceForLoopVar(header, loopVal, params)
        guard++
      }
      return unrolled
    }

    private fun parseModuleItem(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      parameters: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      genvars: MutableSet<String>,
      assigns: MutableList<VerilogAssignStmt>,
      alwaysBlocks: MutableList<VerilogAlwaysBlock>,
      instances: MutableList<VerilogModuleInstance>,
      endKeywords: Set<String>,
    ) {
      if (peek().type == TokenType.EOF || peek().text in endKeywords) return
      when (peek().text) {
        "input", "output", "inout" -> parseBodyPortDecl(ports, signals, paramMap)
        "wire", "reg", "logic", "integer" -> parseSignalDecl(signals, paramMap)
        "genvar" -> parseGenvarDecl(genvars)
        "parameter", "localparam" -> {
          val isLocal = consume().text == "localparam"
          parseParamDecls(parameters, paramMap, isLocal)
          expect(";")
        }
        "assign" -> assigns += parseAssignStmt(paramMap)
        "always", "always_comb", "always_ff", "always_latch" -> alwaysBlocks += parseAlwaysBlock(paramMap)
        "generate" -> {
          consume()
          while (peek().type != TokenType.EOF && peek().text != "endgenerate") {
            parseModuleItem(
              ports, signals, parameters, paramMap, genvars,
              assigns, alwaysBlocks, instances, setOf("endgenerate", "endmodule"),
            )
          }
          match("endgenerate")
        }
        "for" -> parseGenerateFor(
          ports, signals, parameters, paramMap, genvars,
          assigns, alwaysBlocks, instances,
        )
        "if" -> parseGenerateIf(
          ports, signals, parameters, paramMap, genvars,
          assigns, alwaysBlocks, instances,
        )
        "initial" -> skipBlockOrStmt()
        "function", "task" -> skipUntilKeyword(if (peek().text == "function") "endfunction" else "endtask")
        else -> {
          if (peek().type == TokenType.IDENT && peek().text !in verilogKeywords) {
            instances += parseModuleInstance(paramMap)
          } else {
            consume()
          }
        }
      }
    }

    private fun parseGenvarDecl(genvars: MutableSet<String>) {
      expect("genvar")
      while (peek().type != TokenType.EOF && peek().text != ";") {
        if (peek().type == TokenType.IDENT) {
          genvars += consume().text
        } else {
          consume()
        }
        if (!match(",")) break
      }
      expect(";")
    }

    private fun parseGenerateFor(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      parameters: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      genvars: MutableSet<String>,
      assigns: MutableList<VerilogAssignStmt>,
      alwaysBlocks: MutableList<VerilogAlwaysBlock>,
      instances: MutableList<VerilogModuleInstance>,
    ) {
      val header = parseForLoopHeader(paramMap)
      genvars += header.varName
      val bodyStartPos = pos
      val bodyTokensEnd = findBlockOrStmtEndPos()

      var loopVal = header.initValue
      var guard = 0
      while (guard < 128) {
        val env = paramMap.toMutableMap().apply { put(header.varName, loopVal) }
        if (evalConstInt(header.condExpr, env) == 0) break
        pos = bodyStartPos
        parseGenerateBodySlice(
          ports, signals, parameters, env, genvars,
          assigns, alwaysBlocks, instances, loopSuffix = "_${loopVal}",
        )
        loopVal = advanceForLoopVar(header, loopVal, paramMap)
        guard++
      }
      pos = bodyTokensEnd
    }

    private fun parseGenerateIf(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      parameters: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      genvars: MutableSet<String>,
      assigns: MutableList<VerilogAssignStmt>,
      alwaysBlocks: MutableList<VerilogAlwaysBlock>,
      instances: MutableList<VerilogModuleInstance>,
    ) {
      expect("if")
      expect("(")
      val cond = evalConstInt(parseExpr(paramMap), paramMap) != 0
      expect(")")
      val thenStart = pos
      val thenEnd = findBlockOrStmtEndPos()
      pos = thenEnd
      val hasElse = match("else")
      val elseStart = pos
      val elseEnd = if (hasElse) findBlockOrStmtEndPos() else pos

      if (cond) {
        pos = thenStart
        parseGenerateBodySlice(
          ports, signals, parameters, paramMap, genvars,
          assigns, alwaysBlocks, instances, loopSuffix = "",
        )
      } else if (hasElse) {
        pos = elseStart
        parseGenerateBodySlice(
          ports, signals, parameters, paramMap, genvars,
          assigns, alwaysBlocks, instances, loopSuffix = "",
        )
      }
      pos = if (hasElse) elseEnd else thenEnd
    }

    private fun findBlockOrStmtEndPos(): Int {
      val saved = pos
      if (match("begin")) {
        if (match(":")) {
          if (peek().type == TokenType.IDENT) consume()
        }
        var depth = 1
        while (depth > 0 && peek().type != TokenType.EOF) {
          when (consume().text) {
            "begin" -> depth++
            "end" -> depth--
          }
        }
      } else {
        while (peek().text != ";" && peek().type != TokenType.EOF) consume()
        match(";")
      }
      val endPos = pos
      pos = saved
      return endPos
    }

    private fun parseGenerateBodySlice(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      parameters: MutableList<VerilogParameter>,
      localParams: MutableMap<String, Int>,
      genvars: MutableSet<String>,
      assigns: MutableList<VerilogAssignStmt>,
      alwaysBlocks: MutableList<VerilogAlwaysBlock>,
      instances: MutableList<VerilogModuleInstance>,
      loopSuffix: String,
    ) {
      val iterInst = mutableListOf<VerilogModuleInstance>()
      if (match("begin")) {
        if (match(":")) expectIdent()
        while (peek().type != TokenType.EOF && peek().text != "end") {
          parseModuleItem(
            ports, signals, parameters, localParams, genvars,
            assigns, alwaysBlocks, iterInst, setOf("end", "endgenerate", "endmodule"),
          )
        }
        match("end")
      } else {
        parseModuleItem(
          ports, signals, parameters, localParams, genvars,
          assigns, alwaysBlocks, iterInst, setOf("endgenerate", "endmodule"),
        )
      }
      iterInst.forEach { inst ->
        val suffix = if (loopSuffix.isNotEmpty() && !inst.instanceName.endsWith(loopSuffix)) loopSuffix else ""
        instances += inst.copy(instanceName = inst.instanceName + suffix)
      }
    }

    private fun substituteLoopExpr(expr: VerilogExpr, varName: String, value: Int, params: Map<String, Int>): VerilogExpr {
      val env = params + (varName to value)
      return when (expr) {
        is VerilogExpr.Const -> expr
        is VerilogExpr.Signal -> if (expr.name == varName) VerilogExpr.Const(value, 32) else expr
        is VerilogExpr.Slice -> {
          val mExpr = expr.msbExpr?.let { substituteLoopExpr(it, varName, value, params) }
          val lExpr = expr.lsbExpr?.let { substituteLoopExpr(it, varName, value, params) }
          val newMsb = mExpr?.let { evalConstInt(it, env) } ?: expr.msb
          val newLsb = lExpr?.let { evalConstInt(it, env) } ?: expr.lsb
          expr.copy(msb = newMsb, lsb = newLsb, msbExpr = mExpr, lsbExpr = lExpr)
        }
        is VerilogExpr.Concat -> VerilogExpr.Concat(expr.parts.map { substituteLoopExpr(it, varName, value, params) })
        is VerilogExpr.Replication -> VerilogExpr.Replication(expr.count, substituteLoopExpr(expr.inner, varName, value, params))
        is VerilogExpr.Unary -> VerilogExpr.Unary(expr.op, substituteLoopExpr(expr.inner, varName, value, params))
        is VerilogExpr.Binary -> VerilogExpr.Binary(
          expr.op,
          substituteLoopExpr(expr.left, varName, value, params),
          substituteLoopExpr(expr.right, varName, value, params),
        )
        is VerilogExpr.Ternary -> VerilogExpr.Ternary(
          substituteLoopExpr(expr.cond, varName, value, params),
          substituteLoopExpr(expr.thenExpr, varName, value, params),
          substituteLoopExpr(expr.elseExpr, varName, value, params),
        )
      }
    }

    private fun substituteLoopStmt(stmt: VerilogStmt, varName: String, value: Int, params: Map<String, Int>): VerilogStmt {
      val env = params + (varName to value)
      return when (stmt) {
        is VerilogStmt.Assign -> {
          val mExpr = stmt.targetMsbExpr?.let { substituteLoopExpr(it, varName, value, params) }
          val lExpr = stmt.targetLsbExpr?.let { substituteLoopExpr(it, varName, value, params) }
          stmt.copy(
            expr = substituteLoopExpr(stmt.expr, varName, value, params),
            targetMsb = mExpr?.let { evalConstInt(it, env) } ?: stmt.targetMsb,
            targetLsb = lExpr?.let { evalConstInt(it, env) } ?: stmt.targetLsb,
            targetMsbExpr = mExpr,
            targetLsbExpr = lExpr,
          )
        }
        is VerilogStmt.IfStmt -> VerilogStmt.IfStmt(
          cond = substituteLoopExpr(stmt.cond, varName, value, params),
          thenBranch = stmt.thenBranch.map { substituteLoopStmt(it, varName, value, params) },
          elseBranch = stmt.elseBranch.map { substituteLoopStmt(it, varName, value, params) },
          line = stmt.line,
        )
        is VerilogStmt.CaseStmt -> VerilogStmt.CaseStmt(
          selector = substituteLoopExpr(stmt.selector, varName, value, params),
          branches = stmt.branches.map { (pats, body) ->
            pats.map { substituteLoopExpr(it, varName, value, params) } to
              body.map { substituteLoopStmt(it, varName, value, params) }
          },
          defaultBranch = stmt.defaultBranch.map { substituteLoopStmt(it, varName, value, params) },
          line = stmt.line,
        )
      }
    }

    private fun skipBlockOrStmt() {
      consume()
      if (match("begin") || match("generate")) {
        var depth = 1
        while (depth > 0 && peek().type != TokenType.EOF) {
          when (consume().text) {
            "begin", "generate" -> depth++
            "end", "endgenerate" -> depth--
          }
        }
      } else {
        while (peek().text != ";" && peek().type != TokenType.EOF) consume()
        match(";")
      }
    }

    private fun skipUntilKeyword(endKw: String) {
      consume()
      while (peek().text != endKw && peek().type != TokenType.EOF) consume()
      match(endKw)
    }

    private fun parseExpr(params: Map<String, Int>): VerilogExpr = parseTernary(params)

    private fun parseTernary(params: Map<String, Int>): VerilogExpr {
      val cond = parseLogicalOr(params)
      if (match("?")) {
        val thenE = parseExpr(params)
        expect(":")
        val elseE = parseTernary(params)
        return VerilogExpr.Ternary(cond, thenE, elseE)
      }
      return cond
    }

    private fun parseLogicalOr(params: Map<String, Int>): VerilogExpr {
      var left = parseLogicalAnd(params)
      while (peek().text == "||") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseLogicalAnd(params))
      }
      return left
    }

    private fun parseLogicalAnd(params: Map<String, Int>): VerilogExpr {
      var left = parseBitwiseOr(params)
      while (peek().text == "&&") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseBitwiseOr(params))
      }
      return left
    }

    private fun parseBitwiseOr(params: Map<String, Int>): VerilogExpr {
      var left = parseBitwiseXor(params)
      while (peek().text == "|" || peek().text == "~|") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseBitwiseXor(params))
      }
      return left
    }

    private fun parseBitwiseXor(params: Map<String, Int>): VerilogExpr {
      var left = parseBitwiseAnd(params)
      while (peek().text == "^" || peek().text == "^~" || peek().text == "~^") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseBitwiseAnd(params))
      }
      return left
    }

    private fun parseBitwiseAnd(params: Map<String, Int>): VerilogExpr {
      var left = parseEquality(params)
      while (peek().text == "&" || peek().text == "~&") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseEquality(params))
      }
      return left
    }

    private fun parseEquality(params: Map<String, Int>): VerilogExpr {
      var left = parseRelational(params)
      while (peek().text == "==" || peek().text == "!=") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseRelational(params))
      }
      return left
    }

    private fun parseRelational(params: Map<String, Int>): VerilogExpr {
      var left = parseShift(params)
      while (peek().text in listOf("<", "<=", ">", ">=")) {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseShift(params))
      }
      return left
    }

    private fun parseShift(params: Map<String, Int>): VerilogExpr {
      var left = parseAdditive(params)
      while (peek().text == "<<" || peek().text == ">>") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseAdditive(params))
      }
      return left
    }

    private fun parseAdditive(params: Map<String, Int>): VerilogExpr {
      var left = parseMultiplicative(params)
      while (peek().text == "+" || peek().text == "-") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseMultiplicative(params))
      }
      return left
    }

    private fun parseMultiplicative(params: Map<String, Int>): VerilogExpr {
      var left = parseUnary(params)
      while (peek().text == "*") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseUnary(params))
      }
      return left
    }

    private fun parseUnary(params: Map<String, Int>): VerilogExpr {
      if (peek().text in listOf("~", "!", "-", "&", "|", "^")) {
        val op = consume().text
        return VerilogExpr.Unary(op, parseUnary(params))
      }
      return parsePrimary(params)
    }

    private fun parsePrimary(params: Map<String, Int>): VerilogExpr {
      val t = peek()
      return when {
        match("(") -> {
          val inner = parseExpr(params)
          expect(")")
          inner
        }
        match("{") -> {
          val first = parseExpr(params)
          if (match("{")) {
            // Replication operator {N{expr}}
            val count = evalConstInt(first, params).coerceAtLeast(1)
            val inner = parseExpr(params)
            expect("}")
            expect("}")
            VerilogExpr.Replication(count, inner)
          } else {
            val parts = mutableListOf(first)
            while (match(",")) parts += parseExpr(params)
            expect("}")
            VerilogExpr.Concat(parts)
          }
        }
        t.type == TokenType.NUMBER -> {
          consume()
          parseVerilogNumber(t.text)
        }
        t.type == TokenType.IDENT -> {
          val name = consume().text
          if (match("[")) {
            val msbExpr = parseExpr(params)
            val lsbExpr = if (match(":")) parseExpr(params) else msbExpr
            expect("]")
            val msb = evalConstInt(msbExpr, params)
            val lsb = evalConstInt(lsbExpr, params)
            VerilogExpr.Slice(name, msb, lsb, msbExpr, lsbExpr)
          } else if (name in params) {
            VerilogExpr.Const(params[name] ?: 0, 32)
          } else {
            VerilogExpr.Signal(name)
          }
        }
        else -> throw ParseException(
          t.line,
          t.column,
          "Unexpected token '${t.text.ifEmpty { "EOF" }}' in expression",
        )
      }
    }

    private fun parseVerilogNumber(raw: String): VerilogExpr.Const {
      val clean = raw.replace("_", "")
      if (!clean.contains('\'')) {
        val v = clean.toIntOrNull() ?: 0
        return VerilogExpr.Const(v, max(1, 32 - Integer.numberOfLeadingZeros(max(1, v))))
      }
      val parts = clean.split('\'', limit = 2)
      val width = parts[0].toIntOrNull() ?: 1
      val body = parts[1]
      val baseChar = body.firstOrNull()?.lowercaseChar() ?: 'd'
      val digits = body.drop(1).replace('x', '0').replace('z', '0').replace('?', '0')
      val value = when (baseChar) {
        'b' -> digits.toIntOrNull(2) ?: 0
        'h' -> digits.toIntOrNull(16) ?: 0
        'o' -> digits.toIntOrNull(8) ?: 0
        else -> digits.toIntOrNull(10) ?: 0
      }
      return VerilogExpr.Const(value, max(1, width))
    }
  }
}
