package com.example.domain.synth

import kotlin.math.abs
import kotlin.math.max

/**
 * Full-featured Verilog-2001 & SystemVerilog HDL Lexer & Recursive-Descent Parser.
 *
 * Supports:
 * - Multi-module hierarchies, packages, interfaces/modports, enums, typedefs, and imports
 * - Typed/ranged ANSI & Verilog-1995 parameters, `$clog2`, `$signed`, `$unsigned`, `$bits`
 * - Yosys/Synopsys attributes `(* ... *)`, wire inline assigns (`wire a = b & c;`)
 * - Concatenation LHS (`{cout, sum} = a + b;`), `+:`/`-:` indexed part-selects, 2D arrays
 * - Combinational user `function`s, `always_ff`/`always_comb`/`always_latch`, `unique`/`priority case`
 * - Built-in gate primitives (`and`, `or`, `nand`, `nor`, `xor`, `xnor`, `buf`, `not`), `.*`/`.name` ports
 * - `generate`/`genvar` loops & `if`/`case` conditionals
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

  private data class VerilogFuncDef(
    val paramNames: List<String>,
    val bodyExpr: VerilogExpr,
  )

  private val verilogKeywords = setOf(
    "module", "macromodule", "endmodule", "input", "output", "inout", "ref",
    "wire", "reg", "logic", "bit", "byte", "shortint", "int", "longint", "integer",
    "tri", "supply0", "supply1", "var", "signed", "unsigned", "parameter", "localparam",
    "type", "assign", "always", "always_comb", "always_ff", "always_latch", "initial",
    "begin", "end", "if", "else", "case", "casez", "casex", "default", "endcase",
    "unique", "unique0", "priority", "posedge", "negedge", "or", "for", "while",
    "repeat", "forever", "generate", "endgenerate", "genvar", "function", "endfunction",
    "task", "endtask", "package", "endpackage", "interface", "endinterface",
    "import", "export", "typedef", "struct", "union", "enum", "packed", "return",
    "disable", "defparam", "specify", "endspecify", "property", "endproperty",
    "sequence", "endsequence", "assert", "assume", "cover", "bind",
  )

  private val gatePrimitives = setOf(
    "and", "nand", "or", "nor", "xor", "xnor", "buf", "not", "bufif0", "bufif1", "notif0", "notif1",
  )

  private val svTypeKeywords = setOf(
    "wire", "reg", "logic", "bit", "byte", "shortint", "int", "longint", "integer",
    "tri", "supply0", "supply1", "var", "real", "realtime", "time", "string",
    "signed", "unsigned", "const", "automatic", "static",
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
    val modByName = modules.associateBy { it.name }
    modules.forEach { mod ->
      if (mod.ports.none { it.direction == PortDirection.OUTPUT }) {
        warnings += "Module '${mod.name}' declares no output ports."
      }
      val drivenTargets = mutableSetOf<String>()
      mod.assignments.forEach { drivenTargets += it.target }
      mod.alwaysBlocks.forEach { collectAssignedNames(it.statements, drivenTargets) }
      mod.instances.forEach { inst ->
        val subMod = modByName[inst.moduleType]
        inst.portConnections.forEach { (portName, expr) ->
          val dir = subMod?.ports?.firstOrNull { it.name == portName }?.direction
          if (dir == null || dir != PortDirection.INPUT) {
            collectLhsTargetNames(expr, drivenTargets)
          }
        }
        inst.positionalConnections.forEachIndexed { idx, expr ->
          val dir = subMod?.ports?.getOrNull(idx)?.direction
          if (dir != PortDirection.INPUT) {
            collectLhsTargetNames(expr, drivenTargets)
          }
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

  private fun collectLhsTargetNames(expr: VerilogExpr, out: MutableSet<String>) {
    when (expr) {
      is VerilogExpr.Signal -> out += expr.name
      is VerilogExpr.Slice -> out += expr.name
      is VerilogExpr.Index -> collectLhsTargetNames(expr.base, out)
      is VerilogExpr.Concat -> expr.parts.forEach { collectLhsTargetNames(it, out) }
      else -> Unit
    }
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
    val threeCharOps = setOf("===", "!==", "==?", "!=?", "<<<", ">>>")
    val twoCharOps = setOf(
      "<=", ">=", "==", "!=", "&&", "||", "<<", ">>",
      "~&", "~|", "~^", "^~", "++", "--", "**", "+:", "-:",
      "+=", "-=", "&=", "|=", "^=", "::", "->", ".*",
    )

    while (i < n) {
      val c = source[i]
      when {
        c == '\r' -> i++
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
          while (i < n && source[i] != '\n') i++
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
        // Verilog/Yosys attributes: (* keep = "true", ram_style = "block" *) — never match @(*) or (*)
        c == '(' && i + 1 < n && source[i + 1] == '*' &&
          tokens.lastOrNull()?.text != "@" && !(i + 2 < n && source[i + 2] == ')') -> {
          i += 2
          col += 2
          while (i + 1 < n) {
            if (source[i] == '*' && source[i + 1] == ')') {
              i += 2
              col += 2
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
            if (source[i] == '\\' && i + 1 < n) {
              i += 2
              col += 2
              continue
            }
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
        // Escaped Verilog identifiers (\foo[0] )
        c == '\\' -> {
          val startCol = col
          i++
          col++
          val start = i
          while (i < n && !source[i].isWhitespace()) {
            i++
            col++
          }
          val escName = source.substring(start, i).ifEmpty { "_esc" }
          tokens += Token(TokenType.IDENT, escName, line, startCol)
        }
        // Identifiers & system functions ($clog2, $signed, ...)
        c.isLetter() || c == '_' || (c == '$' && i + 1 < n && (source[i + 1].isLetter() || source[i + 1] == '_')) -> {
          val start = i
          val startCol = col
          i++
          col++
          while (i < n && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '$')) {
            i++
            col++
          }
          tokens += Token(TokenType.IDENT, source.substring(start, i), line, startCol)
        }
        // Numbers, SystemVerilog fill literals ('0, '1, 'x, 'z), based numbers ('hFF), and casts (8')
        c.isDigit() || (c == '\'' && i + 1 < n && source[i + 1] in listOf(
          'b', 'B', 'h', 'H', 'd', 'D', 'o', 'O', 's', 'S', '0', '1', 'x', 'X', 'z', 'Z', '(',
        )) -> {
          val start = i
          val startCol = col
          if (c == '\'') {
            i++
            col++
            while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) {
              i++
              col++
            }
          } else {
            while (i < n && (source[i].isDigit() || source[i] == '_')) {
              i++
              col++
            }
            if (i < n && source[i] == '.' && i + 1 < n && source[i + 1].isDigit()) {
              i++
              col++
              while (i < n && (source[i].isDigit() || source[i] == '_')) {
                i++
                col++
              }
            }
            // Optional single spaces between width and radix quote e.g. 32 'hFF
            var peekQuote = i
            while (peekQuote < n && (source[peekQuote] == ' ' || source[peekQuote] == '\t')) peekQuote++
            if (peekQuote < n && source[peekQuote] == '\'') {
              col += (peekQuote - i) + 1
              i = peekQuote + 1
              while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) {
                i++
                col++
              }
            }
          }
          val text = source.substring(start, i).replace(" ", "").replace("\t", "")
          tokens += Token(TokenType.NUMBER, text, line, startCol)
        }
        i + 2 < n && source.substring(i, i + 3) in threeCharOps -> {
          tokens += Token(TokenType.SYMBOL, source.substring(i, i + 3), line, col)
          i += 3
          col += 3
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
    private val globalPackageConsts = linkedMapOf<String, Int>()
    private val globalFunctions = linkedMapOf<String, VerilogFuncDef>()
    private val moduleFunctions = linkedMapOf<String, VerilogFuncDef>()
    private val signalWidths = linkedMapOf<String, Int>()

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
      while (peek().type != TokenType.EOF) {
        when (peek().text) {
          "module", "macromodule" -> return true
          "package" -> parsePackageBlock()
          "interface" -> skipUntilKeyword("endinterface")
          "typedef" -> {
            val dummySignals = linkedMapOf<String, VerilogLogicSignal>()
            parseTypedefOrEnum(globalPackageConsts, dummySignals)
          }
          "import" -> skipUntilSemicolon()
          else -> pos++
        }
      }
      return false
    }

    private fun parsePackageBlock() {
      consume() // package
      if (peek().type == TokenType.IDENT) consume()
      match(";")
      val params = mutableListOf<VerilogParameter>()
      val dummySignals = linkedMapOf<String, VerilogLogicSignal>()
      while (peek().type != TokenType.EOF && peek().text != "endpackage") {
        when (peek().text) {
          "parameter", "localparam" -> {
            val isLocal = consume().text == "localparam"
            parseParamDecls(params, globalPackageConsts, isLocal)
            match(";")
          }
          "typedef", "enum" -> parseTypedefOrEnum(globalPackageConsts, dummySignals)
          "function" -> parseFunctionDecl(globalPackageConsts, globalFunctions)
          else -> consume()
        }
      }
      if (match("endpackage")) {
        if (match(":")) expectIdentIfPresent()
      }
    }

    private fun expectIdentIfPresent() {
      if (peek().type == TokenType.IDENT) consume()
    }

    fun parseModule(): ParsedVerilogModule {
      val modTok = consume() // module / macromodule
      val moduleName = expectIdent().text

      moduleFunctions.clear()
      moduleFunctions.putAll(globalFunctions)
      signalWidths.clear()

      val parameters = mutableListOf<VerilogParameter>()
      val paramMap = globalPackageConsts.toMutableMap()

      // Optional header imports: module foo import pkg::*; #(parameter ...) (...);
      while (match("import")) {
        while (peek().text != ";" && peek().type != TokenType.EOF) consume()
        match(";")
      }

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

      ports.forEach { signalWidths[it.name] = it.width }

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
      if (match(":")) expectIdentIfPresent()

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

    private fun skipOptionalTypeAndRanges(params: Map<String, Int>): Triple<Int, Int, Int>? {
      var customRange: Triple<Int, Int, Int>? = null
      // Consume leading SV type / signed qualifiers
      while (peek().text in svTypeKeywords || peek().text == "type") {
        if (peek().text == "integer" || peek().text == "int") {
          customRange = Triple(32, 31, 0)
        }
        consume()
      }
      // Consume scoped type pkg::type_t or custom type if another IDENT precedes '=' or name
      if (peek().type == TokenType.IDENT && peek(1).text == "::") {
        consume(); consume() // pkg ::
        if (peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT) consume()
      } else if (peek().type == TokenType.IDENT && peek().text !in verilogKeywords &&
        (peek(1).type == TokenType.IDENT || peek(1).text == "[")
      ) {
        // Check carefully: don't eat parameter/port identifier when peek(1) is '=' or ',' or ')' or ';'
        val p1 = peek(1).text
        if (p1 != "=" && p1 != "," && p1 != ")" && p1 != ";" && peek(1).type == TokenType.IDENT) {
          consume()
        }
      }
      if (peek().text == "[") {
        customRange = parseMultiPackedRanges(params)
      }
      return customRange
    }

    private fun parseParamDecls(
      params: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      isLocal: Boolean,
    ) {
      while (peek().type != TokenType.EOF && peek().text != ";" && peek().text != ")") {
        if (peek().text == "parameter" || peek().text == "localparam") break
        val isTypeParam = match("type")
        skipOptionalTypeAndRanges(paramMap)
        if (peek().type == TokenType.IDENT) {
          val tok = consume()
          // Skip optional unpacked dims on parameter arrays e.g. parameter int LUT [0:3] = ...
          while (peek().text == "[") skipBracketGroup()
          if (match("=")) {
            if (isTypeParam || peek().text in svTypeKeywords) {
              skipBalancedUntil(setOf(",", ")"))
            } else if (peek().text == "'{" || (peek().text == "{" && looksLikeArrayLiteral())) {
              skipBalancedUntil(setOf(",", ")"))
            } else {
              val value = evalConstInt(parseExpr(paramMap), paramMap)
              paramMap[tok.text] = value
              params += VerilogParameter(tok.text, value, isLocal, tok.line)
            }
          } else {
            params += VerilogParameter(tok.text, paramMap[tok.text] ?: 0, isLocal, tok.line)
          }
        } else {
          consume()
        }
        if (peek().text == ",") {
          if (peek(1).text == "parameter" || peek(1).text == "localparam") break
          consume()
        } else {
          break
        }
      }
    }

    private fun looksLikeArrayLiteral(): Boolean {
      var offset = 1
      var depth = 1
      while (depth > 0 && peek(offset).type != TokenType.EOF) {
        when (peek(offset).text) {
          "{" -> depth++
          "}" -> depth--
          "default" -> if (peek(offset + 1).text == ":") return true
        }
        offset++
      }
      return false
    }

    private fun parsePortList(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
    ) {
      var lastDir: PortDirection? = null
      var lastKind = LogicSignalKind.WIRE
      var lastSigned = false
      var lastRange = Triple(1, 0, 0)

      while (peek().text != ")" && peek().type != TokenType.EOF) {
        if (match(".*")) {
          if (!match(",")) break
          continue
        }
        val dir = when (peek().text) {
          "input" -> { consume(); PortDirection.INPUT }
          "output" -> { consume(); PortDirection.OUTPUT }
          "inout", "ref" -> { consume(); PortDirection.INOUT }
          else -> null
        }

        val activeDir = dir ?: lastDir
        if (dir != null) {
          lastDir = dir
          lastKind = LogicSignalKind.WIRE
          lastSigned = false
          lastRange = Triple(1, 0, 0)
        }

        if (activeDir != null) {
          match("var")
          when {
            match("reg") -> lastKind = LogicSignalKind.REG
            match("logic") -> lastKind = LogicSignalKind.LOGIC
            match("integer") -> {
              lastKind = LogicSignalKind.INTEGER
              lastRange = Triple(32, 31, 0)
            }
            match("int") -> {
              lastKind = LogicSignalKind.LOGIC
              lastRange = Triple(32, 31, 0)
            }
            match("bit") -> lastKind = LogicSignalKind.LOGIC
            match("wire") || match("tri") -> lastKind = LogicSignalKind.WIRE
          }
          if (match("signed")) lastSigned = true
          else if (match("unsigned")) lastSigned = false

          // Handle custom typedef / interface.modport / pkg::type_t before port name
          if (peek().type == TokenType.IDENT && peek().text !in verilogKeywords) {
            if (peek(1).text == "::") {
              consume(); consume() // pkg ::
              if (peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT) consume()
            } else if (peek(1).text == "." && peek(2).type == TokenType.IDENT && peek(3).type == TokenType.IDENT) {
              consume(); consume(); consume() // if_name . modport
            } else if (peek(1).type == TokenType.IDENT) {
              consume() // custom_type_t
            }
          }

          if (peek().text == "[") {
            lastRange = parseMultiPackedRanges(params)
          }

          val nameTok = expectIdent()
          while (peek().text == "[") {
            // Unpacked port dims e.g. input [7:0] arr [0:3]
            skipBracketGroup()
          }
          if (match("=")) {
            // Optional SystemVerilog default input port expression
            parseExpr(params)
          }
          val port = RtlPort(
            name = nameTok.text,
            direction = activeDir,
            width = lastRange.first,
            isReg = lastKind == LogicSignalKind.REG || lastKind == LogicSignalKind.LOGIC,
            msb = lastRange.second,
            lsb = lastRange.third,
            isSigned = lastSigned,
            line = nameTok.line,
          )
          ports += port
          signalWidths[port.name] = port.width
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
          // Interface port (`axi_if.master bus`) or Verilog-1995 header (`a, b, y`)
          if (peek().type == TokenType.IDENT && peek(1).text == "." && peek(2).type == TokenType.IDENT) {
            consume(); consume(); consume()
          } else if (peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT) {
            consume()
          }
          val nameTok = expectIdent()
          while (peek().text == "[") skipBracketGroup()
          val port = RtlPort(nameTok.text, PortDirection.INPUT, 1, false, 0, 0, false, nameTok.line)
          ports += port
          signalWidths.putIfAbsent(port.name, 1)
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
      match("var")
      val kind = when {
        match("reg") -> LogicSignalKind.REG
        match("logic") || match("bit") -> LogicSignalKind.LOGIC
        match("integer") -> LogicSignalKind.INTEGER
        else -> {
          match("wire"); match("tri")
          LogicSignalKind.WIRE
        }
      }
      val isSigned = match("signed").also { if (!it) match("unsigned") }
      val (width, msb, lsb) = when {
        kind == LogicSignalKind.INTEGER -> Triple(32, 31, 0)
        peek().text == "[" -> parseMultiPackedRanges(params)
        else -> Triple(1, 0, 0)
      }
      while (peek().type != TokenType.EOF && peek().text != ";") {
        val nameTok = expectIdent()
        while (peek().text == "[") skipBracketGroup()
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
        signalWidths[nameTok.text] = width
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
        if (match("=")) {
          parseExpr(params)
        }
        if (!match(",")) break
      }
      expect(";")
    }

    private fun parseSignalDecl(
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
      assigns: MutableList<VerilogAssignStmt>,
    ) {
      val kw = consume().text
      val kind = when (kw) {
        "reg" -> LogicSignalKind.REG
        "logic", "bit", "byte", "shortint", "int", "longint" -> LogicSignalKind.LOGIC
        "integer" -> LogicSignalKind.INTEGER
        else -> LogicSignalKind.WIRE
      }
      val isSigned = match("signed").also { if (!it) match("unsigned") }
      val (width, msb, lsb) = when {
        kw == "integer" || kw == "int" -> Triple(32, 31, 0)
        kw == "byte" -> Triple(8, 7, 0)
        kw == "shortint" -> Triple(16, 15, 0)
        kw == "longint" -> Triple(64, 63, 0)
        peek().text == "[" -> parseMultiPackedRanges(params)
        else -> Triple(1, 0, 0)
      }
      while (peek().type != TokenType.EOF && peek().text != ";") {
        val nameTok = expectIdent()
        // Optional unpacked array dimensions e.g. reg [31:0] mem [0:255]; or logic [7:0] mem [16];
        while (peek().text == "[") {
          skipBracketGroup()
        }
        val existing = signals[nameTok.text]
        val finalW = if (existing?.isPort == true && width == 1) existing.width else width
        val finalMsb = if (existing?.isPort == true && width == 1) existing.msb else msb
        val finalLsb = if (existing?.isPort == true && width == 1) existing.lsb else lsb
        signalWidths[nameTok.text] = finalW
        signals[nameTok.text] = VerilogLogicSignal(
          name = nameTok.text,
          kind = kind,
          width = finalW,
          msb = finalMsb,
          lsb = finalLsb,
          isSigned = isSigned,
          isPort = existing?.isPort ?: false,
          line = nameTok.line,
        )
        if (match("=")) {
          if (peek().text == "'{" || (peek().text == "{" && looksLikeArrayLiteral())) {
            skipBalancedUntil(setOf(",", ";"))
          } else {
            val rhsExpr = parseExpr(params)
            // Inline wire/logic continuous assignment: `wire [7:0] sum = a + b;`
            if (kind == LogicSignalKind.WIRE || kind == LogicSignalKind.LOGIC) {
              assigns += VerilogAssignStmt(
                target = nameTok.text,
                targetMsb = if (finalW > 1) finalMsb else null,
                targetLsb = if (finalW > 1) finalLsb else null,
                expr = rhsExpr,
                line = nameTok.line,
              )
            }
          }
        }
        if (!match(",")) break
      }
      expect(";")
    }

    private fun parseTypedefOrEnum(
      paramMap: MutableMap<String, Int>,
      signals: MutableMap<String, VerilogLogicSignal>,
    ) {
      val line = consume().line // typedef or enum
      // If struct/union: `typedef struct packed { ... } pkt_t;`
      while (peek().type != TokenType.EOF && peek().text != "{" && peek().text != ";") {
        consume()
      }
      if (match("{")) {
        // Parse enum items if looks like enum (comma-separated idents, no ';'), else skip struct body
        val saved = pos
        var isStruct = false
        var depth = 1
        while (depth > 0 && peek().type != TokenType.EOF) {
          when (consume().text) {
            "{" -> depth++
            "}" -> depth--
            ";" -> isStruct = true
          }
        }
        if (!isStruct) {
          pos = saved
          var nextEnumVal = 0
          while (peek().text != "}" && peek().type != TokenType.EOF) {
            if (peek().type == TokenType.IDENT) {
              val itemTok = consume()
              if (match("=")) {
                nextEnumVal = evalConstInt(parseExpr(paramMap), paramMap)
              }
              paramMap[itemTok.text] = nextEnumVal
              nextEnumVal++
            } else {
              consume()
            }
            if (!match(",")) break
          }
          match("}")
        }
      }
      // Trailing type/variable names: `} state_t;` or `} state, next_state;`
      while (peek().type != TokenType.EOF && peek().text != ";") {
        if (peek().type == TokenType.IDENT) {
          val vTok = consume()
          while (peek().text == "[") skipBracketGroup()
          signals.putIfAbsent(
            vTok.text,
            VerilogLogicSignal(vTok.text, LogicSignalKind.LOGIC, 8, 7, 0, isSigned = false, isPort = false, line = line),
          )
          signalWidths.putIfAbsent(vTok.text, 8)
        } else {
          consume()
        }
        match(",")
      }
      match(";")
    }

    private fun parseFunctionDecl(
      paramMap: Map<String, Int>,
      targetFuncs: MutableMap<String, VerilogFuncDef>,
    ) {
      val startPos = pos
      consume() // function
      match("automatic")
      // Skip optional return range/type
      while (peek().text in svTypeKeywords || peek().text == "[" || (peek().type == TokenType.IDENT && peek(1).text != "(" && peek(1).text != ";")) {
        if (peek().text == "[") skipBracketGroup() else consume()
      }
      val fnName = if (peek().type == TokenType.IDENT) consume().text else {
        skipUntilKeyword("endfunction")
        return
      }
      val argNames = mutableListOf<String>()
      if (match("(")) {
        while (peek().text != ")" && peek().type != TokenType.EOF) {
          match("input"); match("output"); match("inout")
          while (peek().text in svTypeKeywords || peek().text == "[") {
            if (peek().text == "[") skipBracketGroup() else consume()
          }
          if (peek().type == TokenType.IDENT && peek(1).type == TokenType.IDENT) consume()
          if (peek().type == TokenType.IDENT) {
            argNames += consume().text
            while (peek().text == "[") skipBracketGroup()
            if (match("=")) parseExpr(paramMap)
          } else {
            consume()
          }
          if (!match(",")) break
        }
        match(")")
      }
      match(";")

      // Try extracting combinational function body (Verilog-2001/SV)
      val bodyStmts = mutableListOf<VerilogStmt>()
      var canExtract = true
      while (peek().text != "endfunction" && peek().type != TokenType.EOF) {
        when (peek().text) {
          "input" -> {
            consume()
            while (peek().text in svTypeKeywords || peek().text == "[") {
              if (peek().text == "[") skipBracketGroup() else consume()
            }
            while (peek().type != TokenType.EOF && peek().text != ";") {
              if (peek().type == TokenType.IDENT) argNames += consume().text else consume()
              match(",")
            }
            match(";")
          }
          "output", "reg", "logic", "wire", "integer", "int", "localparam" -> {
            skipUntilSemicolon()
          }
          "return" -> {
            val l = consume().line
            val e = parseExpr(paramMap)
            match(";")
            bodyStmts += VerilogStmt.Assign(fnName, e, nonBlocking = false, line = l)
          }
          else -> {
            try {
              bodyStmts += parseStmtOrBlock(paramMap)
            } catch (_: ParseException) {
              canExtract = false
              break
            }
          }
        }
      }
      if (canExtract && match("endfunction")) {
        if (match(":")) expectIdentIfPresent()
        val expr = extractReturnExpr(bodyStmts, fnName)
        if (expr != null) {
          targetFuncs[fnName] = VerilogFuncDef(argNames.distinct(), expr)
        }
      } else {
        pos = startPos
        skipUntilKeyword("endfunction")
        if (match(":")) expectIdentIfPresent()
      }
    }

    private fun extractReturnExpr(stmts: List<VerilogStmt>, fnName: String): VerilogExpr? {
      var cur: VerilogExpr? = null
      for (s in stmts) {
        when (s) {
          is VerilogStmt.Assign -> if (s.target == fnName || stmts.size == 1) cur = s.expr
          is VerilogStmt.IfStmt -> {
            val t = extractReturnExpr(s.thenBranch, fnName)
            val e = extractReturnExpr(s.elseBranch, fnName) ?: cur ?: VerilogExpr.Const(0, 32)
            if (t != null) cur = VerilogExpr.Ternary(s.cond, t, e)
          }
          is VerilogStmt.CaseStmt -> {
            var acc = extractReturnExpr(s.defaultBranch, fnName) ?: cur ?: VerilogExpr.Const(0, 32)
            for ((pats, body) in s.branches.asReversed()) {
              val b = extractReturnExpr(body, fnName)
              if (b != null && pats.isNotEmpty()) {
                val cond = pats.map { p -> VerilogExpr.Binary("==", s.selector, p) as VerilogExpr }
                  .reduce { a, x -> VerilogExpr.Binary("||", a, x) }
                acc = VerilogExpr.Ternary(cond, b, acc)
              }
            }
            cur = acc
          }
        }
      }
      return cur
    }

    private fun parseGatePrimitive(assigns: MutableList<VerilogAssignStmt>, params: Map<String, Int>) {
      val gateTok = consume()
      // Optional drive strength `(strong1, weak0)` or delay `#1` / `#(1, 2)`
      skipOptionalDriveOrDelay()
      // Optional instance name before `(out, in0, in1, ...)`
      if (peek().type == TokenType.IDENT) {
        consume()
        while (peek().text == "[") skipBracketGroup()
      }
      expect("(")
      val args = mutableListOf<VerilogExpr>()
      while (peek().text != ")" && peek().type != TokenType.EOF) {
        args += parseExpr(params)
        if (!match(",")) break
      }
      expect(")")
      expect(";")
      if (args.size >= 2) {
        val outTarget = extractBaseName(args.first()) ?: return
        val ins = args.drop(1)
        val combinedExpr: VerilogExpr = when (gateTok.text) {
          "buf", "bufif0", "bufif1" -> ins.first()
          "not", "notif0", "notif1" -> VerilogExpr.Unary("~", ins.first())
          "and" -> ins.reduce { a, b -> VerilogExpr.Binary("&", a, b) }
          "nand" -> VerilogExpr.Unary("~", ins.reduce { a, b -> VerilogExpr.Binary("&", a, b) })
          "or" -> ins.reduce { a, b -> VerilogExpr.Binary("|", a, b) }
          "nor" -> VerilogExpr.Unary("~", ins.reduce { a, b -> VerilogExpr.Binary("|", a, b) })
          "xor" -> ins.reduce { a, b -> VerilogExpr.Binary("^", a, b) }
          "xnor" -> VerilogExpr.Unary("~", ins.reduce { a, b -> VerilogExpr.Binary("^", a, b) })
          else -> ins.first()
        }
        assigns += VerilogAssignStmt(outTarget, expr = combinedExpr, line = gateTok.line)
      }
    }

    private fun skipOptionalDriveOrDelay() {
      if (peek().text == "(") {
        // Check if first token after '(' is a drive strength keyword rather than port list
        val s1 = peek(1).text.lowercase()
        if (s1 in setOf("supply0", "supply1", "strong0", "strong1", "pull0", "pull1", "weak0", "weak1", "highz0", "highz1")) {
          skipParenGroup()
        }
      }
      if (match("#")) {
        if (peek().text == "(") skipParenGroup()
        else if (peek().type == TokenType.NUMBER || peek().type == TokenType.IDENT) consume()
      }
    }

    private fun parseAssignStmts(assigns: MutableList<VerilogAssignStmt>, params: Map<String, Int>) {
      val line = expect("assign").line
      skipOptionalDriveOrDelay()
      while (peek().type != TokenType.EOF) {
        val lhsTerms = parseLhsTargets(params)
        expect("=")
        val expr = parseExpr(params)
        emitConcatOrSingleAssign(lhsTerms, expr, line) { target, msb, lsb, msbE, lsbE, rhs ->
          assigns += VerilogAssignStmt(target, msb, lsb, rhs, line, msbE, lsbE)
        }
        if (!match(",")) break
      }
      expect(";")
    }

    private data class LhsTarget(
      val name: String,
      val msb: Int? = null,
      val lsb: Int? = null,
      val msbExpr: VerilogExpr? = null,
      val lsbExpr: VerilogExpr? = null,
      val widthHint: Int = 1,
    )

    private fun parseLhsTargets(params: Map<String, Int>): List<LhsTarget> {
      if (match("{")) {
        val parts = mutableListOf<LhsTarget>()
        while (peek().text != "}" && peek().type != TokenType.EOF) {
          parts += parseLhsTargets(params)
          if (!match(",")) break
        }
        expect("}")
        return parts
      }
      val baseTok = expectIdent()
      var targetName = baseTok.text
      var tMsb: Int? = null
      var tLsb: Int? = null
      var tMsbExpr: VerilogExpr? = null
      var tLsbExpr: VerilogExpr? = null
      var wHint = signalWidths[targetName] ?: 1

      // Chained field (.field) and bracket ([...]) selectors on LHS
      while (peek().text == "[" || peek().text == ".") {
        if (match(".")) {
          val field = expectIdent().text
          if (targetName !in signalWidths) targetName = "${targetName}_$field"
        } else if (match("[")) {
          val (mE, lE, sliceW) = parseBracketSliceSpec(params)
          if (sliceW != null || peek().text != "[") {
            tMsbExpr = mE
            tLsbExpr = lE
            tMsb = evalConstInt(mE, params)
            tLsb = evalConstInt(lE, params)
            wHint = sliceW ?: 1
          }
        }
      }
      return listOf(LhsTarget(targetName, tMsb, tLsb, tMsbExpr, tLsbExpr, wHint))
    }

    private fun emitConcatOrSingleAssign(
      lhsTerms: List<LhsTarget>,
      rhsExpr: VerilogExpr,
      line: Int,
      emit: (target: String, msb: Int?, lsb: Int?, msbE: VerilogExpr?, lsbE: VerilogExpr?, rhs: VerilogExpr) -> Unit,
    ) {
      if (lhsTerms.size <= 1) {
        val t = lhsTerms.firstOrNull() ?: return
        emit(t.name, t.msb, t.lsb, t.msbExpr, t.lsbExpr, rhsExpr)
        return
      }
      var shiftOffset = 0
      for (t in lhsTerms.asReversed()) {
        val shiftedRhs = if (shiftOffset == 0) {
          rhsExpr
        } else {
          VerilogExpr.Binary(">>", rhsExpr, VerilogExpr.Const(shiftOffset, 32))
        }
        emit(t.name, t.msb, t.lsb, t.msbExpr, t.lsbExpr, shiftedRhs)
        shiftOffset += t.widthHint.coerceAtLeast(1)
      }
    }

    private fun parseBracketSliceSpec(params: Map<String, Int>): Triple<VerilogExpr, VerilogExpr, Int?> {
      val first = parseExpr(params)
      return when {
        match(":") -> {
          val second = parseExpr(params)
          expect("]")
          val w = max(1, abs(evalConstInt(first, params) - evalConstInt(second, params)) + 1)
          Triple(first, second, w)
        }
        match("+:") -> {
          val widthE = parseExpr(params)
          expect("]")
          val w = evalConstInt(widthE, params).coerceAtLeast(1)
          val msbE = VerilogExpr.Binary("+", first, VerilogExpr.Const(w - 1, 32))
          Triple(msbE, first, w)
        }
        match("-:") -> {
          val widthE = parseExpr(params)
          expect("]")
          val w = evalConstInt(widthE, params).coerceAtLeast(1)
          val lsbE = VerilogExpr.Binary("-", first, VerilogExpr.Const(w - 1, 32))
          Triple(first, lsbE, w)
        }
        else -> {
          expect("]")
          Triple(first, first, null)
        }
      }
    }

    private fun parseModuleInstance(params: Map<String, Int>): VerilogModuleInstance {
      val modTypeTok = expectIdent()
      val paramOverrides = linkedMapOf<String, Int>()
      if (match("#")) {
        expect("(")
        while (peek().text != ")" && peek().type != TokenType.EOF) {
          if (match(".")) {
            val pName = expectIdent().text
            expect("(")
            if (peek().text != ")") {
              if (peek().text in svTypeKeywords || peek().text == "type") {
                skipBalancedUntil(setOf(")"))
              } else {
                paramOverrides[pName] = evalConstInt(parseExpr(params), params)
              }
            }
            expect(")")
          } else {
            parseExpr(params)
          }
          if (!match(",")) break
        }
        expect(")")
      }
      val instTok = expectIdent()
      // Instance array: `my_mod u_arr [3:0] (...)`
      while (peek().text == "[") skipBracketGroup()
      expect("(")
      val named = linkedMapOf<String, VerilogExpr>()
      val positional = mutableListOf<VerilogExpr>()
      while (peek().text != ")" && peek().type != TokenType.EOF) {
        if (match(".*")) {
          // SV implicit wildcard port connection
        } else if (match(".")) {
          val portName = expectIdent().text
          if (match("(")) {
            if (peek().text != ")") {
              named[portName] = parseExpr(params)
            }
            expect(")")
          } else {
            // SV implicit `.portName` port connection
            named[portName] = VerilogExpr.Signal(portName)
          }
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
        paramOverrides = paramOverrides,
      )
    }

    private fun parseMultiPackedRanges(params: Map<String, Int>): Triple<Int, Int, Int> {
      var totalWidth = 1
      var firstRange = Triple(1, 0, 0)
      var count = 0
      while (peek().text == "[") {
        val r = parseSingleVectorRange(params)
        if (count == 0) firstRange = r
        totalWidth *= r.first
        count++
      }
      return if (count == 1) firstRange else Triple(totalWidth, totalWidth - 1, 0)
    }

    private fun parseSingleVectorRange(params: Map<String, Int>): Triple<Int, Int, Int> {
      expect("[")
      val firstExpr = parseExpr(params)
      val firstVal = evalConstInt(firstExpr, params)
      return if (match(":")) {
        val lsb = evalConstInt(parseExpr(params), params)
        expect("]")
        val width = max(1, abs(firstVal - lsb) + 1)
        Triple(width, firstVal, lsb)
      } else {
        expect("]")
        val width = max(1, firstVal)
        Triple(width, width - 1, 0)
      }
    }

    private fun evalConstInt(expr: VerilogExpr, params: Map<String, Int>): Int = when (expr) {
      is VerilogExpr.Const -> expr.value
      is VerilogExpr.Signal -> params[expr.name] ?: globalPackageConsts[expr.name] ?: 0
      is VerilogExpr.FuncCall -> {
        val vals = expr.args.map { evalConstInt(it, params) }
        when (expr.name.lowercase()) {
          "\$clog2" -> {
            val v = vals.firstOrNull() ?: 0
            if (v <= 1) 0 else 32 - Integer.numberOfLeadingZeros(v - 1)
          }
          "\$signed", "\$unsigned" -> vals.firstOrNull() ?: 0
          "\$bits" -> {
            val firstArg = expr.args.firstOrNull()
            if (firstArg is VerilogExpr.Signal) signalWidths[firstArg.name] ?: 32 else 32
          }
          else -> vals.firstOrNull() ?: 0
        }
      }
      is VerilogExpr.Binary -> when (expr.op) {
        "+" -> evalConstInt(expr.left, params) + evalConstInt(expr.right, params)
        "-" -> evalConstInt(expr.left, params) - evalConstInt(expr.right, params)
        "*" -> evalConstInt(expr.left, params) * evalConstInt(expr.right, params)
        "/" -> evalConstInt(expr.right, params).let { d -> if (d == 0) 0 else evalConstInt(expr.left, params) / d }
        "%" -> evalConstInt(expr.right, params).let { d -> if (d == 0) 0 else evalConstInt(expr.left, params) % d }
        "**" -> {
          val base = evalConstInt(expr.left, params)
          val exp = evalConstInt(expr.right, params).coerceIn(0, 30)
          var acc = 1
          repeat(exp) { acc *= base }
          acc
        }
        "<<" , "<<<" -> evalConstInt(expr.left, params) shl evalConstInt(expr.right, params).coerceIn(0, 30)
        ">>" -> evalConstInt(expr.left, params) ushr evalConstInt(expr.right, params).coerceIn(0, 30)
        ">>>" -> evalConstInt(expr.left, params) shr evalConstInt(expr.right, params).coerceIn(0, 30)
        "==", "===", "==?" -> if (evalConstInt(expr.left, params) == evalConstInt(expr.right, params)) 1 else 0
        "!=", "!==", "!=?" -> if (evalConstInt(expr.left, params) != evalConstInt(expr.right, params)) 1 else 0
        "<" -> if (evalConstInt(expr.left, params) < evalConstInt(expr.right, params)) 1 else 0
        "<=" -> if (evalConstInt(expr.left, params) <= evalConstInt(expr.right, params)) 1 else 0
        ">" -> if (evalConstInt(expr.left, params) > evalConstInt(expr.right, params)) 1 else 0
        ">=" -> if (evalConstInt(expr.left, params) >= evalConstInt(expr.right, params)) 1 else 0
        "&&" -> if (evalConstInt(expr.left, params) != 0 && evalConstInt(expr.right, params) != 0) 1 else 0
        "||" -> if (evalConstInt(expr.left, params) != 0 || evalConstInt(expr.right, params) != 0) 1 else 0
        "&" -> evalConstInt(expr.left, params) and evalConstInt(expr.right, params)
        "|" -> evalConstInt(expr.left, params) or evalConstInt(expr.right, params)
        "^" -> evalConstInt(expr.left, params) xor evalConstInt(expr.right, params)
        "~&" -> (evalConstInt(expr.left, params) and evalConstInt(expr.right, params)).inv()
        "~|" -> (evalConstInt(expr.left, params) or evalConstInt(expr.right, params)).inv()
        "~^", "^~" -> (evalConstInt(expr.left, params) xor evalConstInt(expr.right, params)).inv()
        else -> 0
      }
      is VerilogExpr.Unary -> when (expr.op) {
        "-" -> -evalConstInt(expr.inner, params)
        "+" -> evalConstInt(expr.inner, params)
        "!" -> if (evalConstInt(expr.inner, params) == 0) 1 else 0
        "~" -> evalConstInt(expr.inner, params).inv()
        "&" -> if (evalConstInt(expr.inner, params) == -1) 1 else 0
        "|" -> if (evalConstInt(expr.inner, params) != 0) 1 else 0
        "^" -> Integer.bitCount(evalConstInt(expr.inner, params)) % 2
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
        if (match(":")) expectIdentIfPresent()
        val list = mutableListOf<VerilogStmt>()
        val localParams = params.toMutableMap()
        while (peek().text != "end" && peek().type != TokenType.EOF) {
          when (peek().text) {
            ";" -> consume()
            "localparam", "parameter" -> {
              val isL = consume().text == "localparam"
              parseParamDecls(mutableListOf(), localParams, isL)
              match(";")
            }
            "integer", "int", "logic", "reg", "bit" -> {
              val kw = consume().text
              if (peek().text == "signed" || peek().text == "unsigned") consume()
              if (peek().text == "[") parseMultiPackedRanges(localParams)
              while (peek().type != TokenType.EOF && peek().text != ";") {
                val nTok = expectIdent()
                while (peek().text == "[") skipBracketGroup()
                if (match("=")) {
                  val e = parseExpr(localParams)
                  list += VerilogStmt.Assign(nTok.text, e, nonBlocking = false, line = nTok.line)
                } else if (kw == "int" || kw == "integer") {
                  // Keep signal trackable
                }
                if (!match(",")) break
              }
              expect(";")
            }
            else -> list += parseSingleStmtExpanded(localParams)
          }
        }
        expect("end")
        if (match(":")) expectIdentIfPresent()
        return list
      }
      if (match(";")) return emptyList()
      return parseSingleStmtExpanded(params)
    }

    private fun parseSingleStmtExpanded(params: Map<String, Int>): List<VerilogStmt> {
      // Skip optional inline SV modifiers (`unique`, `unique0`, `priority`) and timing prefixes (`#1`)
      while (peek().text in setOf("unique", "unique0", "priority")) consume()
      if (match("#")) {
        if (peek().text == "(") skipParenGroup() else if (peek().type == TokenType.NUMBER || peek().type == TokenType.IDENT) consume()
      }
      if (match(";")) return emptyList()

      val t = peek()
      return when (t.text) {
        "begin" -> parseStmtOrBlock(params)
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
              patterns += parseCasePatternExpr(params)
              while (match(",")) patterns += parseCasePatternExpr(params)
              expect(":")
              val body = parseStmtOrBlock(params)
              branches += patterns to body
            }
          }
          expect("endcase")
          listOf(VerilogStmt.CaseStmt(sel, branches, defaultB, line))
        }
        "for" -> parseForLoopStmts(params)
        "repeat" -> parseRepeatLoopStmts(params)
        "while" -> {
          consume()
          skipParenGroup()
          parseStmtOrBlock(params)
          emptyList()
        }
        "assert", "assume", "cover" -> {
          consume()
          match("property") || match("final")
          if (match("#")) {
            if (peek().type == TokenType.NUMBER || peek().type == TokenType.IDENT) consume()
          }
          if (peek().text == "(") skipParenGroup()
          if (match("else")) parseStmtOrBlock(params) else match(";")
          emptyList()
        }
        "disable" -> {
          skipUntilSemicolon()
          emptyList()
        }
        else -> {
          // Ignore non-synthesizable PLI/system calls `$display`, `$finish`, etc.
          if (t.type == TokenType.IDENT && t.text.startsWith("$")) {
            consume()
            if (peek().text == "(") skipParenGroup()
            match(";")
            return emptyList()
          }
          val line = t.line
          val lhsTerms = parseLhsTargets(params)
          when {
            match("++") -> {
              expect(";")
              val target = lhsTerms.firstOrNull()?.name ?: ""
              return listOf(
                VerilogStmt.Assign(
                  target = target,
                  expr = VerilogExpr.Binary("+", VerilogExpr.Signal(target), VerilogExpr.Const(1, 32)),
                  nonBlocking = false,
                  line = line,
                )
              )
            }
            match("--") -> {
              expect(";")
              val target = lhsTerms.firstOrNull()?.name ?: ""
              return listOf(
                VerilogStmt.Assign(
                  target = target,
                  expr = VerilogExpr.Binary("-", VerilogExpr.Signal(target), VerilogExpr.Const(1, 32)),
                  nonBlocking = false,
                  line = line,
                )
              )
            }
            peek().text in setOf("+=", "-=", "&=", "|=", "^=") -> {
              val opStr = consume().text.substring(0, 1)
              val expr = parseExpr(params)
              expect(";")
              val target = lhsTerms.firstOrNull() ?: return emptyList()
              return listOf(
                VerilogStmt.Assign(
                  target = target.name,
                  expr = VerilogExpr.Binary(opStr, VerilogExpr.Signal(target.name), expr),
                  nonBlocking = false,
                  line = line,
                  targetMsb = target.msb,
                  targetLsb = target.lsb,
                  targetMsbExpr = target.msbExpr,
                  targetLsbExpr = target.lsbExpr,
                )
              )
            }
          }
          val nonBlocking = match("<=")
          if (!nonBlocking) expect("=")
          val expr = parseExpr(params)
          expect(";")
          val out = mutableListOf<VerilogStmt>()
          emitConcatOrSingleAssign(lhsTerms, expr, line) { target, msb, lsb, msbE, lsbE, rhs ->
            out += VerilogStmt.Assign(target, rhs, nonBlocking, line, msb, lsb, msbE, lsbE)
          }
          out
        }
      }
    }

    private fun parseCasePatternExpr(params: Map<String, Int>): VerilogExpr {
      // Handle SystemVerilog case range `[lo:hi]` or normal expression
      if (match("[")) {
        val lo = parseExpr(params)
        if (match(":")) parseExpr(params)
        expect("]")
        return lo
      }
      return parseExpr(params)
    }

    private fun parseRepeatLoopStmts(params: Map<String, Int>): List<VerilogStmt> {
      expect("repeat")
      expect("(")
      val count = evalConstInt(parseExpr(params), params).coerceIn(0, 256)
      expect(")")
      val template = parseStmtOrBlock(params)
      val out = mutableListOf<VerilogStmt>()
      repeat(count) { out += template }
      return out
    }

    private data class ForLoopHeader(
      val varName: String,
      val initValue: Int,
      val condExpr: VerilogExpr,
      val stepOp: String,
      val stepExpr: VerilogExpr?,
    )

    private fun parseForLoopHeader(params: Map<String, Int>): ForLoopHeader {
      expect("for")
      expect("(")
      if (peek().text in setOf("integer", "int", "genvar")) consume()
      val varName = expectIdent().text
      expect("=")
      val initVal = evalConstInt(parseExpr(params), params)
      expect(";")
      val condExpr = parseExpr(params)
      expect(";")
      val stepOp: String
      val stepExpr: VerilogExpr
      if (match("++")) {
        expectIdent()
        stepOp = "+"
        stepExpr = VerilogExpr.Const(1, 32)
      } else if (match("--")) {
        expectIdent()
        stepOp = "-"
        stepExpr = VerilogExpr.Const(1, 32)
      } else {
        expectIdent()
        val pair = when {
          match("++") -> "+" to VerilogExpr.Const(1, 32)
          match("--") -> "-" to VerilogExpr.Const(1, 32)
          match("+=") -> "+" to parseExpr(params)
          match("-=") -> "-" to parseExpr(params)
          else -> {
            expect("=")
            "=" to parseExpr(params)
          }
        }
        stepOp = pair.first
        stepExpr = pair.second
      }
      expect(")")
      return ForLoopHeader(varName, initVal, condExpr, stepOp, stepExpr)
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
      while (guard < 512) {
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
        ";" -> consume()
        "input", "output", "inout" -> parseBodyPortDecl(ports, signals, paramMap)
        "wire", "reg", "logic", "bit", "byte", "shortint", "int", "longint", "integer", "tri", "supply0", "supply1" ->
          parseSignalDecl(signals, paramMap, assigns)
        "genvar" -> parseGenvarDecl(genvars)
        "parameter", "localparam" -> {
          val isLocal = consume().text == "localparam"
          parseParamDecls(parameters, paramMap, isLocal)
          expect(";")
        }
        "import" -> skipUntilSemicolon()
        "typedef", "enum" -> parseTypedefOrEnum(paramMap, signals)
        "assign" -> parseAssignStmts(assigns, paramMap)
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
        "case", "casez", "casex" -> parseGenerateCase(
          ports, signals, parameters, paramMap, genvars,
          assigns, alwaysBlocks, instances,
        )
        "initial", "final" -> skipBlockOrStmt()
        "function" -> parseFunctionDecl(paramMap, moduleFunctions)
        "task" -> {
          skipUntilKeyword("endtask")
          if (match(":")) expectIdentIfPresent()
        }
        "specify" -> skipUntilKeyword("endspecify")
        "property" -> {
          if (peek(1).text == "(") {
            consume(); skipParenGroup(); match(";")
          } else {
            skipUntilKeyword("endproperty")
            if (match(":")) expectIdentIfPresent()
          }
        }
        "sequence" -> {
          if (peek(1).text == "(") {
            consume(); skipParenGroup(); match(";")
          } else {
            skipUntilKeyword("endsequence")
            if (match(":")) expectIdentIfPresent()
          }
        }
        "defparam" -> skipUntilSemicolon()
        "assert", "assume", "cover" -> {
          consume()
          match("property") || match("final")
          if (match("#")) {
            if (peek().type == TokenType.NUMBER || peek().type == TokenType.IDENT) consume()
          }
          skipParenGroup()
          if (match("else")) skipBlockOrStmt() else match(";")
        }
        in gatePrimitives -> parseGatePrimitive(assigns, paramMap)
        else -> {
          if (peek().type == TokenType.IDENT && peek().text !in verilogKeywords) {
            if (peek(1).text == ":" && peek(2).text in setOf("assert", "assume", "cover", "for", "if", "case", "casez", "casex", "begin", "generate")) {
              consume(); consume() // skip label:
              return
            }
            val isScopedVar = peek(1).text == "::" && peek(2).type == TokenType.IDENT &&
              (peek(3).type == TokenType.IDENT || peek(3).text == "[")
            val isCustomTypeVar = peek(1).type == TokenType.IDENT &&
              (peek(2).text == ";" || peek(2).text == "=" || peek(2).text == "," || peek(2).text == "[")
            val isCustomPackedVar = peek(1).text == "[" && peek(2).text != "]"
            if (isScopedVar || isCustomTypeVar || isCustomPackedVar) {
              parseCustomTypedSignalDecl(signals, paramMap, assigns)
            } else {
              instances += parseModuleInstance(paramMap)
            }
          } else {
            consume()
          }
        }
      }
    }

    private fun parseCustomTypedSignalDecl(
      signals: MutableMap<String, VerilogLogicSignal>,
      params: Map<String, Int>,
      assigns: MutableList<VerilogAssignStmt>,
    ) {
      if (peek().type == TokenType.IDENT && peek(1).text == "::") {
        consume() // package
        consume() // ::
      }
      consume() // custom type identifier
      val packedWidth = if (peek().text == "[") parseMultiPackedRanges(params).first else 8
      while (peek().type != TokenType.EOF && peek().text != ";") {
        val nameTok = expectIdent()
        while (peek().text == "[") skipBracketGroup()
        signals.putIfAbsent(
          nameTok.text,
          VerilogLogicSignal(
            nameTok.text,
            LogicSignalKind.LOGIC,
            packedWidth,
            packedWidth - 1,
            0,
            isSigned = false,
            isPort = false,
            line = nameTok.line,
          ),
        )
        signalWidths.putIfAbsent(nameTok.text, packedWidth)
        if (match("=")) {
          if (peek().text == "'{" || (peek().text == "{" && looksLikeArrayLiteral())) {
            skipBalancedUntil(setOf(",", ";"))
          } else {
            assigns += VerilogAssignStmt(nameTok.text, expr = parseExpr(params), line = nameTok.line)
          }
        }
        if (!match(",")) break
      }
      expect(";")
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
      while (guard < 512) {
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

    private fun parseGenerateCase(
      ports: MutableList<RtlPort>,
      signals: MutableMap<String, VerilogLogicSignal>,
      parameters: MutableList<VerilogParameter>,
      paramMap: MutableMap<String, Int>,
      genvars: MutableSet<String>,
      assigns: MutableList<VerilogAssignStmt>,
      alwaysBlocks: MutableList<VerilogAlwaysBlock>,
      instances: MutableList<VerilogModuleInstance>,
    ) {
      consume() // case / casez / casex
      expect("(")
      val selVal = evalConstInt(parseExpr(paramMap), paramMap)
      expect(")")
      var matched = false
      while (peek().text != "endcase" && peek().type != TokenType.EOF) {
        if (match("default")) {
          match(":")
          if (!matched) {
            matched = true
            parseGenerateBodySlice(
              ports, signals, parameters, paramMap, genvars,
              assigns, alwaysBlocks, instances, loopSuffix = "",
            )
          } else {
            pos = findBlockOrStmtEndPos()
          }
        } else {
          val pats = mutableListOf<Int>()
          pats += evalConstInt(parseExpr(paramMap), paramMap)
          while (match(",")) pats += evalConstInt(parseExpr(paramMap), paramMap)
          expect(":")
          if (!matched && selVal in pats) {
            matched = true
            parseGenerateBodySlice(
              ports, signals, parameters, paramMap, genvars,
              assigns, alwaysBlocks, instances, loopSuffix = "",
            )
          } else {
            pos = findBlockOrStmtEndPos()
          }
        }
      }
      expect("endcase")
    }

    private fun findBlockOrStmtEndPos(): Int {
      val saved = pos
      skipCompleteGenerateItem()
      val endPos = pos
      pos = saved
      return endPos
    }

    private fun skipCompleteGenerateItem() {
      when {
        match("begin") -> {
          if (match(":")) expectIdentIfPresent()
          var depth = 1
          while (depth > 0 && peek().type != TokenType.EOF) {
            when (consume().text) {
              "begin" -> depth++
              "end" -> depth--
            }
          }
          if (match(":")) expectIdentIfPresent()
        }
        match("if") -> {
          skipParenGroup()
          skipCompleteGenerateItem()
          if (match("else")) skipCompleteGenerateItem()
        }
        match("for") -> {
          skipParenGroup()
          skipCompleteGenerateItem()
        }
        match("case") || match("casez") || match("casex") -> {
          var depth = 1
          while (depth > 0 && peek().type != TokenType.EOF) {
            when (consume().text) {
              "case", "casez", "casex" -> depth++
              "endcase" -> depth--
            }
          }
        }
        match("always") || match("always_ff") || match("always_comb") || match("always_latch") -> {
          if (match("@")) {
            if (!match("*")) skipParenGroup()
          }
          skipCompleteGenerateItem()
        }
        else -> {
          while (peek().text != ";" && peek().type != TokenType.EOF) consume()
          match(";")
        }
      }
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
        if (match(":")) expectIdentIfPresent()
        while (peek().type != TokenType.EOF && peek().text != "end") {
          parseModuleItem(
            ports, signals, parameters, localParams, genvars,
            assigns, alwaysBlocks, iterInst, setOf("end", "endgenerate", "endmodule"),
          )
        }
        match("end")
        if (match(":")) expectIdentIfPresent()
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
        is VerilogExpr.Index -> VerilogExpr.Index(
          base = substituteLoopExpr(expr.base, varName, value, params),
          indexExpr = substituteLoopExpr(expr.indexExpr, varName, value, params),
          width = expr.width,
        )
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
        is VerilogExpr.FuncCall -> VerilogExpr.FuncCall(
          expr.name,
          expr.args.map { substituteLoopExpr(it, varName, value, params) },
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
        if (match(":")) expectIdentIfPresent()
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

    private fun skipUntilSemicolon() {
      while (peek().text != ";" && peek().type != TokenType.EOF) consume()
      match(";")
    }

    private fun skipParenGroup() {
      if (!match("(")) return
      var depth = 1
      while (depth > 0 && peek().type != TokenType.EOF) {
        when (consume().text) {
          "(" -> depth++
          ")" -> depth--
        }
      }
    }

    private fun skipBracketGroup() {
      if (!match("[")) return
      var depth = 1
      while (depth > 0 && peek().type != TokenType.EOF) {
        when (consume().text) {
          "[" -> depth++
          "]" -> depth--
        }
      }
    }

    private fun skipBalancedUntil(stopTokens: Set<String>) {
      var paren = 0
      var bracket = 0
      var brace = 0
      while (peek().type != TokenType.EOF) {
        val t = peek().text
        if (paren == 0 && bracket == 0 && brace == 0 && t in stopTokens) break
        when (consume().text) {
          "(" -> paren++
          ")" -> paren = (paren - 1).coerceAtLeast(0)
          "[" -> bracket++
          "]" -> bracket = (bracket - 1).coerceAtLeast(0)
          "{" -> brace++
          "}" -> brace = (brace - 1).coerceAtLeast(0)
        }
      }
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
      while (peek().text in setOf("==", "!=", "===", "!==", "==?", "!=?") || peek().text == "inside") {
        if (match("inside")) {
          expect("{")
          val conds = mutableListOf<VerilogExpr>()
          while (peek().text != "}" && peek().type != TokenType.EOF) {
            if (match("[")) {
              val lo = parseExpr(params)
              val hi = if (match(":")) parseExpr(params) else lo
              expect("]")
              conds += VerilogExpr.Binary(
                "&&",
                VerilogExpr.Binary(">=", left, lo),
                VerilogExpr.Binary("<=", left, hi),
              )
            } else {
              conds += VerilogExpr.Binary("==", left, parseExpr(params))
            }
            if (!match(",")) break
          }
          expect("}")
          left = if (conds.isEmpty()) VerilogExpr.Const(0, 1)
          else conds.reduce { a, b -> VerilogExpr.Binary("||", a, b) }
        } else {
          val op = consume().text
          left = VerilogExpr.Binary(op, left, parseRelational(params))
        }
      }
      return left
    }

    private fun parseRelational(params: Map<String, Int>): VerilogExpr {
      var left = parseShift(params)
      while (peek().text in setOf("<", "<=", ">", ">=")) {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseShift(params))
      }
      return left
    }

    private fun parseShift(params: Map<String, Int>): VerilogExpr {
      var left = parseAdditive(params)
      while (peek().text in setOf("<<", ">>", "<<<", ">>>")) {
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
      var left = parsePower(params)
      while (peek().text == "*" || peek().text == "/" || peek().text == "%") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parsePower(params))
      }
      return left
    }

    private fun parsePower(params: Map<String, Int>): VerilogExpr {
      var left = parseUnary(params)
      while (peek().text == "**") {
        val op = consume().text
        left = VerilogExpr.Binary(op, left, parseUnary(params))
      }
      return left
    }

    private fun parseUnary(params: Map<String, Int>): VerilogExpr {
      if (peek().text in setOf("~", "!", "-", "+", "&", "|", "^", "~&", "~|", "~^", "^~")) {
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
          if (match("}")) {
            VerilogExpr.Const(0, 1)
          } else if (peek().text == "<<" || peek().text == ">>") {
            // SystemVerilog streaming concatenation `{>>{a, b}}` or `{<<8{data}}`
            consume()
            if (peek().text != "{") parseExpr(params)
            expect("{")
            val parts = mutableListOf<VerilogExpr>()
            while (peek().text != "}" && peek().type != TokenType.EOF) {
              parts += parseExpr(params)
              if (!match(",")) break
            }
            expect("}")
            expect("}")
            if (parts.size == 1) parts.first() else VerilogExpr.Concat(parts)
          } else {
            val first = parseExpr(params)
            if (match("{")) {
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
        }
        t.type == TokenType.STRING -> {
          consume()
          val content = t.text.removeSurrounding("\"")
          var acc = 0
          content.take(4).forEach { ch -> acc = (acc shl 8) or (ch.code and 0xFF) }
          VerilogExpr.Const(acc, (content.length * 8).coerceIn(8, 64))
        }
        t.type == TokenType.NUMBER -> {
          consume()
          // Handle SystemVerilog static width cast e.g. `8'(a + b)`
          if (t.text.endsWith("'") && peek().text == "(") {
            consume()
            val casted = parseExpr(params)
            expect(")")
            casted
          } else {
            parseVerilogNumber(t.text)
          }
        }
        t.type == TokenType.IDENT -> {
          var name = consume().text
          // Scope resolution `pkg::CONST`
          while (match("::")) {
            name = expectIdent().text
          }
          // Static type cast `signed'(expr)` / `logic'(expr)` / `N'(expr)`
          if (peek().text.startsWith("'") && peek().text.length == 1 && peek(1).text == "(") {
            consume() // '
            consume() // (
            val casted = parseExpr(params)
            expect(")")
            return casted
          }
          // Function / system call: `$clog2(N)`, `my_func(a, b)`
          if (match("(")) {
            val args = mutableListOf<VerilogExpr>()
            while (peek().text != ")" && peek().type != TokenType.EOF) {
              if (peek().text in svTypeKeywords || (peek().type == TokenType.IDENT && peek().text.endsWith("_t") && args.isEmpty() && name == "\$bits")) {
                skipBalancedUntil(setOf(",", ")"))
                args += VerilogExpr.Const(32, 32)
              } else {
                args += parseExpr(params)
              }
              if (!match(",")) break
            }
            expect(")")
            val userFn = moduleFunctions[name]
            return if (userFn != null) {
              inlineUserFunction(userFn, args)
            } else {
              VerilogExpr.FuncCall(name, args)
            }
          }

          var curExpr: VerilogExpr = when {
            name in params -> VerilogExpr.Const(params[name] ?: 0, 32)
            name in globalPackageConsts -> VerilogExpr.Const(globalPackageConsts[name] ?: 0, 32)
            else -> VerilogExpr.Signal(name)
          }

          // Chained suffixes: `[...]` slices / indices / `+:` / `-:` and `.field`
          while (peek().text == "[" || peek().text == ".") {
            if (match(".")) {
              val field = expectIdent().text
              curExpr = when (curExpr) {
                is VerilogExpr.Signal -> {
                  val combined = "${curExpr.name}_$field"
                  if (combined in params) VerilogExpr.Const(params[combined] ?: 0, 32)
                  else VerilogExpr.Signal(if (curExpr.name in signalWidths) curExpr.name else combined)
                }
                else -> curExpr
              }
            } else if (match("[")) {
              val (msbE, lsbE, sliceW) = parseBracketSliceSpec(params)
              curExpr = when {
                (sliceW != null || peek().text != "[") && curExpr is VerilogExpr.Signal -> {
                  val msb = evalConstInt(msbE, params)
                  val lsb = evalConstInt(lsbE, params)
                  VerilogExpr.Slice(curExpr.name, msb, lsb, msbE, lsbE)
                }
                else -> VerilogExpr.Index(curExpr, msbE, sliceW ?: 1)
              }
            }
          }
          curExpr
        }
        else -> throw ParseException(
          t.line,
          t.column,
          "Unexpected token '${t.text.ifEmpty { "EOF" }}' in expression",
        )
      }
    }

    private fun inlineUserFunction(fn: VerilogFuncDef, args: List<VerilogExpr>): VerilogExpr {
      val subst = fn.paramNames.mapIndexed { idx, pName -> pName to (args.getOrNull(idx) ?: VerilogExpr.Const(0, 1)) }.toMap()
      return substituteExprVars(fn.bodyExpr, subst)
    }

    private fun substituteExprVars(expr: VerilogExpr, subst: Map<String, VerilogExpr>): VerilogExpr = when (expr) {
      is VerilogExpr.Const -> expr
      is VerilogExpr.Signal -> subst[expr.name] ?: expr
      is VerilogExpr.Slice -> expr.copy(
        msbExpr = expr.msbExpr?.let { substituteExprVars(it, subst) },
        lsbExpr = expr.lsbExpr?.let { substituteExprVars(it, subst) },
      )
      is VerilogExpr.Index -> expr.copy(
        base = substituteExprVars(expr.base, subst),
        indexExpr = substituteExprVars(expr.indexExpr, subst),
      )
      is VerilogExpr.Concat -> VerilogExpr.Concat(expr.parts.map { substituteExprVars(it, subst) })
      is VerilogExpr.Replication -> VerilogExpr.Replication(expr.count, substituteExprVars(expr.inner, subst))
      is VerilogExpr.Unary -> VerilogExpr.Unary(expr.op, substituteExprVars(expr.inner, subst))
      is VerilogExpr.Binary -> VerilogExpr.Binary(
        expr.op,
        substituteExprVars(expr.left, subst),
        substituteExprVars(expr.right, subst),
      )
      is VerilogExpr.Ternary -> VerilogExpr.Ternary(
        substituteExprVars(expr.cond, subst),
        substituteExprVars(expr.thenExpr, subst),
        substituteExprVars(expr.elseExpr, subst),
      )
      is VerilogExpr.FuncCall -> VerilogExpr.FuncCall(
        expr.name,
        expr.args.map { substituteExprVars(it, subst) },
      )
    }

    private fun extractBaseName(expr: VerilogExpr): String? = when (expr) {
      is VerilogExpr.Signal -> expr.name
      is VerilogExpr.Slice -> expr.name
      is VerilogExpr.Index -> extractBaseName(expr.base)
      else -> null
    }

    private fun parseVerilogNumber(raw: String): VerilogExpr.Const {
      val clean = raw.replace("_", "")
      if (!clean.contains('\'')) {
        val intPart = clean.substringBefore('.')
        val v = intPart.toLongOrNull()?.toInt() ?: 0
        return VerilogExpr.Const(v, max(1, 32 - Integer.numberOfLeadingZeros(max(1, v))))
      }
      val parts = clean.split('\'', limit = 2)
      val width = parts[0].toIntOrNull()
      val rawBody = parts[1]
      // SystemVerilog unsized fill literals: '0, '1, 'x, 'z
      if (width == null && rawBody.length == 1 && rawBody[0].lowercaseChar() in listOf('0', '1', 'x', 'z')) {
        val fillVal = if (rawBody[0] == '1') -1 else 0
        return VerilogExpr.Const(fillVal, 32)
      }
      val signedPrefix = rawBody.firstOrNull()?.lowercaseChar() == 's'
      val body = if (signedPrefix) rawBody.drop(1) else rawBody
      val baseChar = body.firstOrNull()?.lowercaseChar() ?: 'd'
      val digits = body.drop(1).lowercase().replace('x', '0').replace('z', '0').replace('?', '0')
      val value = when (baseChar) {
        'b' -> digits.toLongOrNull(2)?.toInt() ?: 0
        'h' -> digits.toLongOrNull(16)?.toInt() ?: 0
        'o' -> digits.toLongOrNull(8)?.toInt() ?: 0
        else -> digits.toLongOrNull(10)?.toInt() ?: 0
      }
      return VerilogExpr.Const(value, max(1, width ?: 32))
    }
  }
}
