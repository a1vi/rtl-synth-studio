package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.example.data.model.RtlLanguage
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.LocalEdaColors

data class VerilogSyntaxPalette(
  val moduleKeyword: Color,
  val signalTypeKeyword: Color,
  val controlKeyword: Color,
  val numberLiteral: Color,
  val comment: Color,
  val stringLiteral: Color,
  val errorLineBackground: Color = Color(0x28EF4444),
  val errorTokenBackground: Color = Color(0x55EF4444),
  val errorUnderline: Color = Color(0xFFEF4444),
  val searchMatchBackground: Color = Color(0x453B82F6),
)

@Composable
fun rememberVerilogSyntaxPalette(): VerilogSyntaxPalette {
  val primary = MaterialTheme.colorScheme.primary
  val eda = LocalEdaColors.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (eda.isDark) 0.68f else 0.86f)
  return remember(primary, eda, muted) {
    VerilogSyntaxPalette(
      moduleKeyword = primary,
      signalTypeKeyword = eda.cyanHighlight,
      controlKeyword = eda.warning,
      numberLiteral = eda.success,
      comment = muted,
      stringLiteral = if (eda.isDark) eda.cyanHighlight.copy(alpha = 0.88f) else eda.cyanHighlight,
      errorLineBackground = eda.error.copy(alpha = if (eda.isDark) 0.14f else 0.10f),
      errorTokenBackground = eda.error.copy(alpha = if (eda.isDark) 0.34f else 0.22f),
      errorUnderline = eda.error,
      searchMatchBackground = primary.copy(alpha = if (eda.isDark) 0.28f else 0.18f),
    )
  }
}

class VerilogSyntaxVisualTransformation(
  private val palette: VerilogSyntaxPalette,
  private val errorLine: Int? = null,
  private val errorColumn: Int? = null,
  private val language: RtlLanguage = RtlLanguage.VERILOG,
  private val searchQuery: String = "",
) : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    return TransformedText(
      text = VerilogSyntaxHighlighter.highlight(
        source = text.text,
        palette = palette,
        errorLine = errorLine,
        errorColumn = errorColumn,
        language = language,
        searchQuery = searchQuery,
      ),
      offsetMapping = OffsetMapping.Identity,
    )
  }
}

data class HdlQuickFix(
  val title: String,
  val preview: String,
  val patchedSource: String,
)

data class FormattedErrorSnippet(
  val lineText: String,
  val caretLine: String,
  val hint: String,
  val suggestedFix: HdlQuickFix? = null,
)

object VerilogDiagnosticFormatter {
  fun buildSnippet(
    source: String,
    error: VerilogParseResult.Error,
    language: RtlLanguage = RtlLanguage.VERILOG,
  ): FormattedErrorSnippet {
    val lines = source.split('\n')
    val lineIdx = (error.line - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val rawLine = lines.getOrElse(lineIdx) { "" }
    val caretCol = (error.column - 1).coerceIn(0, rawLine.length)
    val caretLine = buildString {
      repeat(caretCol) { idx ->
        append(if (idx < rawLine.length && rawLine[idx] == '\t') '\t' else ' ')
      }
      append('^')
    }
    val fix = suggestQuickFix(source, lines, lineIdx, error, language)
    val baseHint = hintFor(error.message)
    val fullHint = if (fix != null) {
      "$baseHint Quick fix: ${fix.title} -> `${fix.preview}`"
    } else {
      baseHint
    }
    return FormattedErrorSnippet(
      lineText = rawLine,
      caretLine = caretLine,
      hint = fullHint,
      suggestedFix = fix,
    )
  }

  fun suggestQuickFix(
    source: String,
    lines: List<String>,
    errorLineIdx: Int,
    error: VerilogParseResult.Error,
    language: RtlLanguage = RtlLanguage.VERILOG,
  ): HdlQuickFix? {
    val lower = error.message.lowercase()

    // 1. Empty source -> insert clean starter template matching language
    if (source.isBlank() || "empty" in lower) {
      val starter = when (language) {
        RtlLanguage.VHDL -> """
          library ieee;
          use ieee.std_logic_1164.all;

          entity rtl_top is
            port (
              clk : in std_logic;
              a   : in std_logic;
              y   : out std_logic
            );
          end entity;

          architecture rtl of rtl_top is
          begin
            y <= not a;
          end architecture;
        """.trimIndent()
        RtlLanguage.SYSTEM_VERILOG -> """
          module rtl_top (
            input  logic clk,
            input  logic a,
            output logic y
          );
            always_comb begin
              y = ~a;
            end
          endmodule
        """.trimIndent()
        else -> """
          module rtl_top (
            input  wire clk,
            input  wire a,
            output wire y
          );
            assign y = ~a;
          endmodule
        """.trimIndent()
      }
      return HdlQuickFix(
        title = "Insert ${language.badge} Starter",
        preview = starter.lineSequence().firstOrNull().orEmpty(),
        patchedSource = starter,
      )
    }

    // 2. Missing semicolon ';'
    if ("expected ';'" in lower || ("';'" in lower && "expected" in lower)) {
      val targetIdx = findStatementLineMissingSemicolon(lines, errorLineIdx)
      if (targetIdx != null) {
        val updatedLine = appendSemicolonBeforeComment(lines[targetIdx], language)
        val patched = lines.toMutableList().apply { set(targetIdx, updatedLine) }.joinToString("\n")
        return HdlQuickFix(
          title = "Add ';' on line ${targetIdx + 1}",
          preview = updatedLine.trim(),
          patchedSource = patched,
        )
      }
    }

    // 3. Missing 'endmodule'
    if ("endmodule" in lower && ("missing" in lower || "expected" in lower || "found" in lower)) {
      val trimmed = source.trimEnd()
      val patched = "$trimmed\nendmodule\n"
      return HdlQuickFix(
        title = "Append 'endmodule'",
        preview = "endmodule",
        patchedSource = patched,
      )
    }

    // 4. Missing 'endcase'
    if ("expected 'endcase'" in lower) {
      val insertAt = errorLineIdx.coerceIn(0, lines.size)
      val indent = lines.getOrElse(insertAt) { "" }.takeWhile { it == ' ' || it == '\t' }
      val patchedList = lines.toMutableList().apply { add(insertAt, "${indent}endcase") }
      return HdlQuickFix(
        title = "Insert 'endcase' at line ${insertAt + 1}",
        preview = "${indent}endcase".trim(),
        patchedSource = patchedList.joinToString("\n"),
      )
    }

    // 5. Missing 'end'
    if ("expected 'end'" in lower) {
      val errLine = lines.getOrElse(errorLineIdx) { "" }
      val insertAt = errorLineIdx.coerceIn(0, lines.size)
      val indent = if (errLine.trimStart().startsWith("endmodule", ignoreCase = true)) {
        "  "
      } else {
        errLine.takeWhile { it == ' ' || it == '\t' }
      }
      val patchedList = lines.toMutableList().apply { add(insertAt, "${indent}end") }
      return HdlQuickFix(
        title = "Insert 'end' at line ${insertAt + 1}",
        preview = "${indent}end".trim(),
        patchedSource = patchedList.joinToString("\n"),
      )
    }

    // 6. Missing ')'
    if ("expected ')'" in lower) {
      val targetIdx = findUnclosedParenLine(lines, errorLineIdx)
      if (targetIdx != null) {
        val orig = lines[targetIdx]
        val semiIdx = orig.indexOf(';')
        val updated = if (semiIdx >= 0) {
          orig.substring(0, semiIdx) + ")" + orig.substring(semiIdx)
        } else {
          "$orig)"
        }
        val patched = lines.toMutableList().apply { set(targetIdx, updated) }.joinToString("\n")
        return HdlQuickFix(
          title = "Close ')' on line ${targetIdx + 1}",
          preview = updated.trim(),
          patchedSource = patched,
        )
      }
    }

    // 7. Unterminated block comment /* ... */
    if ("unterminated block comment" in lower) {
      val patched = "${source.trimEnd()}\n*/\n"
      return HdlQuickFix(
        title = "Close block comment '*/'",
        preview = "*/",
        patchedSource = patched,
      )
    }

    // 8. Unterminated string literal
    if ("unterminated string" in lower) {
      val orig = lines.getOrElse(errorLineIdx) { "" }
      val updated = if (orig.endsWith(";")) {
        orig.dropLast(1) + "\";"
      } else {
        "$orig\""
      }
      val patched = lines.toMutableList().apply { set(errorLineIdx, updated) }.joinToString("\n")
      return HdlQuickFix(
        title = "Close string literal on line ${errorLineIdx + 1}",
        preview = updated.trim(),
        patchedSource = patched,
      )
    }

    // 9. Missing module declaration wrapper
    if ("no 'module' declaration" in lower || "expected 'module'" in lower) {
      val body = lines.joinToString("\n") { "  $it" }
      val patched = "module rtl_top (\n  input  wire clk,\n  input  wire a,\n  output reg  y\n);\n$body\nendmodule"
      return HdlQuickFix(
        title = "Wrap code in 'module rtl_top'",
        preview = "module rtl_top (...); ... endmodule",
        patchedSource = patched,
      )
    }

    return null
  }

  private fun findStatementLineMissingSemicolon(lines: List<String>, errorLineIdx: Int): Int? {
    val errTrim = lines.getOrElse(errorLineIdx) { "" }.trim()
    val startScan = if (
      errTrim.isEmpty() ||
      errTrim.startsWith("endmodule", ignoreCase = true) ||
      errTrim.startsWith("endcase", ignoreCase = true) ||
      errTrim == "end" ||
      errTrim.startsWith("end ", ignoreCase = true) ||
      errTrim.startsWith("else", ignoreCase = true) ||
      errTrim.startsWith("always", ignoreCase = true) ||
      errTrim.startsWith("assign", ignoreCase = true)
    ) {
      (errorLineIdx - 1).coerceAtLeast(0)
    } else {
      errorLineIdx
    }

    for (idx in startScan downTo 0) {
      val raw = lines[idx]
      val codeOnly = stripInlineComment(raw).trim()
      if (codeOnly.isEmpty()) continue
      val alreadyTerminated = codeOnly.endsWith(";") ||
        codeOnly.endsWith(",") ||
        codeOnly.endsWith("(") ||
        codeOnly.endsWith("begin", ignoreCase = true) ||
        codeOnly.endsWith("then", ignoreCase = true) ||
        codeOnly.endsWith("is", ignoreCase = true) ||
        codeOnly.endsWith(":")
      return if (!alreadyTerminated) idx else null
    }
    return null
  }

  private fun findUnclosedParenLine(lines: List<String>, errorLineIdx: Int): Int? {
    for (idx in errorLineIdx downTo maxOf(0, errorLineIdx - 4)) {
      val code = stripInlineComment(lines[idx])
      val opens = code.count { it == '(' }
      val closes = code.count { it == ')' }
      if (opens > closes) return idx
    }
    return null
  }

  private fun stripInlineComment(line: String): String {
    val slIdx = line.indexOf("//")
    val vhIdx = line.indexOf("--")
    val cut = listOf(slIdx, vhIdx).filter { it >= 0 }.minOrNull()
    return if (cut != null) line.substring(0, cut) else line
  }

  private fun appendSemicolonBeforeComment(line: String, language: RtlLanguage): String {
    val commentMarker = if (language == RtlLanguage.VHDL) "--" else "//"
    val altMarker = if (language == RtlLanguage.VHDL) "//" else "--"
    val idx1 = line.indexOf(commentMarker).takeIf { it >= 0 }
    val idx2 = line.indexOf(altMarker).takeIf { it >= 0 }
    val commentIdx = listOfNotNull(idx1, idx2).minOrNull()
    return if (commentIdx != null) {
      val head = line.substring(0, commentIdx).trimEnd()
      val tail = line.substring(commentIdx)
      "$head; $tail"
    } else {
      "${line.trimEnd()};"
    }
  }

  fun hintFor(message: String): String {
    val lower = message.lowercase()
    return when {
      "';'" in lower || "expected ';'" in lower ->
        "Tip: Terminate port declarations, wire/reg lines, and statements with a semicolon ';'."
      "endmodule" in lower ->
        "Tip: Every 'module' declaration must be closed with a matching 'endmodule'."
      "entity" in lower || "architecture" in lower ->
        "Tip: Ensure every VHDL 'entity' has a matching 'architecture <name> of <entity> is ... end architecture;'."
      "expected ')'" in lower || "'('" in lower ->
        "Tip: Check for a missing closing ')' in the port list, sensitivity list, or instance pins."
      "expected 'end'" in lower ->
        "Tip: Match every 'begin' block with an 'end' keyword."
      "expected 'endcase'" in lower ->
        "Tip: Close every 'case' block with 'endcase'."
      "no 'module' declaration" in lower ->
        "Tip: Start your RTL code with `module my_design (...);` and end with `endmodule`."
      "empty" in lower ->
        "Tip: Enter or paste RTL source, or tap Insert Starter to begin."
      "unterminated" in lower ->
        "Tip: Close any open string literal \"...\" or block comment /* ... */."
      "expected identifier" in lower ->
        "Tip: Check for a missing signal/port name or stray operator around the marked column."
      else ->
        "Tip: Check HDL syntax around the highlighted line and column marker."
    }
  }
}

object VerilogSyntaxHighlighter {
  private val moduleKeywords = setOf(
    "module", "endmodule", "begin", "end",
    "function", "endfunction", "task", "endtask",
    "generate", "endgenerate", "package", "endpackage",
    "interface", "endinterface",
    // VHDL structural
    "entity", "architecture", "component", "library", "use", "is", "of", "port", "generic", "map",
  )

  private val signalTypeKeywords = setOf(
    "input", "output", "inout",
    "wire", "reg", "logic", "bit", "byte", "int",
    "signed", "unsigned", "integer", "parameter", "localparam", "genvar",
    "typedef", "enum", "struct", "modport", "import",
    // VHDL types & modes
    "in", "out", "buffer", "signal", "constant", "variable",
    "std_logic", "std_logic_vector", "downto", "to", "all",
  )

  private val controlKeywords = setOf(
    "assign", "always", "always_comb", "always_ff", "always_latch", "initial",
    "if", "else", "elsif", "then", "case", "casez", "casex", "default", "endcase",
    "posedge", "negedge", "or", "and", "xor", "not", "nand", "nor", "for",
    // VHDL control
    "process", "rising_edge", "falling_edge", "when", "others",
    // SDC commands
    "create_clock", "set_input_delay", "set_output_delay", "set_load", "set_clock_uncertainty",
    "set_false_path", "set_max_delay", "get_ports", "get_clocks", "all_inputs", "all_outputs",
  )

  fun lineRangeFor(source: String, oneBasedLine: Int): IntRange? {
    if (oneBasedLine < 1 || source.isEmpty()) return null
    var currentLine = 1
    var start = 0
    val n = source.length
    while (currentLine < oneBasedLine && start < n) {
      if (source[start] == '\n') {
        currentLine++
      }
      start++
    }
    if (currentLine != oneBasedLine || start > n) return null
    var end = start
    while (end < n && source[end] != '\n') {
      end++
    }
    return start..end
  }

  fun highlight(
    source: String,
    palette: VerilogSyntaxPalette,
    errorLine: Int? = null,
    errorColumn: Int? = null,
    language: RtlLanguage = RtlLanguage.VERILOG,
    searchQuery: String = "",
  ): AnnotatedString {
    val isVhdl = language == RtlLanguage.VHDL ||
      (source.contains("entity ", ignoreCase = true) && source.contains("architecture ", ignoreCase = true))
    val isSdc = language == RtlLanguage.SDC

    return buildAnnotatedString {
      append(source)
      val n = source.length
      var i = 0

      while (i < n) {
        val c = source[i]

        // 1a. Single-line comment // ...
        if (c == '/' && i + 1 < n && source[i + 1] == '/') {
          val start = i
          while (i < n && source[i] != '\n') i++
          addStyle(
            style = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic),
            start = start,
            end = i,
          )
          continue
        }

        // 1b. VHDL single-line comment -- ...
        if (isVhdl && c == '-' && i + 1 < n && source[i + 1] == '-') {
          val start = i
          while (i < n && source[i] != '\n') i++
          addStyle(
            style = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic),
            start = start,
            end = i,
          )
          continue
        }

        // 1c. SDC single-line comment # ...
        if (isSdc && c == '#') {
          val start = i
          while (i < n && source[i] != '\n') i++
          addStyle(
            style = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic),
            start = start,
            end = i,
          )
          continue
        }

        // 2. Multi-line block comment /* ... */
        if (c == '/' && i + 1 < n && source[i + 1] == '*') {
          val start = i
          i += 2
          while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) i++
          i = if (i + 1 < n) i + 2 else n
          addStyle(
            style = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic),
            start = start,
            end = i,
          )
          continue
        }

        // 3. String literal "..."
        if (c == '"') {
          val start = i
          i++
          while (i < n && source[i] != '"' && source[i] != '\n') {
            if (source[i] == '\\' && i + 1 < n) i++
            i++
          }
          if (i < n && source[i] == '"') i++
          addStyle(
            style = SpanStyle(color = palette.stringLiteral),
            start = start,
            end = i,
          )
          continue
        }

        // 4. Numeric literal (e.g. 8'hFF, 4'b0101, '1', 128)
        if (c.isDigit() || (c == '\'' && i + 1 < n && source[i + 1].lowercaseChar() in setOf('b', 'o', 'd', 'h', '0', '1'))) {
          val prevIsIdent = i > 0 && isIdentPart(source[i - 1])
          if (!prevIsIdent) {
            val start = i
            while (i < n && (source[i].isLetterOrDigit() || source[i] == '\'' || source[i] == '_' || source[i] == '?' || source[i] == '.')) {
              i++
            }
            addStyle(
              style = SpanStyle(color = palette.numberLiteral),
              start = start,
              end = i,
            )
            continue
          }
        }

        // 4.5. Preprocessor directives & macro usage (`define, `ifdef, `MACRO)
        if (c == '`') {
          val start = i
          i++
          while (i < n && isIdentPart(source[i])) i++
          addStyle(
            style = SpanStyle(color = palette.controlKeyword, fontWeight = FontWeight.SemiBold),
            start = start,
            end = i,
          )
          continue
        }

        // 5. Identifiers & HDL keywords (matched at full word boundaries)
        if (isIdentStart(c)) {
          val start = i
          i++
          while (i < n && isIdentPart(source[i])) i++
          val rawWord = source.substring(start, i)
          val word = if (isVhdl) rawWord.lowercase() else rawWord
          val keywordColor = when (word) {
            in moduleKeywords -> palette.moduleKeyword
            in signalTypeKeywords -> palette.signalTypeKeyword
            in controlKeywords -> palette.controlKeyword
            else -> null
          }
          if (keywordColor != null) {
            addStyle(
              style = SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold),
              start = start,
              end = i,
            )
          }
          continue
        }

        i++
      }

      // 6. Highlight Search Query Matches (if active)
      if (searchQuery.isNotBlank()) {
        var idx = source.indexOf(searchQuery, startIndex = 0, ignoreCase = true)
        while (idx >= 0) {
          addStyle(
            style = SpanStyle(background = palette.searchMatchBackground),
            start = idx,
            end = (idx + searchQuery.length).coerceAtMost(n),
          )
          idx = source.indexOf(searchQuery, startIndex = idx + searchQuery.length.coerceAtLeast(1), ignoreCase = true)
        }
      }

      // 7. Overlay real-time syntax error line & token highlight
      if (errorLine != null) {
        val range = lineRangeFor(source, errorLine)
        if (range != null && range.first < range.last) {
          addStyle(
            style = SpanStyle(
              background = palette.errorLineBackground,
              textDecoration = TextDecoration.Underline,
            ),
            start = range.first,
            end = range.last,
          )

          if (errorColumn != null && errorColumn >= 1) {
            val tokStart = (range.first + errorColumn - 1).coerceIn(range.first, range.last - 1)
            var tokEnd = tokStart + 1
            if (isIdentPart(source[tokStart])) {
              while (tokEnd < range.last && isIdentPart(source[tokEnd])) tokEnd++
            }
            addStyle(
              style = SpanStyle(
                color = palette.errorUnderline,
                background = palette.errorTokenBackground,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
              ),
              start = tokStart,
              end = tokEnd,
            )
          }
        }
      }
    }
  }

  private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

  private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
