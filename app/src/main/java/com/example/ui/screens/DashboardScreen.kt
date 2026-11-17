package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.RtlDesign
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlPresetTemplate
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
  workbench: WorkbenchUiState,
  designsState: UiState<List<RtlDesign>>,
  runsState: UiState<List<SynthesisRun>>,
  onCreateProject: (name: String, language: RtlLanguage, library: TargetLibrary) -> Unit,
  onOpenProjects: () -> Unit,
  onImportProjectFile: (fileName: String, content: String) -> Unit,
  onOpenDesign: (RtlDesign) -> Unit,
  onOpenPreset: (RtlPresetTemplate) -> Unit,
  onOpenEditor: () -> Unit = {},
  onRunSynthesis: () -> Unit = {},
  onOpenSchematic: () -> Unit = {},
  onOpenAnalysis: () -> Unit = {},
  onOpenReports: () -> Unit = {},
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  var showCreateDialog by remember { mutableStateOf(false) }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri != null) {
      val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_rtl.v"
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()
      }.onSuccess { content ->
        if (content.isNotBlank()) {
          onImportProjectFile(fileName, content)
        } else {
          onNotify("Selected file was empty.")
        }
      }.onFailure { err ->
        onNotify("Failed to read file: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  val designs = (designsState as? UiState.Success)?.data.orEmpty()
  val runs = (runsState as? UiState.Success)?.data.orEmpty()
  val activeReport = (workbench.synthesisResult as? SynthesisResult.Success)?.report
  val activeError = workbench.synthesisResult as? SynthesisResult.Error

  val recentActivity = remember(workbench, designs, runs) {
    buildList {
      val activeName = workbench.designName.ifBlank { "rtl_module" }
      val autoSaveSuffix = workbench.lastAutoSavedLabel?.let { " @ $it" }.orEmpty()
      add(
        "Workspace" to "Active design '$activeName' (${workbench.files.size.coerceAtLeast(1)} file(s), ${workbench.autoSaveStatus.name.lowercase()}$autoSaveSuffix)"
      )
      if (activeReport != null) {
        val slackText = formatSignedNs(activeReport.wnsNs)
        add(
          "Synthesis" to "${activeReport.moduleName}: ${activeReport.cellCount} cells, WNS $slackText, Fmax ${"%.1f".format(Locale.US, activeReport.fmaxMhz)} MHz"
        )
      } else if (activeError != null) {
        add("Synthesis Error" to "Line ${activeError.line}: ${activeError.message}")
      }
      workbench.physicalLayout?.let { layout ->
        add(
          "PnR / GDSII" to "Die ${"%.1f".format(Locale.US, layout.dieWidthUm)}×${"%.1f".format(Locale.US, layout.dieHeightUm)} µm, Util ${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%, DRC ${layout.drcViolations}"
        )
      }
      workbench.simulationResult?.let { sim ->
        add("Simulation" to "${sim.moduleName}: ${sim.cycleCount} cycles, ${sim.traces.size} traced nets")
      }
      activeReport?.dftReport?.let { dft ->
        add("DFT / ATPG" to "${"%.1f".format(Locale.US, dft.testCoveragePct)}% test cov (${dft.detectedFaults}/${dft.totalFaults} faults)")
      }
      runs.firstOrNull()?.let { latest ->
        val ts = if (latest.createdAtMillis > 0L) {
          SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(latest.createdAtMillis))
        } else {
          "Recent"
        }
        add("Latest Snapshot" to "${latest.designName} (${TargetLibrary.fromId(latest.targetLibrary).shortName}) · $ts")
      }
    }
  }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .testTag("dashboard_screen"),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // 1. Engineering Workstation Identity + Active Design Header
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column {
          EdaPanelHeader(
            title = "RTL Synth Studio · EDA Workstation",
            subtitle = "Active: ${workbench.designName.ifBlank { "rtl_module" }} · ${workbench.targetLibrary.displayName}",
            icon = Icons.Rounded.Memory,
            trailing = {
              EdaStatusPill(
                text = when {
                  workbench.isSynthesizing -> "RUNNING"
                  activeError != null -> "SYNTH ERR"
                  activeReport != null -> if (activeReport.wnsNs >= 0.0) "MET" else "VIOL"
                  else -> "IDLE"
                },
                color = when {
                  workbench.isSynthesizing -> eda.warning
                  activeError != null -> eda.error
                  activeReport != null -> if (activeReport.wnsNs >= 0.0) eda.success else eda.error
                  else -> eda.cyanHighlight
                },
                containerColor = when {
                  workbench.isSynthesizing -> eda.warningContainer
                  activeError != null -> eda.errorContainer
                  activeReport != null -> if (activeReport.wnsNs >= 0.0) eda.successContainer else eda.errorContainer
                  else -> eda.cyanContainer
                },
                showDot = true,
              )
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // KPI Row
            Row(
              modifier = Modifier.fillMaxWidth().testTag("dashboard_kpi_row"),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              EdaCompactMetric(
                label = "Total Projects",
                value = designs.size.toString(),
                modifier = Modifier.weight(1f).testTag("dashboard_kpi_total_projects"),
              )
              EdaCompactMetric(
                label = "Synth Runs",
                value = runs.size.toString(),
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).testTag("dashboard_kpi_total_runs"),
              )
              EdaCompactMetric(
                label = "Active WNS",
                value = activeReport?.let { formatSignedNs(it.wnsNs) } ?: "Not Run",
                valueColor = when {
                  activeReport == null -> MaterialTheme.colorScheme.onSurfaceVariant
                  activeReport.wnsNs >= 0.0 -> eda.success
                  else -> eda.error
                },
                modifier = Modifier.weight(1f).testTag("dashboard_kpi_active_wns"),
              )
            }

            // Primary Quick Actions
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = { showCreateDialog = !showCreateDialog },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("dashboard_new_project_button"),
              ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Project", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
              OutlinedButton(
                onClick = onOpenProjects,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("dashboard_open_project_button"),
              ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open Project", style = MaterialTheme.typography.labelLarge, maxLines = 1)
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dashboard_import_verilog_button"),
              ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import Verilog / SystemVerilog / VHDL", style = MaterialTheme.typography.labelMedium)
              }
            }

            if (showCreateDialog) {
              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))
              DashboardNewProjectInlineCard(
                defaultLibrary = workbench.targetLibrary,
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, lang, lib ->
                  showCreateDialog = false
                  onCreateProject(name, lang, lib)
                },
              )
            }
          }
        }
      }
    }

    // 2. Recent Projects Panel
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dashboard_recent_projects")) {
        Column {
          EdaPanelHeader(
            title = "Recent Projects",
            subtitle = if (designs.isEmpty()) "Starter RTL templates ready to load" else "${designs.size} saved workspace project(s)",
            icon = Icons.Rounded.FolderOpen,
            trailing = {
              TextButton(onClick = onOpenProjects) {
                Text("View All", style = MaterialTheme.typography.labelMedium)
              }
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (designs.isEmpty()) {
              PresetRtlDesigns.templates.take(3).forEach { tpl ->
                DashboardCompactListRow(
                  title = "${tpl.name}.${tpl.language.extension}",
                  subtitle = tpl.description,
                  badge = tpl.defaultLibrary.shortName,
                  badgeColor = eda.cyanHighlight,
                  badgeContainer = eda.cyanContainer,
                  onClick = { onOpenPreset(tpl) },
                )
              }
            } else {
              designs.take(4).forEach { design ->
                val met = design.lastWnsNs >= 0.0
                DashboardCompactListRow(
                  title = design.name,
                  subtitle = "${TargetLibrary.fromId(design.targetLibrary).shortName} · ${design.lastCellCount} cells · ${"%.1f".format(Locale.US, design.lastAreaUm2)} um²",
                  badge = if (design.lastCellCount > 0L) formatSignedNs(design.lastWnsNs) else RtlLanguage.fromId(design.language).badge,
                  badgeColor = if (design.lastCellCount > 0L) (if (met) eda.success else eda.error) else eda.cyanHighlight,
                  badgeContainer = if (design.lastCellCount > 0L) (if (met) eda.successContainer else eda.errorContainer) else eda.cyanContainer,
                  onClick = { onOpenDesign(design) },
                )
              }
            }
          }
        }
      }
    }

    // 3. Recent Synthesis Runs Panel
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dashboard_recent_runs")) {
        Column {
          EdaPanelHeader(
            title = "Recent Synthesis Runs",
            subtitle = if (runs.isEmpty()) "Execute synthesis to log PPA telemetry" else "Last ${runs.take(4).size} recorded runs",
            icon = Icons.Rounded.Speed,
            trailing = {
              if (runs.isNotEmpty() || activeReport != null) {
                TextButton(onClick = onOpenReports) {
                  Text("Logs & Reports", style = MaterialTheme.typography.labelMedium)
                }
              }
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (runs.isEmpty()) {
              Text(
                text = "No persisted synthesis run snapshots yet. Run synthesis to record Fmax, WNS, cell area, and power.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              runs.take(4).forEach { run ->
                val met = run.wnsNs >= 0.0
                DashboardCompactListRow(
                  title = "${run.designName} · ${TargetLibrary.fromId(run.targetLibrary).shortName}",
                  subtitle = "${run.cellCount} cells · Fmax ${"%.1f".format(Locale.US, run.fmaxMhz)} MHz · ${"%.2f".format(Locale.US, run.powerMw)} mW",
                  badge = if (met) "MET ${formatSignedNs(run.wnsNs)}" else "VIOL ${formatSignedNs(run.wnsNs)}",
                  badgeColor = if (met) eda.success else eda.error,
                  badgeContainer = if (met) eda.successContainer else eda.errorContainer,
                  onClick = onOpenAnalysis,
                )
              }
            }
          }
        }
      }
    }

    // 4. Recent Design Activity Feed
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("dashboard_recent_activity")) {
        Column {
          EdaPanelHeader(
            title = "Recent Design Activity",
            subtitle = "Live workspace, synthesis & verification state",
            icon = Icons.Rounded.History,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            recentActivity.forEachIndexed { idx, (scope, detail) ->
              EdaKeyValueRow(
                label = scope,
                value = detail,
                valueColor = MaterialTheme.colorScheme.onSurface,
              )
              if (idx < recentActivity.lastIndex) {
                HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DashboardCompactListRow(
  title: String,
  subtitle: String,
  badge: String,
  badgeColor: androidx.compose.ui.graphics.Color,
  badgeContainer: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
) {
  val eda = LocalEdaColors.current
  Surface(
    onClick = onClick,
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      EdaStatusPill(
        text = badge,
        color = badgeColor,
        containerColor = badgeContainer,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardNewProjectInlineCard(
  defaultLibrary: TargetLibrary,
  onDismiss: () -> Unit,
  onConfirm: (String, RtlLanguage, TargetLibrary) -> Unit,
) {
  var name by remember { mutableStateOf("rtl_datapath") }
  var language by remember { mutableStateOf(RtlLanguage.VERILOG) }
  var library by remember { mutableStateOf(defaultLibrary) }

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("Create RTL Project", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Top Module / Project Name") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("dashboard_create_name_input"),
    )
    Text("HDL Language", style = MaterialTheme.typography.labelMedium)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      listOf(RtlLanguage.VERILOG, RtlLanguage.SYSTEM_VERILOG, RtlLanguage.VHDL).forEach { lang ->
        FilterChip(
          selected = language == lang,
          onClick = { language = lang },
          label = { Text(lang.displayName, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
    Text("Target Technology Library", style = MaterialTheme.typography.labelMedium)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      TargetLibrary.entries.forEach { lib ->
        FilterChip(
          selected = library == lib,
          onClick = { library = lib },
          label = { Text(lib.shortName, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      TextButton(onClick = onDismiss) { Text("Cancel") }
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = { onConfirm(name, language, library) },
        modifier = Modifier.testTag("dashboard_confirm_create_button"),
      ) {
        Text("Create")
      }
    }
  }
}

private fun formatSignedNs(v: Double): String =
  if (v >= 0) "+${"%.2f".format(Locale.US, v)} ns" else "${"%.2f".format(Locale.US, v)} ns"
