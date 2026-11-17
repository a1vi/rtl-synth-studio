package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings
import com.example.data.model.ExportFormat
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaEmptyStatePanel
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.ExportShareHelper
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.WorkbenchUiState
import java.util.Locale

private enum class ReportsLogFilter(val label: String) {
  ALL("All"),
  WARN("Warnings"),
  ERROR("Errors"),
  TIMING("Timing"),
}

private enum class FormattedReportKind(val label: String) {
  SUMMARY("Summary"),
  RTL_DRC("RTL DRC"),
  TIMING("Timing"),
  AREA("Area"),
  POWER("Power"),
  NETLIST("Netlist .v"),
}

@Composable
fun ReportsLogsScreen(
  state: WorkbenchUiState,
  settings: AppSettings,
  onSaveSnapshot: () -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  onOpenSynthesis: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0=Logs, 1=Formatted Reports, 2=Export
  var logFilter by rememberSaveable { mutableStateOf(ReportsLogFilter.ALL) }
  var logSearch by rememberSaveable { mutableStateOf("") }
  var selectedReportKind by rememberSaveable { mutableStateOf(FormattedReportKind.SUMMARY) }

  var pendingExportContent by remember { mutableStateOf<String?>(null) }
  var pendingZipBytes by remember { mutableStateOf<ByteArray?>(null) }

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

  val zipExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/zip"),
  ) { uri: Uri? ->
    val bytes = pendingZipBytes
    pendingZipBytes = null
    if (uri != null && bytes != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(bytes)
        }
      }.onSuccess {
        onNotify("Saved Tapeout Bundle .zip (${bytes.size / 1024} KB).")
      }.onFailure {
        onNotify("ZIP export failed: ${it.localizedMessage ?: "error"}")
      }
    }
  }

  val report = (state.synthesisResult as? SynthesisResult.Success)?.report
  val synthError = state.synthesisResult as? SynthesisResult.Error

  val allLogLines = remember(state.flowLogLines, state.synthesisResult) {
    val synthLogs = when (val res = state.synthesisResult) {
      is SynthesisResult.Success -> res.report.synthesisLog + res.report.warnings.map { "[WARN] $it" }
      is SynthesisResult.Error -> res.log + "[ERROR] Line ${res.line}: ${res.message}"
      null -> emptyList()
    }
    (state.flowLogLines + synthLogs).distinct()
  }

  val filteredLogs = remember(allLogLines, logFilter, logSearch) {
    val bySeverity = when (logFilter) {
      ReportsLogFilter.ALL -> allLogLines
      ReportsLogFilter.WARN -> allLogLines.filter { "WARN" in it.uppercase() }
      ReportsLogFilter.ERROR -> allLogLines.filter { "ERROR" in it.uppercase() || "FAIL" in it.uppercase() }
      ReportsLogFilter.TIMING -> allLogLines.filter {
        val up = it.uppercase()
        "STA" in up || "TIMING" in up || "WNS" in up || "SLACK" in up || "CTS" in up
      }
    }
    if (logSearch.isBlank()) bySeverity
    else bySeverity.filter { it.contains(logSearch, ignoreCase = true) }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .testTag("reports_logs_screen"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      val tabs = listOf(
        Triple("Logs (${allLogLines.size})", Icons.Rounded.Terminal, "reports_subtab_logs"),
        Triple("Reports", Icons.Rounded.Description, "reports_subtab_formatted"),
        Triple("Export", Icons.Rounded.FileDownload, "reports_subtab_export"),
      )
      tabs.forEachIndexed { idx, (label, icon, tag) ->
        SegmentedButton(
          selected = activeTab == idx,
          onClick = { activeTab = idx },
          shape = SegmentedButtonDefaults.itemShape(idx, tabs.size),
          icon = {},
          modifier = Modifier.testTag(tag),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
          }
        }
      }
    }

    when (activeTab) {
      0 -> {
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          // Search & Filter Bar
          EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Surface(
                color = eda.codeSurface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, eda.subtleBorder),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                  BasicTextField(
                    value = logSearch,
                    onValueChange = { logSearch = it },
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                      fontFamily = JetBrainsMonoFamily,
                      color = MaterialTheme.colorScheme.onSurface,
                    ),
                    decorationBox = { inner ->
                      if (logSearch.isEmpty()) {
                        Text("Search logs, warnings, cells...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }
                      inner()
                    },
                    modifier = Modifier.weight(1f).testTag("reports_log_search_input"),
                  )
                  if (logSearch.isNotEmpty()) {
                    IconButton(onClick = { logSearch = "" }, modifier = Modifier.size(28.dp)) {
                      Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                    }
                  }
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                ReportsLogFilter.entries.forEach { f ->
                  FilterChip(
                    selected = logFilter == f,
                    onClick = { logFilter = f },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("reports_log_filter_${f.name}"),
                  )
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                  onClick = {
                    clipboard.setText(AnnotatedString(filteredLogs.joinToString("\n")))
                    onNotify("Copied ${filteredLogs.size} log line(s).")
                  },
                  enabled = filteredLogs.isNotEmpty(),
                  modifier = Modifier.heightIn(min = 40.dp),
                ) {
                  Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }

          if (synthError != null) {
            Surface(
              onClick = { onJumpToRtlLine(synthError.line) },
              color = eda.errorContainer,
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.error.copy(alpha = 0.65f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = eda.error, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Synthesis Error · Line ${synthError.line}", style = MaterialTheme.typography.labelMedium, color = eda.error)
                  Text(synthError.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("Jump ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
              }
            }
          }

          if (report != null && report.warnings.isNotEmpty()) {
            Surface(
              color = eda.warningContainer,
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.65f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = eda.warning, modifier = Modifier.size(16.dp))
                  Text("Warnings (${report.warnings.size})", style = MaterialTheme.typography.labelMedium, color = eda.warning)
                }
                report.warnings.forEach { w ->
                  Text("• $w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
              }
            }
          }

          // Syntax-Highlighted Log Output
          Surface(
            color = eda.codeSurface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, eda.subtleBorder),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("reports_log_console"),
          ) {
            if (filteredLogs.isEmpty()) {
              Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = if (allLogLines.isEmpty()) "No synthesis logs recorded yet. Run Synthesis to view logs." else "No log lines match the active filter.",
                  style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                itemsIndexed(filteredLogs) { idx, line ->
                  val primaryColor = MaterialTheme.colorScheme.primary
                  val defaultTextColor = MaterialTheme.colorScheme.onSurface
                  val highlighted = remember(line, eda.isDark, primaryColor, defaultTextColor) {
                    highlightLogLine(line, eda.error, eda.warning, eda.success, eda.cyanHighlight, primaryColor, defaultTextColor)
                  }
                  Text(
                    text = highlighted,
                    style = MaterialTheme.typography.labelSmall.copy(
                      fontFamily = JetBrainsMonoFamily,
                      lineHeight = 16.sp,
                    ),
                    modifier = Modifier.testTag("reports_log_line_$idx"),
                  )
                }
              }
            }
          }
        }
      }

      1 -> {
        if (report == null) {
          EdaEmptyStatePanel(
            icon = Icons.Rounded.Assessment,
            title = "No Engineering Reports Yet",
            message = "Run synthesis to generate formatted Timing, Area, Power, and Gate-Level Netlist reports.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          val resolvedPower = remember(state.powerAnalysis, report, state.simulationResult) {
            state.powerAnalysis ?: report.powerAnalysis ?: PowerAnalysisEngine.analyze(report, state.simulationResult)
          }
          val formattedText = remember(report, resolvedPower, selectedReportKind, state.physicalLayout) {
            buildFormattedReportText(report, resolvedPower, state.physicalLayout, selectedReportKind)
          }
          Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              FormattedReportKind.entries.forEach { kind ->
                FilterChip(
                  selected = selectedReportKind == kind,
                  onClick = { selectedReportKind = kind },
                  label = { Text(kind.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("report_kind_${kind.name}"),
                )
              }
            }
            EdaSurfaceCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
              Column(modifier = Modifier.fillMaxSize()) {
                EdaPanelHeader(
                  title = "${selectedReportKind.label} Report · ${report.moduleName}",
                  icon = Icons.Rounded.Description,
                  trailing = {
                    OutlinedButton(
                      onClick = {
                        clipboard.setText(AnnotatedString(formattedText))
                        onNotify("Copied ${selectedReportKind.label} report.")
                      },
                      modifier = Modifier.heightIn(min = 38.dp),
                    ) {
                      Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                      Spacer(Modifier.width(4.dp))
                      Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                  },
                )
                Surface(
                  color = eda.codeSurface,
                  modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                  LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp).testTag("reports_formatted_text_list"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    val lines = formattedText.lines()
                    itemsIndexed(lines) { _, l ->
                      Text(
                        text = l,
                        style = MaterialTheme.typography.labelSmall.copy(
                          fontFamily = JetBrainsMonoFamily,
                          lineHeight = 16.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      2 -> {
        if (report == null) {
          EdaEmptyStatePanel(
            icon = Icons.Rounded.FileDownload,
            title = "Nothing to Export Yet",
            message = "Run synthesis first to export STA CSVs, JSON summaries, Verilog netlists, or full Tapeout .zip bundles.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          val resolvedPower = remember(state.powerAnalysis, report, state.simulationResult) {
            state.powerAnalysis ?: report.powerAnalysis ?: PowerAnalysisEngine.analyze(report, state.simulationResult)
          }
          val reportWithPower = remember(report, resolvedPower) {
            report.copy(
              switchingPowerMw = resolvedPower.switchingPowerMw,
              internalPowerMw = resolvedPower.internalPowerMw,
              dynamicPowerMw = resolvedPower.dynamicPowerMw,
              leakagePowerMw = resolvedPower.leakagePowerMw,
              totalPowerMw = resolvedPower.totalPowerMw,
              powerAnalysis = resolvedPower,
            )
          }
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            item {
              EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                  EdaPanelHeader(
                    title = "Snapshot & Export Artifacts",
                    subtitle = "${report.moduleName} · ${report.targetLibrary.shortName}",
                    icon = Icons.Rounded.FileDownload,
                  )
                  Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Button(
                      onClick = onSaveSnapshot,
                      enabled = !state.isSaving,
                      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reports_save_snapshot_button"),
                    ) {
                      Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                      Spacer(Modifier.width(6.dp))
                      Text("Save Run Snapshot to Workspace", style = MaterialTheme.typography.labelLarge)
                    }
                    HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
                    Text("Export Individual Format", style = MaterialTheme.typography.labelMedium)
                    Row(
                      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      ExportFormat.entries.forEach { fmt ->
                        OutlinedButton(
                          onClick = {
                            pendingExportContent = when (fmt) {
                              ExportFormat.VERILOG_NETLIST -> reportWithPower.netlistVerilog
                              ExportFormat.CSV_STA -> buildStaCsv(reportWithPower)
                              ExportFormat.JSON_REPORT -> buildJsonReport(reportWithPower)
                              ExportFormat.TEXT_SUMMARY -> buildFormattedReportText(reportWithPower, resolvedPower, state.physicalLayout, FormattedReportKind.SUMMARY)
                            }
                            exportLauncher.launch("${report.moduleName}_report.${fmt.extension}")
                          },
                          modifier = Modifier.heightIn(min = 48.dp).testTag("reports_export_${fmt.name}"),
                        ) {
                          Text(fmt.label, style = MaterialTheme.typography.labelSmall)
                        }
                      }
                    }
                    HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Button(
                        onClick = {
                          pendingZipBytes = ExportShareHelper.buildTapeoutZipBytes(
                            projectFiles = state.files,
                            fallbackRtl = state.verilogCode,
                            report = reportWithPower,
                            layout = state.physicalLayout,
                            sdcText = state.sdcText,
                          )
                          zipExportLauncher.launch("${report.moduleName}_tapeout_bundle.zip")
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reports_export_zip_button"),
                      ) {
                        Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save .zip Bundle", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                      }
                      OutlinedButton(
                        onClick = {
                          val zip = ExportShareHelper.buildTapeoutZipBytes(
                            projectFiles = state.files,
                            fallbackRtl = state.verilogCode,
                            report = reportWithPower,
                            layout = state.physicalLayout,
                            sdcText = state.sdcText,
                          )
                          ExportShareHelper.shareZipBundle(
                            context = context,
                            moduleName = report.moduleName,
                            zipBytes = zip,
                            onResult = onNotify,
                          )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reports_share_zip_button"),
                      ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share .zip", style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

private fun highlightLogLine(
  line: String,
  errorColor: androidx.compose.ui.graphics.Color,
  warningColor: androidx.compose.ui.graphics.Color,
  successColor: androidx.compose.ui.graphics.Color,
  cyanColor: androidx.compose.ui.graphics.Color,
  primaryColor: androidx.compose.ui.graphics.Color,
  defaultColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
  val tagMatch = Regex("""^(\[[A-Z0-9_/-]+\])(.*)$""").find(line)
  if (tagMatch != null) {
    val prefix = tagMatch.groupValues[1]
    val rest = tagMatch.groupValues[2]
    val tagColor = when {
      "ERR" in prefix.uppercase() || "FAIL" in prefix.uppercase() -> errorColor
      "WARN" in prefix.uppercase() -> warningColor
      "GDS" in prefix.uppercase() || "PASS" in prefix.uppercase() || "OK" in prefix.uppercase() -> successColor
      "STA" in prefix.uppercase() || "TIMING" in prefix.uppercase() || "CTS" in prefix.uppercase() -> cyanColor
      else -> primaryColor
    }
    withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
      append(prefix)
    }
    val bodyColor = when {
      "ERROR" in line.uppercase() || "FAIL" in line.uppercase() -> errorColor
      "WARN" in line.uppercase() -> warningColor
      "finished successfully" in line.lowercase() || "COMPLETE" in line.uppercase() -> successColor
      else -> defaultColor.copy(alpha = 0.92f)
    }
    withStyle(SpanStyle(color = bodyColor)) {
      append(rest)
    }
  } else {
    val color = when {
      "ERROR" in line.uppercase() || "FAIL" in line.uppercase() -> errorColor
      "WARN" in line.uppercase() -> warningColor
      "PASS" in line.uppercase() || "COMPLETE" in line.uppercase() -> successColor
      else -> defaultColor.copy(alpha = 0.92f)
    }
    withStyle(SpanStyle(color = color)) {
      append(line)
    }
  }
}

private fun buildFormattedReportText(
  report: SynthesisReport,
  power: com.example.domain.synth.PowerAnalysisReport,
  layout: com.example.domain.synth.PhysicalLayoutSnapshot?,
  kind: FormattedReportKind,
): String = when (kind) {
  FormattedReportKind.SUMMARY -> buildString {
    appendLine("====================================================")
    appendLine(" RTL SYNTH STUDIO :: ENGINEERING SUMMARY REPORT")
    appendLine("====================================================")
    appendLine("Module        : ${report.moduleName}")
    appendLine("PDK / Library : ${report.targetLibrary.displayName} (${report.targetLibrary.processNode})")
    appendLine("Engine        : ${report.engineProfile.displayName}")
    appendLine("Goal          : ${report.optimizationGoal.displayName}")
    appendLine("Duration      : ${report.synthesisDurationMs} ms")
    appendLine("----------------------------------------------------")
    appendLine("Cells Total   : ${report.cellCount} (Comb ${report.combCellCount} / Seq ${report.seqCellCount})")
    appendLine("Cell Area     : ${"%.2f".format(Locale.US, report.totalAreaUm2)} um^2")
    appendLine("Clock Target  : ${"%.2f".format(Locale.US, report.clockPeriodNs)} ns")
    appendLine("Setup WNS     : ${"%.3f".format(Locale.US, report.wnsNs)} ns | TNS: ${"%.3f".format(Locale.US, report.tnsNs)} ns")
    appendLine("Fmax Estimate : ${"%.1f".format(Locale.US, report.fmaxMhz)} MHz")
    appendLine("Total Power   : ${"%.3f".format(Locale.US, power.totalPowerMw)} mW (${power.activitySource.label})")
    if (layout != null) {
      appendLine("Die Size      : ${"%.1f".format(Locale.US, layout.dieWidthUm)} x ${"%.1f".format(Locale.US, layout.dieHeightUm)} um")
      appendLine("Core Util     : ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}% (target ${layout.coreUtilizationPercent}%)")
      appendLine("Hold WNS      : +${"%.3f".format(Locale.US, layout.holdWnsNs)} ns | DRC: ${layout.drcViolations}")
    } else {
      appendLine("Physical PnR  : Not executed (pre-layout synthesis estimate)")
    }
    report.dftReport?.let { dft ->
      appendLine("DFT Scan      : ${if (dft.scanInserted) "Inserted (${dft.scanChains.size} chains)" else "Audit Only"} | Test Cov ${"%.1f".format(Locale.US, dft.testCoveragePct)}%")
    }
    report.rtlDrcReport?.let { drc ->
      appendLine("RTL DRC       : ${drc.summaryBadgeText()} (${drc.rulesEvaluated} rules evaluated)")
    }
  }
  FormattedReportKind.RTL_DRC ->
    report.rtlDrcReport?.formatTextReport()
      ?: "RTL DRC report unavailable for this synthesis run."
  FormattedReportKind.TIMING -> buildString {
    appendLine("=== STATIC TIMING ANALYSIS REPORT (${report.moduleName}) ===")
    appendLine("Clock Period     : ${"%.3f".format(Locale.US, report.clockPeriodNs)} ns")
    appendLine("Arrival Time     : ${"%.3f".format(Locale.US, report.criticalPathDelayNs)} ns")
    appendLine("Setup WNS / TNS  : ${"%.3f".format(Locale.US, report.wnsNs)} ns / ${"%.3f".format(Locale.US, report.tnsNs)} ns")
    appendLine("Hold WNS         : ${layout?.let { "+${"%.3f".format(Locale.US, it.holdWnsNs)} ns" } ?: "N/A (pre-CTS)"}")
    appendLine("Fmax Estimate    : ${"%.1f".format(Locale.US, report.fmaxMhz)} MHz")
    appendLine("Logic Depth      : ${report.nodes.maxOfOrNull { it.level } ?: 0} stage(s)")
    appendLine("")
    appendLine("Critical Path Stages:")
    report.criticalPath.forEachIndexed { i, st ->
      appendLine("  ${i + 1}. ${st.instanceName} [${st.cellName}] -> ${st.netName} | +${"%.3f".format(Locale.US, st.incrDelayNs)} ns | arrival ${"%.3f".format(Locale.US, st.arrivalNs)} ns")
    }
  }
  FormattedReportKind.AREA -> buildString {
    val bufs = report.nodes.count { it.gateType == com.example.domain.synth.GateType.BUF }
    val invs = report.nodes.count { it.gateType == com.example.domain.synth.GateType.INV }
    appendLine("=== AREA & CELL UTILIZATION REPORT (${report.moduleName}) ===")
    appendLine("Total Mapped Cells   : ${report.cellCount}")
    appendLine("Combinational Cells  : ${report.combCellCount}")
    appendLine("Sequential Cells     : ${report.seqCellCount}")
    appendLine("Buffers / Inverters  : $bufs / $invs")
    appendLine("Synthesized Cell Area: ${"%.2f".format(Locale.US, report.totalAreaUm2)} um^2")
    if (layout != null) {
      appendLine("Physical Die Area    : ${"%.1f".format(Locale.US, layout.dieWidthUm * layout.dieHeightUm)} um^2")
      appendLine("Physical Core Area   : ${"%.1f".format(Locale.US, layout.coreWidthUm * layout.coreHeightUm)} um^2")
      appendLine("Core Utilization     : ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%")
    }
    appendLine("")
    appendLine("Standard-Cell Histogram:")
    report.gateHistogram.forEach { (cell, count) ->
      appendLine("  $cell : $count")
    }
  }
  FormattedReportKind.POWER -> buildString {
    appendLine("=== CMOS POWER DISSIPATION REPORT (${report.moduleName}) ===")
    appendLine("Activity Source   : ${power.activitySource.label}")
    appendLine("Mean Activity (a) : ${"%.3f".format(Locale.US, power.averageActivityFactor)}")
    appendLine("Supply / Freq     : ${"%.2f".format(Locale.US, power.vddVolts)} V @ ${"%.1f".format(Locale.US, power.clockFreqMhz)} MHz")
    appendLine("Switching Power   : ${"%.3f".format(Locale.US, power.switchingPowerMw)} mW")
    appendLine("Internal Power    : ${"%.3f".format(Locale.US, power.internalPowerMw)} mW")
    appendLine("Leakage Power     : ${"%.3f".format(Locale.US, power.leakagePowerMw)} mW")
    appendLine("Total Power       : ${"%.3f".format(Locale.US, power.totalPowerMw)} mW")
    appendLine("Energy / Cycle    : ${"%.2f".format(Locale.US, power.energyPerCyclePj)} pJ")
  }
  FormattedReportKind.NETLIST -> report.netlistVerilog
}

private fun buildStaCsv(report: SynthesisReport): String = buildString {
  appendLine("instance,cell,level,delay_ns,arrival_ns,required_ns,slack_ns,critical")
  report.nodes.forEach { n ->
    appendLine("${n.instanceName},${n.cellName},${n.level},${n.delayNs},${n.arrivalNs},${n.requiredNs},${n.slackNs},${n.isCriticalPath}")
  }
}

private fun buildJsonReport(report: SynthesisReport): String = """
  {
    "module": "${report.moduleName}",
    "library": "${report.targetLibrary.id}",
    "engine": "${report.engineProfile.id}",
    "clockPeriodNs": ${report.clockPeriodNs},
    "fmaxMhz": ${report.fmaxMhz},
    "wnsNs": ${report.wnsNs},
    "tnsNs": ${report.tnsNs},
    "cellCount": ${report.cellCount},
    "areaUm2": ${report.totalAreaUm2},
    "totalPowerMw": ${report.totalPowerMw}
  }
""".trimIndent()
