package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings
import com.example.data.model.ExportFormat
import com.example.data.model.RtlLanguage
import com.example.domain.synth.GateType
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaSectionHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.ExportShareHelper
import com.example.ui.components.GdsLayoutViewer
import com.example.ui.components.StaTimingTableView
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.WorkbenchUiState
import java.util.Locale

@Composable
fun ReportsScreen(
  state: WorkbenchUiState,
  settings: AppSettings,
  onSaveSnapshot: () -> Unit,
  onSelectNode: (String?) -> Unit,
  onSetCycleCount: (Int) -> Unit,
  onCycleStimulusPattern: (String) -> Unit,
  onStepCustomCellValue: (String, Int, Long, Int) -> Unit,
  onRunSimulation: () -> Unit,
  onRunTestbenchSimulation: () -> Unit,
  onToggleWaveformSignal: (String) -> Unit,
  onResetStimulus: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var subTab by rememberSaveable { mutableIntStateOf(0) } // 0=Summary, 1=STA, 2=Simulate, 3=Schematic, 4=GDS Layout

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Segmented Sub-View Switcher (Summary / STA / Sim / Sch / GDS)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      val tabs = listOf(
        Triple("Sum", Icons.Rounded.Assessment, "reports_tab_summary"),
        Triple("STA", Icons.Rounded.TableChart, "reports_tab_sta"),
        Triple("Sim", Icons.Rounded.Waves, "reports_tab_simulate"),
        Triple("Sch", Icons.Rounded.AccountTree, "reports_tab_schematic"),
        Triple("GDS", Icons.Rounded.GridView, "reports_tab_layout"),
      )
      tabs.forEachIndexed { idx, (label, icon, tag) ->
        SegmentedButton(
          selected = subTab == idx,
          onClick = { subTab = idx },
          shape = SegmentedButtonDefaults.itemShape(idx, tabs.size),
          icon = {},
          modifier = Modifier.testTag(tag),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }

    val report = (state.synthesisResult as? SynthesisResult.Success)?.report

    when (subTab) {
      0 -> {
        if (report == null) {
          EmptyReportState()
        } else {
          EngineeringSummaryReportView(
            report = report,
            layout = state.physicalLayout,
            settings = settings,
            isSaving = state.isSaving,
            onSaveSnapshot = onSaveSnapshot,
            onJumpToSta = { subTab = 1 },
            onJumpToSchematic = { subTab = 3 },
            onJumpToLayout = { subTab = 4 },
            onNotify = onNotify,
            modifier = Modifier.weight(1f),
          )
        }
      }
      1 -> {
        if (report == null) {
          EmptyReportState()
        } else {
          StaTimingTableView(
            report = report,
            onInspectNode = { nodeId ->
              onSelectNode(nodeId)
              subTab = 3
            },
            modifier = Modifier.weight(1f),
          )
        }
      }
      2 -> {
        val hasTb = state.files.any { it.language == RtlLanguage.TESTBENCH && it.content.isNotBlank() }
        SimulationScreen(
          simulation = state.simulationResult,
          cycleCount = state.simCycleCount,
          stimulusPatterns = state.stimulusPatterns,
          onSetCycleCount = onSetCycleCount,
          onCycleStimulusPattern = onCycleStimulusPattern,
          onStepCustomCellValue = onStepCustomCellValue,
          onRunSimulation = onRunSimulation,
          onResetStimulus = onResetStimulus,
          selectedSignals = state.selectedWaveformSignals,
          onToggleSignal = onToggleWaveformSignal,
          hasTestbench = hasTb,
          onRunTestbench = onRunTestbenchSimulation,
          modifier = Modifier.weight(1f),
        )
      }
      3 -> {
        SchematicScreen(
          synthesisResult = state.synthesisResult,
          selectedNodeId = state.selectedNodeId,
          onSelectNode = onSelectNode,
          onNotifyCopied = onNotify,
          modifier = Modifier.weight(1f),
        )
      }
      4 -> {
        val physicalLayout = state.physicalLayout
        if (physicalLayout == null) {
          EmptyReportState()
        } else {
          GdsLayoutViewer(
            layout = physicalLayout,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyReportState() {
  EdaSurfaceCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Column(
      modifier = Modifier.padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
      Text("No Synthesis Report Available", style = MaterialTheme.typography.titleMedium)
      Text(
        text = "Run a clean RTL-to-GDSII flow from Editor or Synthesis to inspect PPA metrics, STA tables, schematics, and GDSII layout.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun EngineeringSummaryReportView(
  report: SynthesisReport,
  layout: PhysicalLayoutSnapshot?,
  settings: AppSettings,
  isSaving: Boolean,
  onSaveSnapshot: () -> Unit,
  onJumpToSta: () -> Unit,
  onJumpToSchematic: () -> Unit,
  onJumpToLayout: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  var pendingExportContent by remember { mutableStateOf<String?>(null) }

  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri: Uri? ->
    val payload = pendingExportContent
    pendingExportContent = null
    if (uri != null && payload != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(payload.toByteArray(Charsets.UTF_8))
        }
      }.onSuccess {
        onNotify("Exported report successfully.")
      }.onFailure {
        onNotify("Export failed: ${it.localizedMessage ?: "error"}")
      }
    }
  }

  val timingMet = report.wnsNs >= 0.0
  val dffCount = report.nodes.count { it.gateType == GateType.DFF || it.gateType == GateType.DFFR }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("reports_summary_list"),
    contentPadding = PaddingValues(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // 1. Header & Timing Sign-off Banner
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = report.moduleName,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
              )
              Text(
                text = "${report.targetLibrary.displayName} · ${report.engineProfile.shortName} · ${report.synthesisDurationMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            EdaStatusPill(
              text = if (timingMet) "TIMING MET" else "TIMING VIOLATION",
              color = if (timingMet) eda.success else eda.error,
              containerColor = if (timingMet) eda.successContainer else eda.errorContainer,
              showDot = true,
            )
          }

          // 3x2 Structured PPA KPI Grid
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdaCompactMetric(
              label = "Total Cells",
              value = report.cellCount.toString(),
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "Comb / Seq",
              value = "${report.combCellCount} / ${report.seqCellCount}",
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "Flip-Flops",
              value = dffCount.toString(),
              valueColor = eda.cyanHighlight,
              modifier = Modifier.weight(1f),
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdaCompactMetric(
              label = if (report.targetLibrary.isFpga) "Slice Units" else "Area (um²)",
              value = "${"%.2f".format(Locale.US, report.totalAreaUm2)}",
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "Fmax (MHz)",
              value = "${"%.1f".format(Locale.US, report.fmaxMhz)}",
              valueColor = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
            EdaCompactMetric(
              label = "WNS Slack",
              value = "${if (report.wnsNs >= 0) "+" else ""}${"%.2f".format(Locale.US, report.wnsNs)} ns",
              valueColor = if (timingMet) eda.success else eda.error,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    // 2. Charts Card (Cell Distribution + Power Breakdown)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          EdaSectionHeader(
            title = "Cell & Power Distribution",
            subtitle = "Gate-type allocation and dynamic/leakage breakdown",
          )

          // Cell Type Horizontal Bar Chart
          val topEntries = report.gateHistogram.entries.take(6).toList()
          val maxCount = (topEntries.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1)
          val barColors = listOf(
            MaterialTheme.colorScheme.primary,
            eda.cyanHighlight,
            eda.success,
            eda.warning,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            eda.cyanHighlight.copy(alpha = 0.7f),
          )
          topEntries.forEachIndexed { idx, (cellType, count) ->
            val fraction = (count.toFloat() / maxCount).coerceIn(0.05f, 1f)
            val barColor = barColors[idx % barColors.size]
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = cellType,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.width(56.dp),
                maxLines = 1,
              )
              Box(modifier = Modifier.weight(1f).height(10.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                  drawRoundRect(
                    color = eda.subtleBorder.copy(alpha = 0.35f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                  )
                  drawRoundRect(
                    color = barColor,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                  )
                }
              }
              Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
              )
            }
          }

          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))

          // Power Stacked Bar (Dynamic vs Leakage)
          val totalP = report.totalPowerMw.coerceAtLeast(0.0001)
          val dynRatio = (report.dynamicPowerMw / totalP).toFloat().coerceIn(0f, 1f)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Total Power", style = MaterialTheme.typography.labelLarge)
            Text(
              text = "${"%.3f".format(Locale.US, report.totalPowerMw)} mW",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = eda.cyanHighlight,
            )
          }
          val primaryColor = MaterialTheme.colorScheme.primary
          Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            drawRoundRect(
              color = eda.warning,
              cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            drawRoundRect(
              color = primaryColor,
              size = Size(size.width * dynRatio, size.height),
              cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            LegendItem(color = MaterialTheme.colorScheme.primary, label = "Dynamic ${"%.3f".format(Locale.US, report.dynamicPowerMw)} mW")
            LegendItem(color = eda.warning, label = "Leakage ${"%.3f".format(Locale.US, report.leakagePowerMw)} mW")
          }
        }
      }
    }

    // 3. Optimization Statistics & Timing Summary
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          EdaSectionHeader(
            title = "Optimization & Timing Statistics",
            subtitle = "Elaboration compaction, critical path delay, and duration",
          )
          StatRow("Pre-Opt IR Operators", "${report.preOptCellCount}")
          StatRow("Post-Opt Mapped Cells", "${report.postOptCellCount} (-${"%.1f".format(Locale.US, report.optimizationReductionPercent)}%)")
          StatRow("Critical Path Delay", "${"%.3f".format(Locale.US, report.criticalPathDelayNs)} ns")
          StatRow("Total Negative Slack (TNS)", "${"%.3f".format(Locale.US, report.tnsNs)} ns")
          StatRow("Synthesis Duration", "${report.synthesisDurationMs} ms")
          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
          Text(
            text = "Critical Path: ${report.criticalPathSummaryText()}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = onJumpToSta,
              modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) {
              Text("Open STA Table", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
              onClick = onJumpToSchematic,
              modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) {
              Text("Inspect Netlist", style = MaterialTheme.typography.labelLarge)
            }
          }
        }
      }
    }

    // 3b. Physical Implementation & Sign-off Card (CTS / Route / DRC / GDSII)
    if (layout != null) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("physical_signoff_card")) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              EdaSectionHeader(
                title = "Physical PnR & GDSII Sign-off",
                subtitle = "Floorplan, CTS skew, M1-M3 routing, and geometry checks",
              )
              EdaStatusPill(
                text = if (layout.drcViolations == 0 && layout.lvsClean) "DRC CLEAN" else "${layout.drcViolations} DRC",
                color = if (layout.drcViolations == 0 && layout.lvsClean) eda.success else eda.warning,
                containerColor = if (layout.drcViolations == 0 && layout.lvsClean) eda.successContainer else eda.warningContainer,
              )
            }
            StatRow("Die Footprint", "${"%.1f".format(Locale.US, layout.dieWidthUm)} × ${"%.1f".format(Locale.US, layout.dieHeightUm)} µm (${layout.rowCount} rows)")
            StatRow("Core Density", "${layout.coreUtilizationPercent}% target · ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}% actual")
            StatRow("Clock Skew / Hold WNS", "${"%.3f".format(Locale.US, layout.clockSkewNs)} ns · +${"%.3f".format(Locale.US, layout.holdWnsNs)} ns")
            StatRow("Routed Wirelength & Vias", "${"%.1f".format(Locale.US, layout.totalWirelengthUm)} µm · ${layout.viaCount} vias")
            StatRow("GDSII Mask Stream", "${layout.gdsBytes.size} B (sha256:${layout.gdsSha256.take(10)})")
            OutlinedButton(
              onClick = onJumpToLayout,
              modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("open_gds_layout_button"),
            ) {
              Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Open Zoomable GDSII Layout Viewer", style = MaterialTheme.typography.labelLarge)
            }
          }
        }
      }
    }

    // 4. Snapshot, Export & Native Share Sheet Controls
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = onSaveSnapshot,
            enabled = !isSaving,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("save_ppa_snapshot_button"),
          ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save Snapshot", style = MaterialTheme.typography.labelLarge)
          }
          OutlinedButton(
            onClick = {
              val fmt = settings.defaultExportFormat
              pendingExportContent = formatReportExport(report, fmt, settings.includeTimestampsInExport)
              exportLauncher.launch("${report.moduleName}_report.${fmt.extension}")
            },
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("export_report_button"),
          ) {
            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Export ${settings.defaultExportFormat.extension.uppercase()}", style = MaterialTheme.typography.labelLarge)
          }
        }

        OutlinedButton(
          onClick = {
            ExportShareHelper.shareBuildBundle(
              context = context,
              report = report,
              layout = layout,
              onResult = onNotify,
            )
          },
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("share_bundle_button"),
        ) {
          Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Share .gds, Netlist, Tcl & Reports", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun LegendItem(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun StatRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

private fun formatReportExport(
  report: SynthesisReport,
  format: ExportFormat,
  includeHeader: Boolean,
): String = when (format) {
  ExportFormat.VERILOG_NETLIST -> report.netlistVerilog
  ExportFormat.CSV_STA -> buildString {
    if (includeHeader) appendLine("# RTL Synth Studio STA CSV — ${report.moduleName} (${report.targetLibrary.displayName})")
    appendLine("instance,cell,level,delay_ns,arrival_ns,required_ns,slack_ns,critical")
    report.nodes.forEach { n ->
      appendLine("${n.instanceName},${n.cellName},${n.level},${n.delayNs},${n.arrivalNs},${n.requiredNs},${n.slackNs},${n.isCriticalPath}")
    }
  }
  ExportFormat.JSON_REPORT -> """
    {
      "module": "${report.moduleName}",
      "library": "${report.targetLibrary.id}",
      "engine": "${report.engineProfile.id}",
      "clockPeriodNs": ${report.clockPeriodNs},
      "fmaxMhz": ${report.fmaxMhz},
      "wnsNs": ${report.wnsNs},
      "tnsNs": ${report.tnsNs},
      "cellCount": ${report.cellCount},
      "combCells": ${report.combCellCount},
      "seqCells": ${report.seqCellCount},
      "areaUm2": ${report.totalAreaUm2},
      "totalPowerMw": ${report.totalPowerMw},
      "durationMs": ${report.synthesisDurationMs}
    }
  """.trimIndent()
  ExportFormat.TEXT_SUMMARY -> buildString {
    if (includeHeader) appendLine("=== RTL Synth Studio Engineering Report ===")
    appendLine("Module: ${report.moduleName}")
    appendLine("PDK: ${report.targetLibrary.displayName}")
    appendLine("Engine: ${report.engineProfile.displayName}")
    appendLine("Cells: ${report.cellCount} (Comb ${report.combCellCount} / Seq ${report.seqCellCount})")
    appendLine("Area: ${"%.2f".format(Locale.US, report.totalAreaUm2)} um^2")
    appendLine("Fmax: ${"%.2f".format(Locale.US, report.fmaxMhz)} MHz | WNS: ${report.wnsNs} ns")
    appendLine("Power: ${"%.3f".format(Locale.US, report.totalPowerMw)} mW")
    appendLine("Critical Path: ${report.criticalPathSummaryText()}")
  }
}
