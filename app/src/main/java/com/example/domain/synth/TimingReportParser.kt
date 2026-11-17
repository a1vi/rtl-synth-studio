package com.example.domain.synth

import java.util.Locale
import kotlin.math.abs

enum class ParsedViolationType(val label: String) {
  SETUP("Setup Violation"),
  HOLD("Hold Violation"),
}

enum class VerilogFixCategory(val badge: String, val label: String) {
  PIPELINE_INSERTION("PIPELINE", "Pipeline Stage Insertion"),
  LOGIC_RESTRUCTURING("RESTRUCTURE", "Logic Restructuring"),
  MUX_PARALLELIZATION("MUX OPT", "Mux / Late-Signal Optimization"),
  HOLD_BUFFER_INSERTION("HOLD BUF", "Hold Delay Buffer Chain"),
  CLOCK_ENABLE_ALIGNMENT("CLK SAFE", "Synchronous Enable & Hold Margin"),
}

data class ParsedTimingStage(
  val instanceName: String,
  val cellName: String,
  val netName: String,
  val incrDelayNs: Double,
  val arrivalNs: Double,
  val sourceLine: Int = 0,
)

data class ParsedTimingPath(
  val pathIndex: Int,
  val violationType: ParsedViolationType,
  val startpoint: String,
  val endpoint: String,
  val clockName: String,
  val clockPeriodNs: Double?,
  val arrivalTimeNs: Double?,
  val requiredTimeNs: Double?,
  val slackNs: Double,
  val isViolated: Boolean,
  val stages: List<ParsedTimingStage>,
) {
  val bottleneckStage: ParsedTimingStage?
    get() = stages
      .filterNot { isSequentialCellName(it.cellName) }
      .maxByOrNull { it.incrDelayNs }
      ?: stages.maxByOrNull { it.incrDelayNs }
}

data class VerilogFixSuggestion(
  val id: String,
  val category: VerilogFixCategory,
  val title: String,
  val issueType: ParsedViolationType,
  val pathReference: String,
  val estimatedTimingGain: String,
  val rationale: String,
  val beforeSnippet: String,
  val verilogFixSnippet: String,
  val targetLineHint: Int = 0,
)

data class ParsedTimingReportResult(
  val detectedFormat: String,
  val moduleName: String?,
  val paths: List<ParsedTimingPath>,
  val violatingPaths: List<ParsedTimingPath>,
  val worstSetupSlackNs: Double?,
  val worstHoldSlackNs: Double?,
  val suggestions: List<VerilogFixSuggestion>,
  val parseNotice: String? = null,
)

private fun isSequentialCellName(cellName: String): Boolean {
  val up = cellName.uppercase(Locale.ROOT)
  return "DFF" in up || "FDRE" in up || "SDFF" in up || "LATCH" in up || "REG" in up
}

object TimingReportParser {

  val SAMPLE_SETUP_REPORT: String = """
    Startpoint: if_id_instr_q_reg[15] (rising edge-triggered flip-flop clocked by clk)
    Endpoint: ex_mem_alu_res_q_reg[31] (rising edge-triggered flip-flop clocked by clk)
    Path Group: clk
    Path Type: max

      Point                                    Incr       Path
      -----------------------------------------------------------
      clock clk (rise edge)                    0.000      0.000
      if_id_instr_q_reg[15]/Q (DFFR_X1)        0.145      0.145 r
      u_alu/u_mul_pipe/Y (MUL_X2)              0.860      1.005 r
      u_alu/u_adder_32/S[31] (ADD_X4)          0.540      1.545 r
      u_alu/u_cmp_branch/Y (CMP_X2)            0.310      1.855 r
      u_wb_mux/Y (MUX2_X2)                     0.195      2.050 r
      ex_mem_alu_res_q_reg[31]/D (DFFR_X1)     0.000      2.050 r
      data arrival time                                   2.050

      clock clk (rise edge)                    1.600      1.600
      clock uncertainty                       -0.050      1.550
      library setup time                      -0.045      1.505
      data required time                                  1.505
      -----------------------------------------------------------
      data required time                                  1.505
      data arrival time                                  -2.050
      -----------------------------------------------------------
      slack (VIOLATED)                                   -0.545
  """.trimIndent()

  val SAMPLE_HOLD_REPORT: String = """
    Startpoint: ch0_src_q_reg[0] (rising edge-triggered flip-flop clocked by clk)
    Endpoint: ch0_shadow_q_reg[0] (rising edge-triggered flip-flop clocked by clk)
    Path Group: clk
    Path Type: min

      Point                                    Incr       Path
      -----------------------------------------------------------
      clock clk (rise edge)                    0.000      0.000
      clock network delay (propagated)         0.110      0.110
      ch0_src_q_reg[0]/Q (DFFR_X1)             0.055      0.165 r
      u_fast_route/Y (BUF_X1)                  0.025      0.190 r
      ch0_shadow_q_reg[0]/D (DFFR_X1)          0.000      0.190 r
      data arrival time                                   0.190

      clock clk (rise edge)                    0.000      0.000
      clock network delay (propagated)         0.245      0.245
      clock uncertainty                        0.030      0.275
      library hold time                        0.035      0.310
      data required time                                  0.310
      -----------------------------------------------------------
      data required time                                  0.310
      data arrival time                                  -0.190
      -----------------------------------------------------------
      slack (VIOLATED)                                   -0.120
  """.trimIndent()

  fun formatFromSynthesis(
    report: SynthesisReport,
    summary: StaticTimingSummary,
    layout: PhysicalLayoutSnapshot? = null,
  ): String = buildString {
    val violating = summary.paths.filter { !it.isFalsePath && it.slackNs < 0.0 }
    val pathsToFormat = if (violating.isNotEmpty()) violating.take(3) else summary.paths.take(2)
    appendLine("=== STATIC TIMING ANALYSIS REPORT (${report.moduleName}) ===")
    appendLine("Clock Period     : ${"%.3f".format(Locale.US, report.clockPeriodNs)} ns")
    appendLine("Setup WNS / TNS  : ${"%.3f".format(Locale.US, report.wnsNs)} ns / ${"%.3f".format(Locale.US, report.tnsNs)} ns")
    if (layout != null) {
      appendLine("Hold WNS         : ${"%.3f".format(Locale.US, layout.holdWnsNs)} ns")
      appendLine("Clock Skew       : ${"%.3f".format(Locale.US, layout.clockSkewNs)} ns")
    }
    appendLine()
    pathsToFormat.forEach { path ->
      val pathTypeStr = if (path.pathType == TimingPathType.HOLD) "min" else "max"
      appendLine("Startpoint: ${path.startpoint}")
      appendLine("Endpoint: ${path.endpoint}")
      appendLine("Path Group: ${path.endpointClock}")
      appendLine("Path Type: $pathTypeStr")
      appendLine("  Point                                    Incr       Path")
      appendLine("  -----------------------------------------------------------")
      path.stages.forEach { st ->
        val lineTag = if (st.sourceLine > 0) " (L${st.sourceLine})" else ""
        appendLine("  ${st.instanceName}/${st.outputPin} (${st.cellName})$lineTag   ${"%.3f".format(Locale.US, st.incrementalDelayNs)}   ${"%.3f".format(Locale.US, st.arrivalTimeNs)} r")
      }
      appendLine("  data arrival time                                  ${"%.3f".format(Locale.US, path.dataArrivalNs)}")
      appendLine("  data required time                                 ${"%.3f".format(Locale.US, path.requiredTimeNs)}")
      val verdict = if (path.slackNs < 0.0) "VIOLATED" else "MET"
      appendLine("  slack ($verdict)                                   ${"%.3f".format(Locale.US, path.slackNs)}")
      appendLine()
    }
  }.trimEnd()

  fun parseAndSuggestFixes(
    reportText: String,
    activeRtlSource: String = "",
  ): ParsedTimingReportResult {
    val trimmed = reportText.trim()
    if (trimmed.isBlank()) {
      return ParsedTimingReportResult(
        detectedFormat = "Empty",
        moduleName = null,
        paths = emptyList(),
        violatingPaths = emptyList(),
        worstSetupSlackNs = null,
        worstHoldSlackNs = null,
        suggestions = emptyList(),
        parseNotice = "Paste an OpenSTA, PrimeTime, Tempus, or SiliconFlow timing report or load the active design report.",
      )
    }

    val lines = trimmed.lines()
    val moduleRegex = Regex("""(?:STATIC TIMING ANALYSIS REPORT\s*\(|Module\s*:\s*)([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
    val detectedModule = lines.firstNotNullOfOrNull { moduleRegex.find(it)?.groupValues?.getOrNull(1) }

    val hasStartpointBlocks = lines.any { it.trimStart().startsWith("Startpoint:", ignoreCase = true) }
    val isSiliconSummaryFormat = !hasStartpointBlocks &&
      lines.any { it.contains("Setup WNS", ignoreCase = true) || it.contains("Critical Path Stages", ignoreCase = true) || it.contains("Critical Timing Path", ignoreCase = true) }

    val parsedPaths = if (hasStartpointBlocks) {
      parseStartpointReport(lines)
    } else if (isSiliconSummaryFormat) {
      parseSiliconSummaryReport(lines, detectedModule)
    } else {
      parseFallbackSingleBlock(lines, detectedModule)
    }

    val detectedFormat = when {
      hasStartpointBlocks && isSiliconSummaryFormat -> "SiliconFlow + OpenSTA"
      hasStartpointBlocks -> "OpenSTA / PrimeTime / Tempus"
      isSiliconSummaryFormat -> "SiliconFlow STA Report"
      else -> "Generic Timing Report"
    }

    val violating = parsedPaths.filter { it.isViolated || it.slackNs < 0.0 }
    val worstSetup = parsedPaths
      .filter { it.violationType == ParsedViolationType.SETUP }
      .minOfOrNull { it.slackNs }
    val worstHold = parsedPaths
      .filter { it.violationType == ParsedViolationType.HOLD }
      .minOfOrNull { it.slackNs }

    val suggestions = violating.flatMap { path ->
      generateSuggestionsForPath(path, activeRtlSource)
    }.distinctBy { "${it.category}_${it.pathReference}_${it.title}" }

    val notice = when {
      parsedPaths.isEmpty() -> "Could not detect timing paths. Expected Startpoint/Endpoint, data arrival/required time, or slack lines."
      violating.isEmpty() -> "Parsed ${parsedPaths.size} timing path(s) — all paths show MET slack (no setup or hold violations)."
      else -> null
    }

    return ParsedTimingReportResult(
      detectedFormat = detectedFormat,
      moduleName = detectedModule,
      paths = parsedPaths,
      violatingPaths = violating,
      worstSetupSlackNs = worstSetup,
      worstHoldSlackNs = worstHold,
      suggestions = suggestions,
      parseNotice = notice,
    )
  }

  private fun parseStartpointReport(lines: List<String>): List<ParsedTimingPath> {
    val blocks = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    for (line in lines) {
      if (line.trimStart().startsWith("Startpoint:", ignoreCase = true) && current.isNotEmpty()) {
        blocks += current
        current = mutableListOf()
      }
      current += line
    }
    if (current.isNotEmpty()) blocks += current

    val riseEdgeRegex = Regex("""clock\s+\S+\s+\(rise edge\)\s+([-+]?\d+(?:\.\d+)?)\s+([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val arrivalRegex = Regex("""data arrival time\s+([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val requiredRegex = Regex("""data required time\s+([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val slackRegex = Regex("""slack\s*\((VIOLATED|MET)[^)]*\)\s*:?\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val genericSlackRegex = Regex("""^\s*slack\s*:?\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val stageParenRegex = Regex("""^\s*([^\s(]+)\s+\(([^)]+)\)(?:\s+\(L(\d+)\))?\s+([-+]?\d+(?:\.\d+)?)\s+([-+]?\d+(?:\.\d+)?)""")
    val periodLineRegex = Regex("""Clock\s+(?:Period|Target)\s*:\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    val globalPeriod = lines.firstNotNullOfOrNull { periodLineRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }

    return blocks.mapIndexedNotNull { idx, blk ->
      var startpoint = "in_reg"
      var endpoint = "out_reg"
      var clockName = "clk"
      var pathType = ParsedViolationType.SETUP
      var clockPeriod: Double? = globalPeriod
      var arrival: Double? = null
      var required: Double? = null
      var slack: Double? = null
      var violatedFlag: Boolean? = null
      val stages = mutableListOf<ParsedTimingStage>()

      for (raw in blk) {
        val line = raw.trim()
        when {
          line.startsWith("Startpoint:", ignoreCase = true) -> {
            startpoint = line.substringAfter(":").trim().substringBefore(" (").trim().ifBlank { "in_reg" }
            val clkMatch = Regex("""clocked by\s+([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE).find(line)
            if (clkMatch != null) clockName = clkMatch.groupValues[1]
          }
          line.startsWith("Endpoint:", ignoreCase = true) -> {
            endpoint = line.substringAfter(":").trim().substringBefore(" (").trim().ifBlank { "out_reg" }
            val clkMatch = Regex("""clocked by\s+([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE).find(line)
            if (clkMatch != null) clockName = clkMatch.groupValues[1]
          }
          line.startsWith("Path Group:", ignoreCase = true) -> {
            clockName = line.substringAfter(":").trim().ifBlank { clockName }
          }
          line.startsWith("Path Type:", ignoreCase = true) -> {
            val pt = line.substringAfter(":").trim().lowercase(Locale.ROOT)
            pathType = if ("min" in pt || "hold" in pt) ParsedViolationType.HOLD else ParsedViolationType.SETUP
          }
          else -> {
            riseEdgeRegex.find(line)?.let { m ->
              val edgeVal = m.groupValues[1].toDoubleOrNull()
              if (edgeVal != null && edgeVal > 0.0) clockPeriod = edgeVal
            }
            arrivalRegex.find(line)?.let { m ->
              arrival = abs(m.groupValues[1].toDoubleOrNull() ?: 0.0)
            }
            requiredRegex.find(line)?.let { m ->
              required = m.groupValues[1].toDoubleOrNull()
            }
            slackRegex.find(line)?.let { m ->
              violatedFlag = m.groupValues[1].equals("VIOLATED", ignoreCase = true)
              slack = m.groupValues[2].toDoubleOrNull()
            } ?: genericSlackRegex.find(line)?.let { m ->
              slack = m.groupValues[1].toDoubleOrNull()
            }
            stageParenRegex.find(line)?.let { m ->
              val instFull = m.groupValues[1]
              val cell = m.groupValues[2]
              val lineNum = m.groupValues[3].toIntOrNull() ?: 0
              val incr = m.groupValues[4].toDoubleOrNull() ?: 0.0
              val arr = m.groupValues[5].toDoubleOrNull() ?: 0.0
              if (!cell.equals("net", ignoreCase = true)) {
                val instClean = instFull.substringBeforeLast('/')
                stages += ParsedTimingStage(
                  instanceName = instClean.ifBlank { instFull },
                  cellName = cell,
                  netName = instFull.substringAfterLast('/', instClean),
                  incrDelayNs = incr,
                  arrivalNs = arr,
                  sourceLine = lineNum,
                )
              }
            }
          }
        }
      }

      val resolvedSlack = slack ?: if (required != null && arrival != null) required!! - arrival!! else null
      if (resolvedSlack == null && stages.isEmpty()) return@mapIndexedNotNull null
      val finalSlack = resolvedSlack ?: 0.0
      ParsedTimingPath(
        pathIndex = idx + 1,
        violationType = pathType,
        startpoint = startpoint,
        endpoint = endpoint,
        clockName = clockName,
        clockPeriodNs = clockPeriod,
        arrivalTimeNs = arrival,
        requiredTimeNs = required,
        slackNs = finalSlack,
        isViolated = violatedFlag ?: (finalSlack < 0.0),
        stages = stages,
      )
    }
  }

  private fun parseSiliconSummaryReport(
    lines: List<String>,
    moduleName: String?,
  ): List<ParsedTimingPath> {
    val period = lines.firstNotNullOfOrNull {
      Regex("""Clock\s+(?:Period|Target)\s*:\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
    val arrival = lines.firstNotNullOfOrNull {
      Regex("""(?:Arrival Time|Critical Path)\s*:\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
    val setupWns = lines.firstNotNullOfOrNull {
      Regex("""Setup WNS(?:\s*/\s*TNS)?\s*:\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
    val holdWns = lines.firstNotNullOfOrNull {
      Regex("""Hold WNS\s*:\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    val stageArrowRegex = Regex("""^\s*\d+\.\s+(\S+)\s+\[([^\]]+)]\s*->\s*(\S+)\s*\|\s*\+?([-+]?\d+(?:\.\d+)?)\s*ns\s*\|\s*arrival\s+([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    val stageBracketRegex = Regex("""^\s*\[\d+]\s+(\S+)\s+\(([^)]+)\)\s*->\s*net=(\S+)\s*\+?([-+]?\d+(?:\.\d+)?)ns\s*@\s*([-+]?\d+(?:\.\d+)?)ns(?:\s*\(L(\d+)\))?""", RegexOption.IGNORE_CASE)

    val stages = lines.mapNotNull { line ->
      stageArrowRegex.find(line)?.let { m ->
        ParsedTimingStage(
          instanceName = m.groupValues[1],
          cellName = m.groupValues[2],
          netName = m.groupValues[3],
          incrDelayNs = m.groupValues[4].toDoubleOrNull() ?: 0.0,
          arrivalNs = m.groupValues[5].toDoubleOrNull() ?: 0.0,
        )
      } ?: stageBracketRegex.find(line)?.let { m ->
        ParsedTimingStage(
          instanceName = m.groupValues[1],
          cellName = m.groupValues[2],
          netName = m.groupValues[3],
          incrDelayNs = m.groupValues[4].toDoubleOrNull() ?: 0.0,
          arrivalNs = m.groupValues[5].toDoubleOrNull() ?: 0.0,
          sourceLine = m.groupValues[6].toIntOrNull() ?: 0,
        )
      }
    }

    val startPt = stages.firstOrNull()?.instanceName ?: "${moduleName ?: "rtl"}_in_q"
    val endPt = stages.lastOrNull()?.instanceName ?: "${moduleName ?: "rtl"}_out_q"
    val out = mutableListOf<ParsedTimingPath>()

    if (setupWns != null) {
      val req = if (arrival != null) arrival + setupWns else period
      out += ParsedTimingPath(
        pathIndex = 1,
        violationType = ParsedViolationType.SETUP,
        startpoint = startPt,
        endpoint = endPt,
        clockName = "clk",
        clockPeriodNs = period,
        arrivalTimeNs = arrival,
        requiredTimeNs = req,
        slackNs = setupWns,
        isViolated = setupWns < 0.0,
        stages = stages,
      )
    }
    if (holdWns != null && holdWns < 0.0) {
      out += ParsedTimingPath(
        pathIndex = out.size + 1,
        violationType = ParsedViolationType.HOLD,
        startpoint = startPt,
        endpoint = endPt,
        clockName = "clk",
        clockPeriodNs = period,
        arrivalTimeNs = null,
        requiredTimeNs = null,
        slackNs = holdWns,
        isViolated = true,
        stages = stages,
      )
    }
    return out
  }

  private fun parseFallbackSingleBlock(
    lines: List<String>,
    moduleName: String?,
  ): List<ParsedTimingPath> {
    val slackMatch = lines.firstNotNullOfOrNull {
      Regex("""slack(?:\s*\([^)]+\))?\s*:?\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(it)
    } ?: return emptyList()
    val slackVal = slackMatch.groupValues[1].toDoubleOrNull() ?: return emptyList()
    val isHold = lines.any { Regex("""\bhold\b|\bmin\s+path\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    val isViol = lines.any { it.contains("VIOLATED", ignoreCase = true) } || slackVal < 0.0
    return listOf(
      ParsedTimingPath(
        pathIndex = 1,
        violationType = if (isHold) ParsedViolationType.HOLD else ParsedViolationType.SETUP,
        startpoint = "${moduleName ?: "src"}_reg",
        endpoint = "${moduleName ?: "dst"}_reg",
        clockName = "clk",
        clockPeriodNs = null,
        arrivalTimeNs = null,
        requiredTimeNs = null,
        slackNs = slackVal,
        isViolated = isViol,
        stages = emptyList(),
      )
    )
  }

  private fun generateSuggestionsForPath(
    path: ParsedTimingPath,
    activeRtlSource: String,
  ): List<VerilogFixSuggestion> {
    val suggestions = mutableListOf<VerilogFixSuggestion>()
    val clk = sanitizeIdent(path.clockName, "clk")
    val srcSig = sanitizeIdent(path.startpoint.substringBefore('[').substringAfterLast('/'), "stage0_q")
    val dstSig = sanitizeIdent(path.endpoint.substringBefore('[').substringAfterLast('/'), "stage1_q")
    val bottleneck = path.bottleneckStage
    val bottleneckCell = bottleneck?.cellName ?: "COMB"
    val bottleneckLine = bottleneck?.sourceLine ?: 0
    val pathRef = "${path.startpoint} → ${path.endpoint}"
    val slackStr = "${"%.3f".format(Locale.US, path.slackNs)} ns"
    val contextFromRtl = extractRtlSnippetNearLine(activeRtlSource, bottleneckLine)

    if (path.violationType == ParsedViolationType.SETUP) {
      val halfDelayEst = path.arrivalTimeNs?.let { "${"%.2f".format(Locale.US, it * 0.52)} ns" } ?: "~50% delay cut"
      val midReg = "${dstSig}_pipe_q"
      val pipeStageVerilog = """
        |// Insert 1 pipeline register stage to split $bottleneckCell critical path
        |reg [31:0] $midReg;
        |wire [31:0] ${srcSig}_comb_mid;
        |
        |// Stage 1: first half of combinational logic (before $midReg)
        |assign ${srcSig}_comb_mid = /* first-half logic from $srcSig */ ;
        |
        |always @(posedge $clk or negedge rst_n) begin
        |  if (!rst_n) begin
        |    $midReg <= 32'd0;
        |  end else begin
        |    $midReg <= ${srcSig}_comb_mid;
        |  end
        |end
        |
        |// Stage 2: remaining logic feeds destination register
        |always @(posedge $clk or negedge rst_n) begin
        |  if (!rst_n) begin
        |    $dstSig <= 32'd0;
        |  end else begin
        |    $dstSig <= /* second-half logic using $midReg */ ;
        |  end
        |end
      """.trimMargin()

      suggestions += VerilogFixSuggestion(
        id = "fix_pipe_${path.pathIndex}",
        category = VerilogFixCategory.PIPELINE_INSERTION,
        title = "Insert Pipeline Register Stage Before $dstSig",
        issueType = ParsedViolationType.SETUP,
        pathReference = pathRef,
        estimatedTimingGain = "-$halfDelayEst (~2x Fmax)",
        rationale = "Setup slack is $slackStr along $pathRef (bottleneck: ${bottleneck?.instanceName ?: "comb cone"} [$bottleneckCell]). Registering the midpoint breaks the combinational cone across two clock cycles.",
        beforeSnippet = contextFromRtl ?: """
          |// Single-cycle deep cone violates setup ($slackStr)
          |always @(posedge $clk) begin
          |  $dstSig <= op_stage3(op_stage2(op_stage1($srcSig)));
          |end
        """.trimMargin(),
        verilogFixSnippet = pipeStageVerilog,
        targetLineHint = bottleneckLine,
      )

      val hasArith = path.stages.any { st ->
        val c = st.cellName.uppercase(Locale.ROOT)
        "MUL" in c || "ADD" in c || "SUB" in c || "CMP" in c || "CARRY" in c
      }
      val hasMux = path.stages.any { st ->
        "MUX" in st.cellName.uppercase(Locale.ROOT)
      }

      if (hasArith || path.stages.isEmpty()) {
        suggestions += VerilogFixSuggestion(
          id = "fix_tree_${path.pathIndex}",
          category = VerilogFixCategory.LOGIC_RESTRUCTURING,
          title = "Restructure Arithmetic Chain into Balanced Tree",
          issueType = ParsedViolationType.SETUP,
          pathReference = pathRef,
          estimatedTimingGain = "~25–35% cone depth cut",
          rationale = "Arithmetic cells ($bottleneckCell) create linear carry/multiplier propagation on $pathRef. Parenthesizing into a balanced binary tree (or pre-computing partial sums) trims critical levels without adding cycle latency.",
          beforeSnippet = """
            |// Linear chained expression (depth = O(N))
            |wire [31:0] result_w = op_a + op_b + op_c + op_d;
          """.trimMargin(),
          verilogFixSnippet = """
            |// Balanced parallel reduction tree (depth = O(log2 N), 0 extra cycles)
            |wire [31:0] sum_ab_w = op_a + op_b;
            |wire [31:0] sum_cd_w = op_c + op_d;
            |wire [31:0] result_w = sum_ab_w + sum_cd_w;
          """.trimMargin(),
          targetLineHint = bottleneckLine,
        )
      }

      if (hasMux || !hasArith) {
        suggestions += VerilogFixSuggestion(
          id = "fix_mux_${path.pathIndex}",
          category = VerilogFixCategory.MUX_PARALLELIZATION,
          title = "Pre-Compute Branches & Flatten Priority Mux",
          issueType = ParsedViolationType.SETUP,
          pathReference = pathRef,
          estimatedTimingGain = "~15–30% setup recovery",
          rationale = "Nested if/else chains force late-arriving control signals through cascaded muxes before $dstSig. Computing candidate branches in parallel and selecting late reduces critical path levels.",
          beforeSnippet = """
            |// Late select inside priority if-else chain
            |always @(*) begin
            |  if (sel_a)      mux_out = data_a + offset;
            |  else if (sel_b) mux_out = data_b + offset;
            |  else            mux_out = data_c;
            |end
          """.trimMargin(),
          verilogFixSnippet = """
            |// Speculative parallel branch computation + flat final mux
            |wire [31:0] branch_a_w = data_a + offset;
            |wire [31:0] branch_b_w = data_b + offset;
            |
            |always @(*) begin
            |  casez ({sel_a, sel_b})
            |    2'b1?:   mux_out = branch_a_w;
            |    2'b01:   mux_out = branch_b_w;
            |    default: mux_out = data_c;
            |  endcase
            |end
          """.trimMargin(),
          targetLineHint = bottleneckLine,
        )
      }
    } else {
      // Hold Violation Suggestions
      val delayNeededNs = abs(path.slackNs) + 0.04
      val stagesNeeded = ((delayNeededNs / 0.045).toInt()).coerceIn(2, 6)
      val dlyNet = "${srcSig}_hold_dly"
      val dlyChainCode = buildString {
        appendLine("// Insert explicit hold-delay buffer chain (~${"%.2f".format(Locale.US, delayNeededNs)} ns, $stagesNeeded stages)")
        appendLine("(* dont_touch = \"true\" *) wire [31:0] ${dlyNet}_s0;")
        appendLine("assign ${dlyNet}_s0 = $srcSig;")
        for (i in 1..stagesNeeded) {
          val prev = "${dlyNet}_s${i - 1}"
          val cur = if (i == stagesNeeded) "${dstSig}_hold_safe" else "${dlyNet}_s$i"
          appendLine("(* keep = \"true\", dont_touch = \"true\" *) wire [31:0] $cur;")
          appendLine("assign $cur = $prev; // Buffer stage $i prevents $slackStr hold race")
        }
        appendLine()
        appendLine("always @(posedge $clk or negedge rst_n) begin")
        appendLine("  if (!rst_n) $dstSig <= 32'd0;")
        appendLine("  else        $dstSig <= ${dstSig}_hold_safe;")
        append("end")
      }

      suggestions += VerilogFixSuggestion(
        id = "fix_hold_buf_${path.pathIndex}",
        category = VerilogFixCategory.HOLD_BUFFER_INSERTION,
        title = "Add Minimum-Delay Buffer Chain Before $dstSig",
        issueType = ParsedViolationType.HOLD,
        pathReference = pathRef,
        estimatedTimingGain = "+${"%.2f".format(Locale.US, delayNeededNs)} ns hold margin",
        rationale = "Hold slack is $slackStr on short register-to-register path $pathRef (clock skew exceeds fast data arrival). Keeping a $stagesNeeded-buffer delay path guarantees D stays stable past capture clock hold window.",
        beforeSnippet = """
          |// Direct back-to-back flop transfer races against clock skew ($slackStr)
          |always @(posedge $clk) begin
          |  $dstSig <= $srcSig;
          |end
        """.trimMargin(),
        verilogFixSnippet = dlyChainCode,
        targetLineHint = bottleneckLine,
      )

      suggestions += VerilogFixSuggestion(
        id = "fix_hold_en_${path.pathIndex}",
        category = VerilogFixCategory.CLOCK_ENABLE_ALIGNMENT,
        title = "Replace Gated Clock with Synchronous Enable & SDC Hold Margin",
        issueType = ParsedViolationType.HOLD,
        pathReference = pathRef,
        estimatedTimingGain = "Eliminates local clock skew",
        rationale = "Locally gated clocks add insertion skew that triggers hold failures between $srcSig and $dstSig. Use an un-gated clock with synchronous enable plus explicit CTS hold uncertainty.",
        beforeSnippet = """
          |// Risky derived/gated clock adds skew between launch & capture flops
          |wire local_clk = $clk & path_en;
          |always @(posedge local_clk) $dstSig <= $srcSig;
        """.trimMargin(),
        verilogFixSnippet = """
          |// Glitch-free synchronous clock enable (zero clock-tree skew)
          |always @(posedge $clk or negedge rst_n) begin
          |  if (!rst_n)       $dstSig <= 32'd0;
          |  else if (path_en) $dstSig <= $srcSig;
          |end
          |
          |// Matching SDC hold safeguard:
          |// set_clock_uncertainty -hold 0.050 [get_clocks $clk]
        """.trimMargin(),
        targetLineHint = bottleneckLine,
      )
    }

    return suggestions
  }

  private fun sanitizeIdent(raw: String, fallback: String): String {
    val cleaned = raw
      .replace("_reg", "")
      .replace(Regex("""[^A-Za-z0-9_]"""), "_")
      .trim('_')
    if (cleaned.isBlank()) return fallback
    return if (cleaned.first().isDigit()) "sig_$cleaned" else cleaned
  }

  private fun extractRtlSnippetNearLine(rtlSource: String, line: Int): String? {
    if (rtlSource.isBlank() || line <= 0) return null
    val allLines = rtlSource.lines()
    if (line > allLines.size) return null
    val startIdx = (line - 2).coerceAtLeast(0)
    val endIdx = (line + 1).coerceAtMost(allLines.lastIndex)
    return (startIdx..endIdx).joinToString("\n") { idx ->
      "L${idx + 1}: ${allLines[idx]}"
    }
  }
}
