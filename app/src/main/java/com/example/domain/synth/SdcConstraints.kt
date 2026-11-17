package com.example.domain.synth

import com.example.data.model.TargetLibrary
import java.util.Locale

data class SdcClock(
  val name: String,
  val periodNs: Double,
  val portName: String?,
) {
  val frequencyMhz: Double
    get() = if (periodNs > 0.0) 1000.0 / periodNs else 0.0
}

data class SdcIoDelay(
  val portPattern: String,
  val clockName: String?,
  val delayNs: Double,
  val isInput: Boolean,
)

data class SdcOutputLoad(
  val portPattern: String,
  val loadPf: Double,
  val isPinLoad: Boolean = true,
)

enum class SdcExceptionKind {
  FALSE_PATH,
  MAX_DELAY,
}

data class SdcTimingException(
  val kind: SdcExceptionKind,
  val fromPattern: String?,
  val toPattern: String?,
  val maxDelayNs: Double? = null,
)

data class SdcConstraintSet(
  val rawText: String,
  val clocks: List<SdcClock>,
  val ioDelays: List<SdcIoDelay>,
  val exceptions: List<SdcTimingException>,
  val clockUncertaintyNs: Double,
  val diagnostics: List<String>,
  val outputLoads: List<SdcOutputLoad> = emptyList(),
) {
  val primaryClockPeriodNs: Double?
    get() = clocks.firstOrNull()?.periodNs

  fun inputDelayForPort(portName: String): Double {
    val specific = ioDelays.lastOrNull { it.isInput && matchesPattern(portName, it.portPattern) }
    return specific?.delayNs ?: 0.0
  }

  fun outputDelayForPort(portName: String): Double {
    val specific = ioDelays.lastOrNull { !it.isInput && matchesPattern(portName, it.portPattern) }
    return specific?.delayNs ?: 0.0
  }

  fun outputLoadForPort(portName: String): Double {
    val specific = outputLoads.lastOrNull { matchesPattern(portName, it.portPattern) }
    return specific?.loadPf ?: 0.0
  }

  fun isFalsePathEndpoint(endpointName: String, launchCandidates: List<String> = emptyList()): Boolean {
    return exceptions.any { ex ->
      if (ex.kind != SdcExceptionKind.FALSE_PATH) return@any false
      val toMatches = ex.toPattern == null || matchesPattern(endpointName, ex.toPattern)
      val fromMatches = ex.fromPattern == null ||
        launchCandidates.any { matchesPattern(it, ex.fromPattern) } ||
        matchesPattern(endpointName, ex.fromPattern)
      toMatches && fromMatches
    }
  }

  fun maxDelayOverrideFor(endpointName: String, launchCandidates: List<String> = emptyList()): Double? {
    return exceptions.lastOrNull { ex ->
      if (ex.kind != SdcExceptionKind.MAX_DELAY || ex.maxDelayNs == null) return@lastOrNull false
      val toMatches = ex.toPattern == null || matchesPattern(endpointName, ex.toPattern)
      val fromMatches = ex.fromPattern == null ||
        launchCandidates.any { matchesPattern(it, ex.fromPattern) }
      toMatches && fromMatches
    }?.maxDelayNs
  }

  val totalConstraintCount: Int
    get() = clocks.size + ioDelays.size + outputLoads.size + exceptions.size +
      (if (clockUncertaintyNs > 0.0) 1 else 0)

  fun summaryLine(): String {
    if (totalConstraintCount == 0) return "No custom SDC constraints"
    val parts = mutableListOf<String>()
    if (clocks.isNotEmpty()) {
      parts += clocks.joinToString(", ") {
        "${it.name}:${String.format(Locale.US, "%.2f", it.periodNs)}ns (${String.format(Locale.US, "%.0f", it.frequencyMhz)}MHz)"
      }
    }
    val inCount = ioDelays.count { it.isInput }
    val outCount = ioDelays.count { !it.isInput }
    if (inCount > 0 || outCount > 0) parts += "${inCount}in/${outCount}out delays"
    if (outputLoads.isNotEmpty()) {
      val maxLoad = outputLoads.maxOf { it.loadPf }
      parts += "${outputLoads.size} load (${String.format(Locale.US, "%.2f", maxLoad)}pF)"
    }
    if (clockUncertaintyNs > 0.0) parts += "uncert=${String.format(Locale.US, "%.2f", clockUncertaintyNs)}ns"
    if (exceptions.isNotEmpty()) parts += "${exceptions.size} path exc"
    return parts.joinToString(" · ")
  }

  companion object {
    fun matchesPattern(target: String, pattern: String): Boolean {
      val clean = pattern.trim()
      if (clean == "*" || clean == "all_inputs" || clean == "all_outputs") return true
      val regexStr = buildString {
        append('^')
        clean.forEach { ch ->
          when (ch) {
            '*' -> append(".*")
            '?' -> append('.')
            else -> append(Regex.escape(ch.toString()))
          }
        }
        append('$')
      }
      return Regex(regexStr, RegexOption.IGNORE_CASE).matches(target)
    }
  }
}

/**
 * Synopsys Design Constraints (SDC) parser, serializer & default template generator.
 * Supports:
 * - create_clock -name <clk> -period <ns> [get_ports <port>]
 * - set_input_delay -clock <clk> <ns> [get_ports <pat>]
 * - set_output_delay -clock <clk> <ns> [get_ports <pat>]
 * - set_load [-pin_load|-wire_load] <pF> [get_ports <pat>]
 * - set_clock_uncertainty <ns> [get_clocks ...]
 * - set_false_path [-from <pat>] [-to <pat>]
 * - set_max_delay <ns> [-from <pat>] [-to <pat>]
 */
object SdcParser {

  /**
   * Computes output buffer RC transition delay (ns) for a capacitive load in picofarads (pF)
   * calibrated per target process node / FPGA I/O driver strength.
   */
  fun outputLoadDelayNs(loadPf: Double, library: TargetLibrary): Double {
    if (loadPf <= 0.0) return 0.0
    val nsPerPf = when (library) {
      TargetLibrary.ASAP7_7NM -> 0.28
      TargetLibrary.ARTIX7_FPGA -> 0.34
      TargetLibrary.NANGATE45_45NM -> 0.38
      TargetLibrary.SKY130_130NM -> 0.52
      TargetLibrary.GF180MCU_180NM -> 0.68
    }
    return (loadPf.coerceIn(0.0, 100.0) * nsPerPf)
  }

  fun parse(sdcText: String, fallbackClockNs: Double): SdcConstraintSet {
    if (sdcText.isBlank()) {
      return SdcConstraintSet(
        rawText = sdcText,
        clocks = emptyList(),
        ioDelays = emptyList(),
        exceptions = emptyList(),
        clockUncertaintyNs = 0.0,
        diagnostics = emptyList(),
        outputLoads = emptyList(),
      )
    }

    val clocks = mutableListOf<SdcClock>()
    val ioDelays = mutableListOf<SdcIoDelay>()
    val outputLoads = mutableListOf<SdcOutputLoad>()
    val exceptions = mutableListOf<SdcTimingException>()
    var uncertainty = 0.0
    val diagnostics = mutableListOf<String>()

    // Join backslash-continued lines
    val normalizedLines = mutableListOf<Pair<Int, String>>()
    val rawLines = sdcText.lines()
    var buffer = StringBuilder()
    var startLine = 1
    rawLines.forEachIndexed { idx, line ->
      val stripped = line.substringBefore('#').trim()
      if (stripped.endsWith("\\")) {
        if (buffer.isEmpty()) startLine = idx + 1
        buffer.append(stripped.dropLast(1)).append(' ')
      } else {
        if (buffer.isEmpty()) startLine = idx + 1
        buffer.append(stripped)
        val combined = buffer.toString().trim()
        if (combined.isNotEmpty()) {
          normalizedLines += startLine to combined
        }
        buffer = StringBuilder()
      }
    }
    if (buffer.isNotEmpty()) {
      normalizedLines += startLine to buffer.toString().trim()
    }

    normalizedLines.forEach { (lineNo, stmt) ->
      val tokens = tokenizeSdc(stmt)
      if (tokens.isEmpty()) return@forEach
      when (tokens.first().lowercase(Locale.ROOT)) {
        "create_clock" -> {
          var name: String? = null
          var period: Double? = null
          var port: String? = null
          var i = 1
          while (i < tokens.size) {
            when (tokens[i]) {
              "-name" -> {
                name = tokens.getOrNull(i + 1)
                i += 2
              }
              "-period" -> {
                period = tokens.getOrNull(i + 1)?.toDoubleOrNull()
                i += 2
              }
              "-waveform" -> i += 2
              else -> {
                val extracted = extractTargetPattern(tokens[i])
                if (extracted != null && !tokens[i].startsWith("-")) {
                  port = extracted
                }
                i++
              }
            }
          }
          val resolvedPeriod = (period ?: fallbackClockNs).coerceIn(0.2, 500.0)
          val resolvedName = name ?: port ?: "clk_${clocks.size + 1}"
          clocks += SdcClock(resolvedName, resolvedPeriod, port)
        }

        "set_input_delay", "set_output_delay" -> {
          val isInput = tokens.first().equals("set_input_delay", ignoreCase = true)
          var clkName: String? = null
          var delay: Double? = null
          var targetPat: String? = null
          var i = 1
          while (i < tokens.size) {
            val tok = tokens[i]
            when {
              tok == "-clock" -> {
                clkName = extractTargetPattern(tokens.getOrNull(i + 1) ?: "") ?: tokens.getOrNull(i + 1)
                i += 2
              }
              tok.startsWith("-") -> {
                if (tok in setOf("-max", "-min", "-rise", "-fall", "-add_delay")) {
                  i++
                } else {
                  i += 2
                }
              }
              delay == null && tok.toDoubleOrNull() != null -> {
                delay = tok.toDoubleOrNull()
                i++
              }
              else -> {
                targetPat = extractTargetPattern(tok) ?: tok
                i++
              }
            }
          }
          if (delay == null) {
            diagnostics += "SDC line $lineNo: Missing numeric delay value in ${tokens.first()}."
          } else {
            ioDelays += SdcIoDelay(
              portPattern = targetPat ?: "*",
              clockName = clkName,
              delayNs = delay.coerceIn(0.0, 200.0),
              isInput = isInput,
            )
          }
        }

        "set_load" -> {
          var loadPf: Double? = null
          var targetPat: String? = null
          var isPinLoad = true
          var i = 1
          while (i < tokens.size) {
            val tok = tokens[i]
            when {
              tok == "-pin_load" -> {
                isPinLoad = true
                i++
              }
              tok == "-wire_load" -> {
                isPinLoad = false
                i++
              }
              tok.startsWith("-") -> {
                if (tok in setOf("-max", "-min", "-rise", "-fall", "-subtract_pin_load")) {
                  i++
                } else {
                  i += 2
                }
              }
              loadPf == null && tok.toDoubleOrNull() != null -> {
                loadPf = tok.toDoubleOrNull()
                i++
              }
              else -> {
                targetPat = extractTargetPattern(tok) ?: tok
                i++
              }
            }
          }
          if (loadPf == null) {
            diagnostics += "SDC line $lineNo: Missing numeric capacitance value in set_load."
          } else {
            outputLoads += SdcOutputLoad(
              portPattern = targetPat ?: "*",
              loadPf = loadPf.coerceIn(0.0, 100.0),
              isPinLoad = isPinLoad,
            )
          }
        }

        "set_clock_uncertainty" -> {
          val valNum = tokens.drop(1).firstOrNull { !it.startsWith("-") && it.toDoubleOrNull() != null }?.toDoubleOrNull()
          if (valNum != null) {
            uncertainty = valNum.coerceIn(0.0, 50.0)
          } else {
            diagnostics += "SDC line $lineNo: Missing uncertainty value."
          }
        }

        "set_false_path" -> {
          var fromPat: String? = null
          var toPat: String? = null
          var i = 1
          while (i < tokens.size) {
            when (tokens[i]) {
              "-from" -> {
                fromPat = extractTargetPattern(tokens.getOrNull(i + 1) ?: "*")
                i += 2
              }
              "-to" -> {
                toPat = extractTargetPattern(tokens.getOrNull(i + 1) ?: "*")
                i += 2
              }
              "-through" -> i += 2
              else -> i++
            }
          }
          exceptions += SdcTimingException(
            kind = SdcExceptionKind.FALSE_PATH,
            fromPattern = fromPat,
            toPattern = toPat,
          )
        }

        "set_max_delay" -> {
          var maxD: Double? = null
          var fromPat: String? = null
          var toPat: String? = null
          var i = 1
          while (i < tokens.size) {
            when {
              tokens[i] == "-from" -> {
                fromPat = extractTargetPattern(tokens.getOrNull(i + 1) ?: "*")
                i += 2
              }
              tokens[i] == "-to" -> {
                toPat = extractTargetPattern(tokens.getOrNull(i + 1) ?: "*")
                i += 2
              }
              maxD == null && tokens[i].toDoubleOrNull() != null -> {
                maxD = tokens[i].toDoubleOrNull()
                i++
              }
              else -> i++
            }
          }
          if (maxD != null) {
            exceptions += SdcTimingException(
              kind = SdcExceptionKind.MAX_DELAY,
              fromPattern = fromPat,
              toPattern = toPat,
              maxDelayNs = maxD.coerceIn(0.05, 500.0),
            )
          } else {
            diagnostics += "SDC line $lineNo: Missing delay value in set_max_delay."
          }
        }

        "set_units", "current_design" -> Unit

        else -> {
          diagnostics += "SDC line $lineNo: Unsupported command '${tokens.first()}' ignored."
        }
      }
    }

    return SdcConstraintSet(
      rawText = sdcText,
      clocks = clocks,
      ioDelays = ioDelays,
      exceptions = exceptions,
      clockUncertaintyNs = uncertainty,
      diagnostics = diagnostics,
      outputLoads = outputLoads,
    )
  }

  fun serialize(
    clocks: List<SdcClock>,
    ioDelays: List<SdcIoDelay>,
    outputLoads: List<SdcOutputLoad>,
    clockUncertaintyNs: Double,
    exceptions: List<SdcTimingException> = emptyList(),
    moduleName: String? = null,
  ): String = buildString {
    if (!moduleName.isNullOrBlank()) {
      appendLine("# SDC Constraints for $moduleName")
    }
    clocks.forEach { clk ->
      val period = String.format(Locale.US, "%.2f", clk.periodNs.coerceIn(0.2, 500.0))
      val portTarget = clk.portName?.takeIf { it.isNotBlank() } ?: clk.name
      appendLine("create_clock -name ${clk.name} -period $period [get_ports $portTarget]")
    }
    if (clockUncertaintyNs > 0.0) {
      val uncert = String.format(Locale.US, "%.2f", clockUncertaintyNs.coerceIn(0.0, 50.0))
      val primaryName = clocks.firstOrNull()?.name ?: "*"
      appendLine("set_clock_uncertainty $uncert [get_clocks $primaryName]")
    }
    val defaultClk = clocks.firstOrNull()?.name ?: "clk"
    ioDelays.filter { it.isInput }.forEach { io ->
      val clkRef = io.clockName?.takeIf { it.isNotBlank() } ?: defaultClk
      val d = String.format(Locale.US, "%.2f", io.delayNs.coerceIn(0.0, 200.0))
      appendLine("set_input_delay -clock $clkRef $d [get_ports ${io.portPattern.ifBlank { "*" }}]")
    }
    ioDelays.filter { !it.isInput }.forEach { io ->
      val clkRef = io.clockName?.takeIf { it.isNotBlank() } ?: defaultClk
      val d = String.format(Locale.US, "%.2f", io.delayNs.coerceIn(0.0, 200.0))
      appendLine("set_output_delay -clock $clkRef $d [get_ports ${io.portPattern.ifBlank { "*" }}]")
    }
    outputLoads.forEach { load ->
      val pf = String.format(Locale.US, "%.2f", load.loadPf.coerceIn(0.0, 100.0))
      val pat = load.portPattern.ifBlank { "*" }
      val flag = if (load.isPinLoad) "-pin_load " else "-wire_load "
      appendLine("set_load $flag$pf [get_ports $pat]")
    }
    exceptions.forEach { ex ->
      when (ex.kind) {
        SdcExceptionKind.FALSE_PATH -> {
          val fromStr = ex.fromPattern?.let { " -from [get_ports $it]" } ?: ""
          val toStr = ex.toPattern?.let { " -to [get_ports $it]" } ?: ""
          appendLine("set_false_path$fromStr$toStr")
        }
        SdcExceptionKind.MAX_DELAY -> {
          val maxStr = String.format(Locale.US, "%.2f", (ex.maxDelayNs ?: 2.0).coerceIn(0.05, 500.0))
          val fromStr = ex.fromPattern?.let { " -from [get_ports $it]" } ?: ""
          val toStr = ex.toPattern?.let { " -to [get_ports $it]" } ?: ""
          appendLine("set_max_delay $maxStr$fromStr$toStr")
        }
      }
    }
  }.trimEnd()

  fun generateDefaultSdc(
    moduleName: String,
    ports: List<RtlPort>,
    clockPeriodNs: Double,
  ): String {
    val clkPort = ports.firstOrNull {
      it.direction == PortDirection.INPUT &&
        (it.name.equals("clk", ignoreCase = true) ||
          it.name.equals("clock", ignoreCase = true) ||
          it.name.lowercase(Locale.ROOT).contains("clk"))
    }?.name ?: "clk"
    val formattedPeriod = String.format(Locale.US, "%.2f", clockPeriodNs.coerceIn(0.2, 500.0))
    val inDelay = String.format(Locale.US, "%.2f", (clockPeriodNs * 0.12).coerceIn(0.05, 5.0))
    val outDelay = String.format(Locale.US, "%.2f", (clockPeriodNs * 0.12).coerceIn(0.05, 5.0))
    val uncertainty = String.format(Locale.US, "%.2f", (clockPeriodNs * 0.04).coerceIn(0.02, 1.0))

    val dataInputs = ports.filter {
      it.direction == PortDirection.INPUT && it.name != clkPort
    }.map { it.name }
    val outputs = ports.filter { it.direction == PortDirection.OUTPUT }.map { it.name }

    return buildString {
      appendLine("# SDC Constraints for $moduleName")
      appendLine("create_clock -name $clkPort -period $formattedPeriod [get_ports $clkPort]")
      appendLine("set_clock_uncertainty $uncertainty [get_clocks $clkPort]")
      if (dataInputs.isNotEmpty()) {
        appendLine("set_input_delay -clock $clkPort $inDelay [get_ports *]")
      }
      if (outputs.isNotEmpty()) {
        appendLine("set_output_delay -clock $clkPort $outDelay [get_ports *]")
        appendLine("set_load -pin_load 0.15 [get_ports *]")
      }
    }.trimEnd()
  }

  fun updateClockPeriodInSdc(sdcText: String, newPeriodNs: Double): String {
    if (sdcText.isBlank()) return sdcText
    val formatted = String.format(Locale.US, "%.2f", newPeriodNs.coerceIn(0.2, 500.0))
    val regex = Regex("""(create_clock\b[^\n]*?-period\s+)([0-9]+(?:\.[0-9]+)?)""")
    return if (regex.containsMatchIn(sdcText)) {
      var replacedFirst = false
      regex.replace(sdcText) { match ->
        if (!replacedFirst) {
          replacedFirst = true
          "${match.groupValues[1]}$formatted"
        } else {
          match.value
        }
      }
    } else {
      "create_clock -name clk -period $formatted [get_ports clk]\n$sdcText"
    }
  }

  private fun tokenizeSdc(stmt: String): List<String> {
    val tokens = mutableListOf<String>()
    var i = 0
    val n = stmt.length
    while (i < n) {
      while (i < n && stmt[i].isWhitespace()) i++
      if (i >= n) break
      when (stmt[i]) {
        '[' -> {
          val start = i++
          var depth = 1
          while (i < n && depth > 0) {
            if (stmt[i] == '[') depth++
            else if (stmt[i] == ']') depth--
            i++
          }
          tokens += stmt.substring(start, i)
        }
        '{' -> {
          val start = i++
          var depth = 1
          while (i < n && depth > 0) {
            if (stmt[i] == '{') depth++
            else if (stmt[i] == '}') depth--
            i++
          }
          tokens += stmt.substring(start, i)
        }
        '"' -> {
          val start = ++i
          while (i < n && stmt[i] != '"') i++
          tokens += stmt.substring(start, i)
          if (i < n) i++
        }
        else -> {
          val start = i
          while (i < n && !stmt[i].isWhitespace()) i++
          tokens += stmt.substring(start, i)
        }
      }
    }
    return tokens
  }

  private fun extractTargetPattern(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      val inner = trimmed.substring(1, trimmed.length - 1).trim()
      val parts = inner.split(Regex("\\s+"))
      return when {
        parts.firstOrNull() == "all_inputs" -> "*"
        parts.firstOrNull() == "all_outputs" -> "*"
        parts.size >= 2 -> parts.last().trim('{', '}', '"')
        else -> parts.firstOrNull()
      }
    }
    return trimmed.trim('{', '}', '"')
  }
}
