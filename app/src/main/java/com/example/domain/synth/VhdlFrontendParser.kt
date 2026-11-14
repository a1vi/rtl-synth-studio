package com.example.domain.synth

import java.util.Locale

sealed interface VhdlConversionResult {
  data class Success(val verilogSource: String, val entityName: String) : VhdlConversionResult
  data class Error(val line: Int, val message: String) : VhdlConversionResult
}

/**
 * Lightweight VHDL-2008 RTL frontend that translates synthesizable VHDL entities and architectures
 * (ports, signals, concurrent assignments, and clocked/combinational `process` blocks) into
 * canonical Verilog RTL for elaboration, technology mapping, STA, and cycle simulation.
 */
object VhdlFrontendParser {

  private val entityRegex = Regex(
    pattern = """(?is)\bentity\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+is\b(.*?)\bend\s+(?:entity\s+)?(?:[a-zA-Z_][a-zA-Z0-9_]*)?\s*;""",
  )

  private val portBlockRegex = Regex(
    pattern = """(?is)\bport\s*\((.*)\)\s*;""",
  )

  private val archRegex = Regex(
    pattern = """(?is)\barchitecture\s+[a-zA-Z_][a-zA-Z0-9_]*\s+of\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+is\b(.*?)\bbegin\b(.*)\bend\s+(?:architecture(?:\s+[a-zA-Z_][a-zA-Z0-9_]*)?|(?!(?:if|process|case|loop|generate)\b)[a-zA-Z_][a-zA-Z0-9_]*)?\s*;""",
  )

  fun looksLikeVhdl(source: String): Boolean {
    val stripped = source.lines()
      .filterNot { it.trimStart().startsWith("--") }
      .joinToString("\n")
    return Regex("""(?is)\bentity\s+[a-zA-Z_][a-zA-Z0-9_]*\s+is\b""").containsMatchIn(stripped) &&
      !Regex("""(?is)\bmodule\s+[a-zA-Z_][a-zA-Z0-9_]*\b""").containsMatchIn(stripped)
  }

  fun toCanonicalVerilog(source: String): VhdlConversionResult {
    val cleanLines = source.lines().map { line ->
      val idx = line.indexOf("--")
      if (idx >= 0) line.substring(0, idx) else line
    }
    val clean = cleanLines.joinToString("\n")
    if (clean.isBlank()) {
      return VhdlConversionResult.Error(1, "Empty VHDL source.")
    }

    val entityMatch = entityRegex.find(clean)
      ?: return VhdlConversionResult.Error(1, "Expected VHDL 'entity <name> is ... end entity;' declaration.")

    val entityName = entityMatch.groupValues[1]
    val entityBody = entityMatch.groupValues[2]

    val portMatch = portBlockRegex.find(entityBody)
      ?: return VhdlConversionResult.Error(1, "Entity '$entityName' is missing a 'port (...);' block.")

    val rawPorts = portMatch.groupValues[1]
      .split(';')
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    val parsedPorts = mutableListOf<Triple<String, String, String>>() // (name, dir, range)
    val outputNames = mutableSetOf<String>()

    rawPorts.forEach { decl ->
      val colonIdx = decl.indexOf(':')
      if (colonIdx <= 0) {
        return VhdlConversionResult.Error(2, "Invalid VHDL port declaration '$decl'.")
      }
      val names = decl.substring(0, colonIdx).split(',').map { it.trim() }.filter { it.isNotEmpty() }
      val rest = decl.substring(colonIdx + 1).trim()
      val dirMatch = Regex("""(?is)^(in|out|inout|buffer)\b\s*(.*)$""").find(rest)
        ?: return VhdlConversionResult.Error(2, "Missing port direction (in/out/inout) in '$decl'.")

      val dir = when (dirMatch.groupValues[1].lowercase(Locale.ROOT)) {
        "in" -> "input"
        "inout" -> "inout"
        else -> "output"
      }
      val typeStr = dirMatch.groupValues[2]
      val range = extractRange(typeStr)

      names.forEach { n ->
        parsedPorts += Triple(n, dir, range)
        if (dir == "output") outputNames += n
      }
    }

    val archMatch = archRegex.find(clean)
      ?: return VhdlConversionResult.Error(
        line = cleanLines.size.coerceAtLeast(1),
        message = "Expected 'architecture <name> of $entityName is ... begin ... end architecture;'.",
      )

    val declPart = archMatch.groupValues[2]
    val stmtPart = archMatch.groupValues[3]

    val internalSignals = mutableListOf<Pair<String, String>>() // name to range
    Regex("""(?is)\bsignal\s+([^:]+)\s*:\s*([^;]+);""").findAll(declPart).forEach { m ->
      val names = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
      val range = extractRange(m.groupValues[2])
      names.forEach { internalSignals += it to range }
    }

    // Extract process blocks vs concurrent signal assignments
    val processRegex = Regex(
      """(?is)(?:[a-zA-Z_][a-zA-Z0-9_]*\s*:\s*)?\bprocess\s*\(([^)]*)\)\s*(?:is\b)?.*?\bbegin\b(.*?)\bend\s+process(?:\s+[a-zA-Z_][a-zA-Z0-9_]*)?\s*;""",
    )
    val processBlocks = mutableListOf<Pair<String, String>>() // sensitivity -> body
    val assignedInProcess = mutableSetOf<String>()

    val concurrentBody = processRegex.replace(stmtPart) { m ->
      val sens = m.groupValues[1].trim()
      val body = m.groupValues[2].trim()
      processBlocks += sens to body
      Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*<=""").findAll(body).forEach { lhs ->
        assignedInProcess += lhs.groupValues[1]
      }
      ""
    }

    val sb = StringBuilder()
    sb.appendLine("module $entityName (")
    parsedPorts.forEachIndexed { idx, (name, dir, range) ->
      val isRegOut = dir == "output" && name in assignedInProcess
      val qual = if (isRegOut) "reg " else "wire "
      val rangeStr = if (range.isNotEmpty()) "$range " else ""
      val comma = if (idx < parsedPorts.lastIndex) "," else ""
      sb.appendLine("  $dir $qual$rangeStr$name$comma")
    }
    sb.appendLine(");")

    internalSignals.forEach { (name, range) ->
      val qual = if (name in assignedInProcess) "reg" else "wire"
      val rangeStr = if (range.isNotEmpty()) " $range" else ""
      sb.appendLine("  $qual$rangeStr $name;")
    }

    // Concurrent assignments
    concurrentBody.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
      val assignMatch = Regex("""(?is)^([a-zA-Z_][a-zA-Z0-9_]*)\s*<=\s*(.+)$""").find(stmt)
      if (assignMatch != null) {
        val lhs = assignMatch.groupValues[1].trim()
        val rhs = convertVhdlWhenExpr(assignMatch.groupValues[2].trim())
        sb.appendLine("  assign $lhs = $rhs;")
      }
    }

    // Process blocks
    processBlocks.forEach { (sens, body) ->
      val isClocked = Regex("""(?is)\b(rising_edge|falling_edge|clk'event)\b""").containsMatchIn(body)
      val clkMatch = Regex("""(?is)\brising_edge\s*\(\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\)""").find(body)
        ?: Regex("""(?is)\bfalling_edge\s*\(\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\)""").find(body)
      val clkName = clkMatch?.groupValues?.get(1) ?: sens.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "clk" }
      if (isClocked) {
        sb.appendLine("  always @(posedge $clkName) begin")
        val normalizedBody = convertVhdlProcessBody(body, isSequential = true)
        sb.appendLine(normalizedBody)
        sb.appendLine("  end")
      } else {
        sb.appendLine("  always @(*) begin")
        val normalizedBody = convertVhdlProcessBody(body, isSequential = false)
        sb.appendLine(normalizedBody)
        sb.appendLine("  end")
      }
    }

    sb.appendLine("endmodule")
    return VhdlConversionResult.Success(sb.toString(), entityName)
  }

  private fun extractRange(typeStr: String): String {
    val downto = Regex("""(?is)\(\s*(\d+)\s+downto\s+(\d+)\s*\)""").find(typeStr)
    if (downto != null) {
      return "[${downto.groupValues[1]}:${downto.groupValues[2]}]"
    }
    val to = Regex("""(?is)\(\s*(\d+)\s+to\s+(\d+)\s*\)""").find(typeStr)
    if (to != null) {
      return "[${to.groupValues[2]}:${to.groupValues[1]}]"
    }
    return ""
  }

  private fun convertVhdlWhenExpr(expr: String): String {
    // Handle `val1 when cond else val2`
    val whenMatch = Regex("""(?is)^(.+?)\s+\bwhen\b\s+(.+?)\s+\belse\b\s+(.+)$""").find(expr)
    if (whenMatch != null) {
      val tVal = convertVhdlExpr(whenMatch.groupValues[1])
      val cond = convertVhdlExpr(whenMatch.groupValues[2])
      val fVal = convertVhdlWhenExpr(whenMatch.groupValues[3])
      return "($cond ? $tVal : $fVal)"
    }
    return convertVhdlExpr(expr)
  }

  private fun convertVhdlProcessBody(body: String, isSequential: Boolean): String {
    // Convert <= in combinational blocks using a placeholder so '=' in conditions becomes '==' safely
    var out = body
    if (!isSequential) {
      out = out.replace("<=", "__VHDL_COMB_ASSIGN__")
    }
    // Convert else BEFORE elsif so "end else if" does not re-match \belse\b
    out = Regex("""(?is)\belse\b""").replace(out, "end else begin")
    out = Regex("""(?is)\belsif\s+rising_edge\s*\([^)]*\)\s+then\b""").replace(out, "end else if (1'b1) begin")
    out = Regex("""(?is)\belsif\s+(?!\()([^;\n]+?)\s+then\b""").replace(out) { m ->
      "end else if (${convertVhdlExpr(m.groupValues[1])}) begin"
    }
    out = Regex("""(?is)\bif\s+rising_edge\s*\([^)]*\)\s+then\b""").replace(out, "if (1'b1) begin")
    out = Regex("""(?is)\bif\s+(?!\()([^;\n]+?)\s+then\b""").replace(out) { m ->
      "if (${convertVhdlExpr(m.groupValues[1])}) begin"
    }
    out = Regex("""(?is)\bend\s+if\s*;""").replace(out, "end")

    // Simple case conversion
    out = Regex("""(?is)\bcase\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+is\b""").replace(out) { m ->
      "case (${m.groupValues[1]})"
    }
    out = Regex("""(?is)\bwhen\s+others\s*=>""").replace(out, "default:")
    out = Regex("""(?is)\bwhen\s+([^\n=>]+)=>""").replace(out) { m ->
      "${convertVhdlExpr(m.groupValues[1].trim())}:"
    }
    out = Regex("""(?is)\bend\s+case\s*;""").replace(out, "endcase")

    return convertVhdlExpr(out)
      .replace("__VHDL_COMB_ASSIGN__", "=")
      .lines()
      .filter { it.isNotBlank() }
      .joinToString("\n") { "    ${it.trim()}" }
  }

  private fun convertVhdlExpr(expr: String): String {
    var s = expr.trim()
    // Single bit literals '0' / '1'
    s = s.replace("'0'", "1'b0").replace("'1'", "1'b1")
    // Hex literals x"FF"
    s = Regex("""(?i)x"([0-9a-f_]+)"""").replace(s) { m ->
      val digits = m.groupValues[1].replace("_", "")
      "${digits.length * 4}'h$digits"
    }
    // Binary literals "0101"
    s = Regex(""""([01_]+)"""").replace(s) { m ->
      val bits = m.groupValues[1].replace("_", "")
      "${bits.length}'b$bits"
    }
    // Others => '0'
    s = Regex("""(?is)\(\s*others\s*=>\s*1'b0\s*\)""").replace(s, "0")
    s = Regex("""(?is)\(\s*others\s*=>\s*1'b1\s*\)""").replace(s, "8'hFF")
    // Inequality & equality
    s = s.replace("/=", "!=")
    // Replace single '=' used as comparison inside conditions without breaking <= or != or ==
    s = Regex("""(?<![<>=!])=(?!=)""").replace(s) { m ->
      // Keep assignment '=' untouched if preceded by identifier at statement start? No, in VHDL signal assignments always use <=!
      // Only inside converted combinational block `=` might exist; handle carefully:
      "=="
    }
    // Restore combinational assignments `sig == expr;` back to `sig = expr;`? No, easier: only convert `=` in `if (...)`? Let's keep `<=` until after condition replacement!
    // Logical operators
    s = Regex("""(?is)\band\b""").replace(s, "&")
    s = Regex("""(?is)\bnand\b""").replace(s, "&")
    s = Regex("""(?is)\bor\b""").replace(s, "|")
    s = Regex("""(?is)\bxor\b""").replace(s, "^")
    s = Regex("""(?is)\bnot\s+""").replace(s, "~")
    return s
  }
}
