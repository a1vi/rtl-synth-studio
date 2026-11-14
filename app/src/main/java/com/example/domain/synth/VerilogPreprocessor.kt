package com.example.domain.synth

data class PreprocessedVerilog(
  val processedSource: String,
  val definedMacros: Map<String, String>,
  val diagnostics: List<String>,
)

/**
 * Hardened Verilog / SystemVerilog Preprocessor supporting:
 * - Multi-line `` `define NAME [value] `` (backslash ``\`` continuations)
 * - Function-like macros `` `define MACRO(a, b) ((a) + (b)) `` and nested invocations
 * - `` `undef NAME ``, `` `ifdef `` / `` `ifndef `` / `` `elsif `` / `` `else `` / `` `endif ``
 * - `` // synopsys translate_off ... translate_on `` & `` // synthesis translate_off ... translate_on ``
 * - Standard directives: `` `timescale ``, `` `default_nettype ``, `` `celldefine ``, `` `pragma ``, etc.
 *
 * Preserves exact 1:1 line numbering by emitting blank lines for consumed directives/inactive regions.
 */
object VerilogPreprocessor {

  private data class CondFrame(
    val parentActive: Boolean,
    val anyBranchTaken: Boolean,
    val currentActive: Boolean,
  )

  private data class MacroDef(
    val params: List<String>?,
    val body: String,
  )

  fun preprocess(source: String): PreprocessedVerilog {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val macroDefs = linkedMapOf<String, MacroDef>()
    val diagnostics = mutableListOf<String>()
    val condStack = ArrayDeque<CondFrame>()
    val lines = normalized.split('\n')
    val outLines = ArrayList<String>(lines.size)
    var inTranslateOff = false
    var inBlockComment = false

    fun isActive(): Boolean = !inTranslateOff && (condStack.lastOrNull()?.currentActive ?: true)

    var idx = 0
    while (idx < lines.size) {
      val rawLine = lines[idx]
      val lineNo = idx + 1

      // Check synthesis/synopsys translate_off / translate_on pragmas
      val lowTrim = rawLine.trim().lowercase()
      if (!inBlockComment && isTranslateOffDirective(lowTrim)) {
        inTranslateOff = true
        outLines += rawLine
        idx++
        continue
      }
      if (inTranslateOff) {
        if (isTranslateOnDirective(lowTrim)) {
          inTranslateOff = false
          outLines += rawLine
        } else {
          outLines += ""
        }
        idx++
        continue
      }

      val (trimmedNoComment, endsInComment, nextBlockState) = stripLeadingBlockCommentState(rawLine, inBlockComment)
      inBlockComment = nextBlockState
      val trimmed = trimmedNoComment.trimStart()

      if (!endsInComment && trimmed.startsWith("`")) {
        val directive = parseDirectiveWord(trimmed)
        when (directive) {
          "`ifdef", "`ifndef" -> {
            val isNdef = directive == "`ifndef"
            val macroName = extractMacroIdent(trimmed.removePrefix(directive))
            val parentAct = condStack.lastOrNull()?.currentActive ?: true
            val condMet = if (macroName.isEmpty()) {
              diagnostics += "Line $lineNo: Missing macro identifier after $directive."
              false
            } else {
              val defined = macroDefs.containsKey(macroName)
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
            idx++
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
            idx++
          }

          "`elsif" -> {
            val macroName = extractMacroIdent(trimmed.removePrefix("`elsif"))
            if (condStack.isEmpty()) {
              diagnostics += "Line $lineNo: Unexpected `elsif without matching `ifdef/`ifndef."
            } else {
              val top = condStack.removeLast()
              val condMet = macroName.isNotEmpty() && macroDefs.containsKey(macroName)
              val nextActive = top.parentActive && !top.anyBranchTaken && condMet
              condStack.addLast(
                top.copy(
                  anyBranchTaken = top.anyBranchTaken || condMet,
                  currentActive = nextActive,
                )
              )
            }
            outLines += ""
            idx++
          }

          "`endif" -> {
            if (condStack.isEmpty()) {
              diagnostics += "Line $lineNo: Unexpected `endif without matching `ifdef/`ifndef."
            } else {
              condStack.removeLast()
            }
            outLines += ""
            idx++
          }

          "`define" -> {
            // Accumulate backslash line continuations while preserving exact 1:1 line count
            val bodySb = StringBuilder()
            var firstChunk = stripSingleLineComments(trimmed.removePrefix("`define").trimStart())
            var consumedExtra = 0
            var continues = firstChunk.trimEnd().endsWith("\\")
            if (continues) {
              firstChunk = firstChunk.trimEnd().dropLast(1)
            }
            bodySb.append(firstChunk)
            while (continues && idx + consumedExtra + 1 < lines.size) {
              consumedExtra++
              var nextChunk = stripSingleLineComments(lines[idx + consumedExtra])
              continues = nextChunk.trimEnd().endsWith("\\")
              if (continues) {
                nextChunk = nextChunk.trimEnd().dropLast(1)
              }
              bodySb.append(' ').append(nextChunk.trim())
            }

            if (isActive()) {
              val fullDef = bodySb.toString().trimStart()
              val name = fullDef.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
              if (name.isEmpty()) {
                diagnostics += "Line $lineNo: Missing macro name in `define."
              } else {
                val afterName = fullDef.removePrefix(name)
                if (afterName.startsWith("(")) {
                  val closeIdx = findMatchingParen(afterName, 0)
                  if (closeIdx > 0) {
                    val rawParams = afterName.substring(1, closeIdx)
                    val paramList = rawParams.split(',')
                      .map { it.trim().substringBefore('=').trim() }
                      .filter { it.isNotEmpty() }
                    val rawBody = afterName.substring(closeIdx + 1).trim()
                    macroDefs[name] = MacroDef(
                      params = paramList,
                      body = expandMacrosInLine(rawBody, macroDefs, diagnostics, lineNo),
                    )
                  } else {
                    macroDefs[name] = MacroDef(params = null, body = afterName.trim())
                  }
                } else {
                  val rawBody = afterName.trim()
                  macroDefs[name] = MacroDef(
                    params = null,
                    body = expandMacrosInLine(rawBody, macroDefs, diagnostics, lineNo),
                  )
                }
              }
            }
            outLines += ""
            repeat(consumedExtra) { outLines += "" }
            idx += 1 + consumedExtra
          }

          "`undef" -> {
            if (isActive()) {
              val name = extractMacroIdent(trimmed.removePrefix("`undef"))
              macroDefs.remove(name)
            }
            outLines += ""
            idx++
          }

          "`timescale", "`default_nettype", "`celldefine", "`endcelldefine", "`resetall",
          "`pragma", "`begin_keywords", "`end_keywords", "`line",
          "`unconnected_drive", "`nounconnected_drive", "`protect", "`endprotect" -> {
            outLines += ""
            idx++
          }

          "`include" -> {
            if (isActive()) {
              diagnostics += "Line $lineNo: External `include directive skipped in standalone mode; paste shared defs inline if needed."
            }
            outLines += ""
            idx++
          }

          else -> {
            outLines += if (isActive()) {
              expandMacrosInLine(trimmedNoComment, macroDefs, diagnostics, lineNo)
            } else {
              ""
            }
            idx++
          }
        }
      } else {
        outLines += if (isActive()) {
          if (endsInComment) trimmedNoComment else expandMacrosInLine(trimmedNoComment, macroDefs, diagnostics, lineNo)
        } else {
          ""
        }
        idx++
      }
    }

    if (condStack.isNotEmpty()) {
      diagnostics += "Unterminated `ifdef/`ifndef block (missing `endif)."
    }

    return PreprocessedVerilog(
      processedSource = outLines.joinToString("\n"),
      definedMacros = macroDefs.mapValues { it.value.body },
      diagnostics = diagnostics.distinct(),
    )
  }

  private fun isTranslateOffDirective(lowTrim: String): Boolean {
    if (!lowTrim.startsWith("//") && !lowTrim.startsWith("/*")) return false
    return ("translate_off" in lowTrim && ("synopsys" in lowTrim || "synthesis" in lowTrim || "pragma" in lowTrim))
  }

  private fun isTranslateOnDirective(lowTrim: String): Boolean {
    if (!lowTrim.startsWith("//") && !lowTrim.startsWith("/*")) return false
    return ("translate_on" in lowTrim && ("synopsys" in lowTrim || "synthesis" in lowTrim || "pragma" in lowTrim))
  }

  private fun stripLeadingBlockCommentState(line: String, startedInComment: Boolean): Triple<String, Boolean, Boolean> {
    var inComment = startedInComment
    var i = 0
    val n = line.length
    val sb = StringBuilder(n)
    var inString = false
    while (i < n) {
      if (inComment) {
        if (i + 1 < n && line[i] == '*' && line[i + 1] == '/') {
          inComment = false
          sb.append("  ")
          i += 2
        } else {
          sb.append(' ')
          i++
        }
      } else {
        val c = line[i]
        if (c == '"' && (i == 0 || line[i - 1] != '\\')) {
          inString = !inString
          sb.append(c)
          i++
        } else if (!inString && c == '/' && i + 1 < n && line[i + 1] == '/') {
          sb.append(line.substring(i))
          break
        } else if (!inString && c == '/' && i + 1 < n && line[i + 1] == '*') {
          inComment = true
          sb.append("  ")
          i += 2
        } else {
          sb.append(c)
          i++
        }
      }
    }
    val effective = sb.toString()
    return Triple(effective, startedInComment && effective.isBlank(), inComment)
  }

  private fun stripSingleLineComments(line: String): String {
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
      } else if (!inString && c == '/' && i + 1 < n && line[i + 1] == '/') {
        break
      } else if (!inString && c == '/' && i + 1 < n && line[i + 1] == '*') {
        val close = line.indexOf("*/", i + 2)
        if (close >= 0) {
          sb.append(' ')
          i = close + 2
        } else {
          break
        }
      } else {
        sb.append(c)
        i++
      }
    }
    return sb.toString()
  }

  private fun extractMacroIdent(raw: String): String =
    stripSingleLineComments(raw).trim().takeWhile { it.isLetterOrDigit() || it == '_' }

  private fun parseDirectiveWord(trimmedLine: String): String {
    var i = 1
    while (i < trimmedLine.length && (trimmedLine[i].isLetterOrDigit() || trimmedLine[i] == '_')) {
      i++
    }
    return trimmedLine.substring(0, i)
  }

  private fun findMatchingParen(s: String, openIdx: Int): Int {
    var depth = 0
    var inString = false
    for (i in openIdx until s.length) {
      val c = s[i]
      if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
        inString = !inString
      } else if (!inString) {
        if (c == '(') depth++
        else if (c == ')') {
          depth--
          if (depth == 0) return i
        }
      }
    }
    return -1
  }

  private fun splitMacroArgs(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val args = mutableListOf<String>()
    val cur = StringBuilder()
    var paren = 0
    var bracket = 0
    var brace = 0
    var inString = false
    for (i in raw.indices) {
      val c = raw[i]
      if (c == '"' && (i == 0 || raw[i - 1] != '\\')) {
        inString = !inString
        cur.append(c)
      } else if (!inString) {
        when (c) {
          '(' -> { paren++; cur.append(c) }
          ')' -> { paren = (paren - 1).coerceAtLeast(0); cur.append(c) }
          '[' -> { bracket++; cur.append(c) }
          ']' -> { bracket = (bracket - 1).coerceAtLeast(0); cur.append(c) }
          '{' -> { brace++; cur.append(c) }
          '}' -> { brace = (brace - 1).coerceAtLeast(0); cur.append(c) }
          ',' -> if (paren == 0 && bracket == 0 && brace == 0) {
            args += cur.toString().trim()
            cur.clear()
          } else {
            cur.append(c)
          }
          else -> cur.append(c)
        }
      } else {
        cur.append(c)
      }
    }
    args += cur.toString().trim()
    return args
  }

  private fun substituteFunctionMacroBody(
    body: String,
    params: List<String>,
    args: List<String>,
  ): String {
    var out = body
    params.forEachIndexed { idx, pName ->
      val argVal = args.getOrNull(idx) ?: ""
      out = out.replace(Regex("""(?<![A-Za-z0-9_$])${Regex.escape(pName)}(?![A-Za-z0-9_$])""")) {
        Regex.escapeReplacement(argVal)
      }
    }
    return out
  }

  private fun expandMacrosInLine(
    line: String,
    macros: Map<String, MacroDef>,
    diagnostics: MutableList<String>,
    lineNo: Int,
  ): String {
    if ('`' !in line) return line
    var current = line
    repeat(10) {
      if ('`' !in current) return current
      val next = substituteOnce(current, macros, diagnostics, lineNo)
      if (next == current) return current
      current = next
    }
    return current
  }

  private fun substituteOnce(
    line: String,
    macros: Map<String, MacroDef>,
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
      if (!inString && c == '/' && i + 1 < n && line[i + 1] == '*') {
        val close = line.indexOf("*/", i + 2)
        if (close >= 0) {
          sb.append(line.substring(i, close + 2))
          i = close + 2
          continue
        } else {
          sb.append(line.substring(i))
          break
        }
      }
      if (!inString && c == '`' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_')) {
        var j = i + 1
        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
        val name = line.substring(i + 1, j)
        val macro = macros[name]
        if (macro != null) {
          if (macro.params != null) {
            var argStart = j
            while (argStart < n && line[argStart].isWhitespace()) argStart++
            if (argStart < n && line[argStart] == '(') {
              val closeIdx = findMatchingParen(line, argStart)
              if (closeIdx > argStart) {
                val args = splitMacroArgs(line.substring(argStart + 1, closeIdx))
                sb.append(substituteFunctionMacroBody(macro.body, macro.params, args))
                i = closeIdx + 1
                continue
              }
            }
          }
          sb.append(macro.body)
          i = j
          continue
        } else {
          // Handle common built-in/EDA macros quietly if left undefined, otherwise warn
          if (name == "__LINE__") {
            sb.append(lineNo.toString())
          } else {
            diagnostics += "Line $lineNo: Undefined macro `$name."
            sb.append("0")
          }
          i = j
          continue
        }
      }
      sb.append(c)
      i++
    }
    return sb.toString()
  }
}
