package com.example.ui.screens.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.RtlPort
import com.example.domain.synth.SdcParser
import com.example.domain.synth.StaticTimingAnalyzer
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.TimingAnalysisStage
import com.example.domain.synth.TimingPathDetail
import com.example.domain.synth.TimingPathType
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.StaTimingTableView
import com.example.ui.components.TimingReportFixAdvisorCard
import com.example.ui.components.TimingViolationRemediationCard
import com.example.ui.components.VisualSdcEditorCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class PathBrowserFilter(val label: String) {
  ALL("All"),
  SETUP("Setup"),
  HOLD("Hold"),
  VIOLATING("Violating"),
}

private enum class PathBrowserSort(val label: String) {
  SLACK("Slack ↑"),
  ARRIVAL("Delay ↓"),
  LEVELS("Levels ↓"),
}

@Composable
fun TimingAnalysisTab(
  report: SynthesisReport,
  layout: PhysicalLayoutSnapshot?,
  activePathId: String?,
  onSelectActivePathId: (String?) -> Unit,
  onInspectNode: (String) -> Unit,
  onViewPathInSchematic: (TimingPathDetail) -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  modifier: Modifier = Modifier,
  onApplyClockPeriod: (Double) -> Unit = {},
  onApplyOptimizationGoal: (OptimizationGoal) -> Unit = {},
  onApplySynthesisEngine: (SynthesisEngineProfile) -> Unit = {},
  onApplyTargetLibrary: (TargetLibrary) -> Unit = {},
  activeRtlSource: String = "",
  onNotify: (String) -> Unit = {},
  sdcText: String = "",
  ports: List<RtlPort> = emptyList(),
  onUpdateSdc: (String) -> Unit = {},
  onResetSdc: () -> Unit = {},
) {
  val eda = LocalEdaColors.current
  val summary = remember(report, layout) { StaticTimingAnalyzer.analyze(report, layout) }
  val remediationPlan = remember(report, summary) {
    StaticTimingAnalyzer.buildViolationRemediation(report, summary)
  }

  var pathFilter by rememberSaveable { mutableStateOf(PathBrowserFilter.ALL) }
  var pathSort by rememberSaveable { mutableStateOf(PathBrowserSort.SLACK) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var expandedStageId by rememberSaveable { mutableStateOf<String?>(null) }
  var showInlineSdcEditor by rememberSaveable { mutableStateOf(false) }

  val filteredPaths = remember(summary.paths, pathFilter, pathSort, searchQuery) {
    val q = searchQuery.trim().lowercase(Locale.ROOT)
    val base = summary.paths.filter { path ->
      val typeOk = when (pathFilter) {
        PathBrowserFilter.ALL -> true
        PathBrowserFilter.SETUP -> path.pathType == TimingPathType.SETUP
        PathBrowserFilter.HOLD -> path.pathType == TimingPathType.HOLD
        PathBrowserFilter.VIOLATING -> !path.isFalsePath && path.slackNs < 0.0
      }
      val queryOk = q.isEmpty() ||
        path.startpoint.lowercase(Locale.ROOT).contains(q) ||
        path.endpoint.lowercase(Locale.ROOT).contains(q) ||
        path.stages.any { it.instanceName.lowercase(Locale.ROOT).contains(q) }
      typeOk && queryOk
    }
    when (pathSort) {
      PathBrowserSort.SLACK -> base.sortedBy { it.slackNs }
      PathBrowserSort.ARRIVAL -> base.sortedByDescending { it.dataArrivalNs }
      PathBrowserSort.LEVELS -> base.sortedByDescending { it.logicLevels }
    }
  }

  val selectedPath = remember(summary.paths, filteredPaths, activePathId) {
    summary.paths.firstOrNull { it.pathId == activePathId }
      ?: filteredPaths.firstOrNull()
      ?: summary.paths.firstOrNull()
  }

  LaunchedEffect(selectedPath?.pathId) {
    if (selectedPath != null && activePathId == null) {
      onSelectActivePathId(selectedPath.pathId)
    }
  }

  val setupSlack = summary.setupWnsNs
  val timingMet = setupSlack != null && setupSlack >= 0.0
  val statusPill = when {
    !summary.isAvailable -> Triple("UNAVAILABLE", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    setupSlack != null && setupSlack < 0.0 -> Triple("VIOLATED", eda.error, eda.errorContainer)
    summary.isEstimated -> Triple("ESTIMATED", eda.warning, eda.warningContainer)
    else -> Triple("MET", eda.success, eda.successContainer)
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("analysis_timing_list"),
    contentPadding = PaddingValues(bottom = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 1. STA Timing Summary (Tempus / PrimeTime inspired)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_timing_summary_card")) {
        Column {
          EdaPanelHeader(
            title = "Static Timing Analysis (STA)",
            subtitle = "${summary.stage.label} · ${report.targetLibrary.shortName}",
            icon = Icons.Rounded.Timeline,
            trailing = {
              EdaStatusPill(
                text = statusPill.first,
                color = statusPill.second,
                containerColor = statusPill.third,
                showDot = true,
              )
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Clock Period",
                value = "${"%.2f".format(Locale.US, report.clockPeriodNs)} ns",
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Arrival Time",
                value = summary.arrivalTimeNs?.let { "${"%.3f".format(Locale.US, it)} ns" } ?: "N/A",
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Required Time",
                value = summary.requiredTimeNs?.let { "${"%.3f".format(Locale.US, it)} ns" } ?: "N/A",
                modifier = Modifier.weight(1f),
              )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Setup Slack (WNS)",
                value = setupSlack?.let { formatSignedNs(it) } ?: "N/A",
                valueColor = when {
                  setupSlack == null -> MaterialTheme.colorScheme.onSurfaceVariant
                  timingMet -> eda.success
                  else -> eda.error
                },
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Hold Slack",
                value = summary.holdWnsNs?.let { formatSignedNs(it) } ?: "N/A (pre-CTS)",
                valueColor = when {
                  summary.holdWnsNs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                  summary.holdWnsNs >= 0.0 -> eda.success
                  else -> eda.error
                },
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Est. Max Freq",
                value = summary.estimatedFmaxMhz?.let { "${"%.1f".format(Locale.US, it)} MHz" } ?: "N/A",
                valueColor = if (summary.estimatedFmaxMhz != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Setup TNS",
                value = summary.setupTnsNs?.let { "${"%.3f".format(Locale.US, it)} ns" } ?: "N/A",
                valueColor = if ((summary.setupTnsNs ?: 0.0) < 0.0) eda.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Violating Paths",
                value = "${summary.violatingSetupPaths + summary.violatingHoldPaths}",
                valueColor = if (summary.violatingSetupPaths + summary.violatingHoldPaths > 0) eda.error else eda.success,
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Timing Stage",
                value = summary.stage.badge,
                valueColor = if (summary.stage == TimingAnalysisStage.POST_ROUTE) eda.success else eda.warning,
                modifier = Modifier.weight(1f),
              )
            }
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
            EdaKeyValueRow(
              label = "Analysis Corner / Confidence",
              value = when (summary.stage) {
                TimingAnalysisStage.PRE_LAYOUT_POST_SYNTH -> "Pre-layout gate STA (wire load estimated; hold pending CTS)"
                TimingAnalysisStage.POST_PLACEMENT -> "Post-placement Steiner estimate (pre-detail route)"
                TimingAnalysisStage.POST_ROUTE -> "Post-route & Post-CTS skew=${"%.3f".format(Locale.US, summary.clockSkewNs ?: 0.0)} ns"
                TimingAnalysisStage.UNAVAILABLE -> "Unavailable — synthesize non-empty cells"
              },
            )
            if (summary.clockInsertionNs != null) {
              EdaKeyValueRow("CTS Insertion / Skew", "${"%.3f".format(Locale.US, summary.clockInsertionNs)} ns / ${"%.3f".format(Locale.US, summary.clockSkewNs ?: 0.0)} ns")
            }
            if (report.maxOutputLoadPf > 0.0) {
              EdaKeyValueRow(
                "Output Pin Load",
                "${"%.2f".format(Locale.US, report.maxOutputLoadPf)} pF (+${"%.3f".format(Locale.US, SdcParser.outputLoadDelayNs(report.maxOutputLoadPf, report.targetLibrary))} ns RC)",
              )
            }
          }
        }
      }
    }

    // 1b. Actionable Timing-Closure Plan when Timing Shows VIOLATED
    if (remediationPlan != null) {
      val worstViolatingPath = summary.paths.firstOrNull { !it.isFalsePath && it.slackNs < 0.0 } ?: selectedPath
      item {
        TimingViolationRemediationCard(
          plan = remediationPlan,
          onApplyClockPeriod = onApplyClockPeriod,
          onApplyOptimizationGoal = onApplyOptimizationGoal,
          onApplySynthesisEngine = onApplySynthesisEngine,
          onApplyTargetLibrary = onApplyTargetLibrary,
          onJumpToRtlLine = onJumpToRtlLine,
          onInspectWorstPath = worstViolatingPath?.let { path -> { onViewPathInSchematic(path) } },
        )
      }
    }

    // 1c. Timing Report Parser & Verilog RTL Fix Advisor (works for active STA or pasted OpenSTA/PrimeTime reports)
    item {
      TimingReportFixAdvisorCard(
        report = report,
        summary = summary,
        layout = layout,
        activeRtlSource = activeRtlSource,
        onJumpToRtlLine = onJumpToRtlLine,
        onNotify = onNotify,
      )
    }

    // 1d. Visual SDC Constraint Editor inside STA Tab
    item {
      val effectivePorts = remember(ports, report.ports) { ports.ifEmpty { report.ports } }
      val effectiveSdc = remember(sdcText, report) {
        sdcText.ifBlank {
          SdcParser.generateDefaultSdc(report.moduleName, effectivePorts, report.clockPeriodNs)
        }
      }
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("timing_tab_sdc_card")) {
        Column {
          Surface(
            onClick = { showInlineSdcEditor = !showInlineSdcEditor },
            color = eda.elevatedCard,
            modifier = Modifier.fillMaxWidth().testTag("timing_tab_sdc_toggle"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Column {
                  Text("Visual SDC Constraints Editor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                  Text(
                    text = report.sdcSummary.ifBlank { "Define clock frequency, input delays & output load (pF)" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              Icon(
                imageVector = if (showInlineSdcEditor) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (showInlineSdcEditor) "Collapse SDC constraints" else "Expand SDC constraints",
              )
            }
          }
          AnimatedVisibility(visible = showInlineSdcEditor) {
            VisualSdcEditorCard(
              sdcText = effectiveSdc,
              clockPeriodNs = report.clockPeriodNs,
              ports = effectivePorts,
              targetLibrary = report.targetLibrary,
              moduleName = report.moduleName,
              onUpdateSdc = onUpdateSdc,
              onResetSdc = onResetSdc,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    }

    // 2. Timing Visualizations (Cell vs Net Delay, Budget Comparison, Slack Distribution)
    if (selectedPath != null) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_timing_visual_card")) {
          Column {
            EdaPanelHeader(
              title = "Timing Delay & Slack Visualizations",
              subtitle = "${selectedPath.startpoint} → ${selectedPath.endpoint}",
              icon = Icons.Rounded.BarChart,
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text("Cell Delay vs Net Delay (${selectedPath.pathType.label})", style = MaterialTheme.typography.labelMedium)
              EdaStackedRatioBar(
                segments = listOf(
                  Triple("Cell (${"%.3f".format(Locale.US, selectedPath.totalCellDelayNs)}ns)", selectedPath.totalCellDelayNs, MaterialTheme.colorScheme.primary),
                  Triple("Net (${"%.3f".format(Locale.US, selectedPath.totalNetDelayNs)}ns)", selectedPath.totalNetDelayNs, eda.cyanHighlight),
                ),
              )

              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
              Text("Timing Budget vs Clock Period (${"%.2f".format(Locale.US, report.clockPeriodNs)} ns)", style = MaterialTheme.typography.labelMedium)
              val budgetDenom = maxOf(report.clockPeriodNs, selectedPath.dataArrivalNs, 0.1)
              val arrivalFrac = (selectedPath.dataArrivalNs / budgetDenom).toFloat().coerceIn(0.02f, 1f)
              val reqFrac = (selectedPath.requiredTimeNs / budgetDenom).toFloat().coerceIn(0.02f, 1f)
              val barColor = if (selectedPath.slackNs >= 0.0) eda.success else eda.error
              val reqLineColor = eda.warning
              Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                  drawRect(color = eda.subtleBorder.copy(alpha = 0.28f), size = size)
                  drawRect(color = barColor.copy(alpha = 0.85f), size = Size(size.width * arrivalFrac, size.height))
                  val rx = size.width * reqFrac
                  drawLine(color = reqLineColor, start = Offset(rx, 0f), end = Offset(rx, size.height), strokeWidth = 3f)
                }
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                  text = "Arrival ${"%.3f".format(Locale.US, selectedPath.dataArrivalNs)} ns",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                )
                Text(
                  text = "Req ${"%.3f".format(Locale.US, selectedPath.requiredTimeNs)} ns (${formatSignedNs(selectedPath.slackNs)})",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = barColor,
                )
              }

              val buckets = remember(summary.paths) { StaticTimingAnalyzer.slackHistogramBuckets(summary.paths, 6) }
              if (buckets.isNotEmpty()) {
                HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
                Text("Path Slack Distribution (${summary.paths.size} paths)", style = MaterialTheme.typography.labelMedium)
                val maxBucket = (buckets.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                val histBarColor = MaterialTheme.colorScheme.primary
                val negBarColor = eda.error
                Row(
                  modifier = Modifier.fillMaxWidth().height(54.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.Bottom,
                ) {
                  buckets.forEach { (loLabel, count) ->
                    val frac = (count.toFloat() / maxBucket).coerceAtLeast(if (count > 0) 0.12f else 0.03f)
                    val isNeg = loLabel.startsWith("-")
                    Column(
                      modifier = Modifier.weight(1f),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.Bottom,
                    ) {
                      Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
                        val h = size.height * frac
                        drawRect(
                          color = if (isNeg) negBarColor else histBarColor,
                          topLeft = Offset(0f, size.height - h),
                          size = Size(size.width, h),
                        )
                      }
                      Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // 3. Timing Path Browser
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_timing_path_browser")) {
        Column {
          EdaPanelHeader(
            title = "Timing Path Browser",
            subtitle = "${filteredPaths.size} matching path(s) · Tap to trace",
            icon = Icons.Rounded.Search,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("Search startpoint, endpoint, or cell...") },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("sta_path_search_input"),
            )
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              PathBrowserFilter.entries.forEach { f ->
                FilterChip(
                  selected = pathFilter == f,
                  onClick = { pathFilter = f },
                  label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("sta_path_filter_${f.name.lowercase()}"),
                )
              }
              PathBrowserSort.entries.forEach { s ->
                FilterChip(
                  selected = pathSort == s,
                  onClick = { pathSort = s },
                  label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("sta_path_sort_${s.name.lowercase()}"),
                )
              }
            }

            if (filteredPaths.isEmpty()) {
              Text(
                text = if (pathFilter == PathBrowserFilter.HOLD && layout == null) {
                  "Hold paths are unavailable until Clock Tree Synthesis / PnR runs."
                } else {
                  "No timing paths match the current filter."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              filteredPaths.take(10).forEach { path ->
                val isSelected = selectedPath?.pathId == path.pathId
                val pColor = when {
                  path.isFalsePath -> MaterialTheme.colorScheme.tertiary
                  path.slackNs < 0.0 -> eda.error
                  else -> eda.success
                }
                Surface(
                  onClick = { onSelectActivePathId(path.pathId) },
                  color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else eda.elevatedCard,
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else eda.subtleBorder.copy(alpha = 0.55f),
                  ),
                  modifier = Modifier.fillMaxWidth().testTag("sta_path_item_${path.pathId}"),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                      Text(
                        text = "#${path.pathIndex} [${path.pathType.label}] ${path.startpoint} → ${path.endpoint}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Text(
                        text = "${path.logicLevels} levels · Arr ${"%.3f".format(Locale.US, path.dataArrivalNs)} ns · Req ${"%.3f".format(Locale.US, path.requiredTimeNs)} ns",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                      )
                    }
                    Text(
                      text = if (path.isFalsePath) "EXEMPT" else formatSignedNs(path.slackNs),
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold),
                      color = pColor,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // 4. Critical / Selected Path Trace + View in Schematic Action
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_critical_path_card")) {
        Column {
          EdaPanelHeader(
            title = if (selectedPath != null) "Path Trace · #${selectedPath.pathIndex} (${selectedPath.pathType.label})" else "Critical Path Trace",
            subtitle = selectedPath?.let { "${it.stages.size} stage(s) · ${it.startpoint} → ${it.endpoint}" }
              ?: "${report.criticalPath.size} stage(s)",
            icon = Icons.Rounded.Speed,
            trailing = {
              if (selectedPath != null) {
                Button(
                  onClick = { onViewPathInSchematic(selectedPath) },
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                  modifier = Modifier.heightIn(min = 48.dp).testTag("analysis_view_in_schematic_button"),
                ) {
                  Icon(Icons.Rounded.AccountTree, contentDescription = "View selected path in schematic", modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("View in Schematic", style = MaterialTheme.typography.labelSmall)
                }
              }
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            val stages = selectedPath?.stages.orEmpty()
            if (stages.isEmpty()) {
              Text(
                text = "No combinational or sequential timing path stages reported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              stages.forEach { stage ->
                val stageKey = "${selectedPath?.pathId}_${stage.stageIndex}_${stage.nodeId}"
                val expanded = expandedStageId == stageKey
                Surface(
                  onClick = { expandedStageId = if (expanded) null else stageKey },
                  color = eda.elevatedCard,
                  shape = MaterialTheme.shapes.small,
                  border = BorderStroke(
                    1.dp,
                    if (selectedPath?.slackNs ?: 0.0 < 0.0) eda.error.copy(alpha = 0.45f) else eda.subtleBorder.copy(alpha = 0.65f),
                  ),
                  modifier = Modifier.fillMaxWidth().testTag("sta_stage_${stage.stageIndex}"),
                ) {
                  Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                          text = "#${stage.stageIndex} ${stage.instanceName} (${stage.cellName})",
                          style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                          color = MaterialTheme.colorScheme.onSurface,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                          text = "${stage.inputPin} → ${stage.outputPin} · net ${stage.netName}",
                          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      }
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(horizontalAlignment = Alignment.End) {
                          Text(
                            text = "+${"%.3f".format(Locale.US, stage.incrementalDelayNs)} ns",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = eda.warning,
                          )
                          Text(
                            text = "Σ ${"%.3f".format(Locale.US, stage.arrivalTimeNs)} ns",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold),
                            color = if (timingMet) eda.cyanHighlight else eda.error,
                          )
                        }
                        Icon(
                          imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                          contentDescription = if (expanded) "Collapse stage" else "Expand stage",
                          modifier = Modifier.size(16.dp),
                        )
                      }
                    }

                    AnimatedVisibility(visible = expanded) {
                      Column(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))
                        EdaKeyValueRow("Cell Delay / Net Delay", "${"%.3f".format(Locale.US, stage.cellDelayNs)} ns / ${"%.3f".format(Locale.US, stage.netDelayNs)} ns")
                        EdaKeyValueRow("Cumulative Arrival / Fanout", "${"%.3f".format(Locale.US, stage.cumulativeDelayNs)} ns · FO=${stage.fanout}")
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                          TextButton(
                            onClick = { onInspectNode(stage.nodeId) },
                            modifier = Modifier.heightIn(min = 48.dp),
                          ) {
                            Text("Focus Cell in Schematic", style = MaterialTheme.typography.labelSmall)
                          }
                          if (stage.sourceLine > 0) {
                            TextButton(
                              onClick = { onJumpToRtlLine(stage.sourceLine) },
                              modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                              Text("RTL Line ${stage.sourceLine} ↗", style = MaterialTheme.typography.labelSmall)
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // 5. Full Interactive STA Cell Table
    item {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(420.dp),
      ) {
        StaTimingTableView(
          report = report,
          onInspectNode = onInspectNode,
          onJumpToRtlLine = onJumpToRtlLine,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
