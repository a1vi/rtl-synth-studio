package com.example.domain.synth

data class PreprocessedVerilog(
  val processedSource: String,
  val definedMacros: Map<String, String>,
  val diagnostics: List<String>,
)

/**
 * Lightweight Verilog Preprocessor supporting:
 * - `` `define NAME [value] `` and `` `undef NAME ``
 * - `` `ifdef NAME `` / `` `ifndef NAME `` / `` `else `` / `` `endif `` (nested)
 * - `` `MACRO `` token expansion outside comments and string literals
 * - `` `timescale ``, `` `default_nettype ``, `` `celldefine ``, `` `endcelldefine ``
 *
 * Preserves 1:1 line numbering by replacing directive-only and inactive lines
 * with blank lines so parser line/column diagnostics match the user's editor.
 */
object VerilogPreprocessor {

  private data class CondFrame(
    val parentActive: Boolean,
    val anyBranchTaken: Boolean,
    val currentActive: Boolean,
  )

  fun preprocess(source: String): PreprocessedVerilog {
    val macros = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<String>()
    val condStack = ArrayDeque<CondFrame>()
    val lines = source.split('\n')
    val outLines = ArrayList<String>(lines.size)

    fun isActive(): Boolean = condStack.lastOrNull()?.currentActive ?: true

    lines.forEachIndexed { idx, rawLine ->
      val lineNo = idx + 1
      val trimmed = rawLine.trimStart()

      if (trimmed.startsWith("`")) {
        val directive = parseDirectiveWord(trimmed)
        when (directive) {
          "`ifdef", "`ifndef" -> {
            val isNdef = directive == "`ifndef"
            val macroName = trimmed.removePrefix(directive).trim()
              .takeWhile { it.isLetterOrDigit() || it == '_' }
            val parentAct = isActive()
            val condMet = if (macroName.isEmpty()) {
              diagnostics += "Line $lineNo: Missing macro identifier after $directive."
              false
            } else {
              val defined = macros.containsKey(macroName)
              if (isNdef) !defined else defined
            }
            val branchActive = parentAct && condMet
            condStack.addLast(
              CondFrame(
                parentActive = parentAct,
                anyBranchTaken = condMet,
                currentActive = branchActive,
              )
            )
            outLines += ""
          }

          "`else" -> {
            if (condStack.isEmpty()) {
              diagnostics += "Line $lineNo: Unexpected `else without matching `ifdef/`ifndef."
            } else {
              val top = condStack.removeLast()
              val nextActive = top.parentActive && !top.anyBranchTaken
              condStack.addLast(
                top.copy(
                  anyBranchTaken = true,
                  currentActive = nextActive,
                )
              )
            }
            outLines += ""
          }

          "`elsif" -> {
            val macroName = trimmed.removePrefix("`elsif").trim()
              .takeWhile { it.isLetterOrDigit() || it == '_' }
            if (condStack.isEmpty()) {
              diagnostics += "Line $lineNo: Unexpected `elsif without matching `ifdef/`ifndef."
            } else {
              val top = condStack.removeLast()
              val condMet = macroName.isNotEmpty() && macros.containsKey(macroName)
              val nextActive = top.parentActive && !top.anyBranchTaken && condMet
              condStack.addLast(
                top.copy(
                  anyBranchTaken = top.anyBranchTaken || condMet,
                  currentActive = nextActive,
                )
              )
            }
            outLines += ""
          }

          "`endif" -> {
            if (condStack.isEmpty()) {
              diagnostics += "Line $lineNo: Unexpected `endif without matching `ifdef/`ifndef."
            } else {
              condStack.removeLast()
            }
            outLines += ""
          }

          "`define" -> {
            if (isActive()) {
              val rest = trimmed.removePrefix("`define").trimStart()
              val name = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
              if (name.isEmpty()) {
                diagnostics += "Line $lineNo: Missing macro name in `define."
              } else {
                val rawBody = rest.removePrefix(name)
                  .substringBefore("//")
                  .trim()
                macros[name] = expandMacrosInLine(rawBody, macros, diagnostics, lineNo)
              }
            }
            outLines += ""
          }

          "`undef" -> {
            if (isActive()) {
              val name = trimmed.removePrefix("`undef").trim()
                .takeWhile { it.isLetterOrDigit() || it == '_' }
              macros.remove(name)
            }
            outLines += ""
          }

          "`timescale", "`default_nettype", "`celldefine", "`endcelldefine", "`resetall" -> {
            outLines += ""
          }

          "`include" -> {
            if (isActive()) {
              diagnostics += "Line $lineNo: External `include directive ignored in single-file mobile workspace; paste module definitions inline."
            }
            outLines += ""
          }

          else -> {
            // Line starts with a macro invocation such as `` `MY_EXPR ``
            outLines += if (isActive()) {
              expandMacrosInLine(rawLine, macros, diagnostics, lineNo)
            } else {
              ""
            }
          }
        }
      } else {
        outLines += if (isActive()) {
          expandMacrosInLine(rawLine, macros, diagnostics, lineNo)
        } else {
          ""
        }
      }
    }

    if (condStack.isNotEmpty()) {
      diagnostics += "Unterminated `ifdef/`ifndef block (missing `endif)."
    }

    return PreprocessedVerilog(
      processedSource = outLines.joinToString("\n"),
      definedMacros = macros.toMap(),
      diagnostics = diagnostics.distinct(),
    )
  }

  private fun parseDirectiveWord(trimmedLine: String): String {
    var i = 1
    while (i < trimmedLine.length && (trimmedLine[i].isLetterOrDigit() || trimmedLine[i] == '_')) {
      i++
    }
    return trimmedLine.substring(0, i)
  }

  private fun expandMacrosInLine(
    line: String,
    macros: Map<String, String>,
    diagnostics: MutableList<String>,
    lineNo: Int,
  ): String {
    if ('`' !in line) return line
    var current = line
    repeat(8) {
      if ('`' !in current) return current
      val next = substituteOnce(current, macros, diagnostics, lineNo)
      if (next == current) return current
      current = next
    }
    return current
  }

  private fun substituteOnce(
    line: String,
    macros: Map<String, String>,
    diagnostics: MutableList<String>,
    lineNo: Int,
  ): String {
    val sb = StringBuilder(line.length)
    var i = 0
    val n = line.length
    var inString = false
    while (i < n) {
      val c = line[i]
      if (c == '"' && (i == 0 || line[i - 1] != '\\')) {
        inString = !inString
        sb.append(c)
        i++
        continue
      }
      if (!inString && c == '/' && i + 1 < n && line[i + 1] == '/') {
        sb.append(line.substring(i))
        break
      }
      if (!inString && c == '`' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
        var j = i + 1
        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
        val name = line.substring(i + 1, j)
        val replacement = macros[name]
        if (replacement != null) {
          sb.append(replacement)
        } else {
          diagnostics += "Line $lineNo: Undefined macro `$name."
          sb.append("0")
        }
        i = j
        continue
      }
      sb.append(c)
      i++
    }
    return sb.toString()
  }
}
