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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlPresetTemplate
import com.example.ui.components.EdaSectionHeader
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
fun ProjectsScreen(
  workbench: WorkbenchUiState,
  designsState: UiState<List<RtlDesign>>,
  runsState: UiState<List<SynthesisRun>>,
  onCreateProject: (name: String, language: RtlLanguage, library: TargetLibrary) -> Unit,
  onImportProjectFile: (fileName: String, content: String) -> Unit,
  onOpenDesign: (RtlDesign) -> Unit,
  onDuplicateDesign: (RtlDesign) -> Unit,
  onDeleteDesign: (String) -> Unit,
  onOpenPreset: (RtlPresetTemplate) -> Unit,
  onOpenActiveEditor: () -> Unit,
  onOpenRunReport: () -> Unit,
  onDeleteRun: (String) -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  var showCreateDialog by remember { mutableStateOf(false) }
  var showCompareDialog by remember { mutableStateOf(false) }

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
        onNotify("Failed to read file: ${err.localizedMessage ?: "unknown error"}")
      }
    }
  }

  val designs = (designsState as? UiState.Success)?.data.orEmpty()
  val runs = (runsState as? UiState.Success)?.data.orEmpty()

  if (showCompareDialog) {
    CompareRunsDialog(
      designs = designs,
      runs = runs,
      onDismiss = { showCompareDialog = false },
    )
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // 1. Engine Status & Active Workspace Card
    item {
      EdaSurfaceCard(elevated = false, modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(8.dp))
              Column {
                Text(
                  text = "EDA Engine Ready",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "${workbench.synthesisEngine.shortName} · ${workbench.targetLibrary.shortName}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            EdaStatusPill(
              text = if (workbench.isSynthesizing) "RUNNING" else "LOCAL · READY",
              color = if (workbench.isSynthesizing) eda.warning else eda.success,
              containerColor = if (workbench.isSynthesizing) eda.warningContainer else eda.successContainer,
              showDot = true,
            )
          }

          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))

          // Primary Actions: Create New Project + Import RTL Project
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = { showCreateDialog = !showCreateDialog },
              shape = MaterialTheme.shapes.small,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("create_new_project_button"),
            ) {
              Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("New Project", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            OutlinedButton(
              onClick = { importLauncher.launch(arrayOf("*/*")) },
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.subtleBorder),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("import_rtl_project_button"),
            ) {
              Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Import RTL", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
          }

          if (showCreateDialog) {
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))
            CreateProjectInlineCard(
              defaultLibrary = workbench.targetLibrary,
              onDismiss = { showCreateDialog = false },
              onConfirm = { name, lang, lib ->
                showCreateDialog = false
                onCreateProject(name, lang, lib)
              },
            )
          }

          // Active Draft Quick Resume Bar
          Surface(
            onClick = onOpenActiveEditor,
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Code,
                  contentDescription = null,
                  tint = eda.cyanHighlight,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                  Text(
                    text = "Active: ${workbench.designName.ifBlank { "rtl_module" }}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = "${workbench.primaryLanguage.badge} · ${workbench.files.size.coerceAtLeast(1)} files · ${workbench.clockPeriodNs} ns",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              Text(
                text = "Open Editor",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }

    // 2. Recent Projects Section
    item {
      EdaSectionHeader(
        title = "Recent Projects",
        subtitle = if (designs.isEmpty()) "Save projects from the Editor to build your local workspace" else "${designs.size} project(s) stored on device",
        trailing = {
          if (designs.size >= 2 || runs.size >= 2) {
            TextButton(
              onClick = { showCompareDialog = true },
              modifier = Modifier.testTag("compare_projects_button"),
            ) {
              Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Compare", style = MaterialTheme.typography.labelLarge)
            }
          }
        },
      )
    }

    if (designs.isEmpty()) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.FolderOpen,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
            Text(
              text = "No saved projects yet. Tap New Project, Import RTL, or choose an example below.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(designs, key = { it.id }) { design ->
        SavedProjectCard(
          design = design,
          onOpen = { onOpenDesign(design) },
          onDuplicate = { onDuplicateDesign(design) },
          onDelete = { onDeleteDesign(design.id) },
        )
      }
    }

    // 3. Quick Access Example Verilog, SystemVerilog & VHDL Projects
    item {
      EdaSectionHeader(
        title = "Example HDL Projects",
        subtitle = "Verified Verilog, SystemVerilog, and VHDL starter designs",
      )
    }

    items(PresetRtlDesigns.templates, key = { it.id }) { template ->
      ExampleTemplateCard(
        template = template,
        onOpen = { onOpenPreset(template) },
      )
    }

    // 4. Recent Synthesis Activities
    item {
      EdaSectionHeader(
        title = "Recent Synthesis Activity",
        subtitle = if (runs.isEmpty()) "Synthesis runs appear automatically after elaboration" else "Last ${runs.take(8).size} completed runs",
      )
    }

    if (runs.isEmpty()) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.History,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = "Execute synthesis from the Editor or Synthesis tab to log timing & area telemetry.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(runs.take(8), key = { it.id }) { run ->
        SynthesisActivityCard(
          run = run,
          onOpenReport = onOpenRunReport,
          onDelete = { onDeleteRun(run.id) },
        )
      }
    }
  }
}

@Composable
private fun SavedProjectCard(
  design: RtlDesign,
  onOpen: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
) {
  val eda = LocalEdaColors.current
  val lib = TargetLibrary.fromId(design.targetLibrary)
  val lang = RtlLanguage.fromId(design.language)
  val slackMet = design.lastWnsNs >= 0.0

  EdaSurfaceCard(
    onClick = onOpen,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("project_card_${design.id}"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(
            text = design.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (design.description.isNotBlank()) {
            Text(
              text = design.description,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          EdaStatusPill(
            text = lang.badge,
            color = eda.cyanHighlight,
            containerColor = eda.cyanContainer,
          )
          IconButton(
            onClick = onDuplicate,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.ContentCopy,
              contentDescription = "Duplicate project",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onDelete,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "Delete project",
              tint = eda.error.copy(alpha = 0.85f),
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        EdaStatusPill(
          text = lib.shortName,
          color = MaterialTheme.colorScheme.primary,
          containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
        EdaStatusPill(
          text = "WNS ${formatSignedNs(design.lastWnsNs)}",
          color = if (slackMet) eda.success else eda.error,
          containerColor = if (slackMet) eda.successContainer else eda.errorContainer,
        )
        Text(
          text = "${design.lastCellCount} cells · ${"%.1f".format(Locale.US, design.lastAreaUm2)} um²",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ExampleTemplateCard(
  template: RtlPresetTemplate,
  onOpen: () -> Unit,
) {
  val eda = LocalEdaColors.current
  EdaSurfaceCard(
    onClick = onOpen,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("example_card_${template.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f).padding(end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "${template.name}.${template.language.extension}",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
          )
          EdaStatusPill(
            text = template.language.badge,
            color = eda.cyanHighlight,
            containerColor = eda.cyanContainer,
          )
        }
        Text(
          text = template.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      EdaStatusPill(
        text = template.defaultLibrary.shortName,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = eda.elevatedCard,
      )
    }
  }
}

@Composable
private fun SynthesisActivityCard(
  run: SynthesisRun,
  onOpenReport: () -> Unit,
  onDelete: () -> Unit,
) {
  val eda = LocalEdaColors.current
  val lib = TargetLibrary.fromId(run.targetLibrary)
  val engine = SynthesisEngineProfile.fromId(run.engineId)
  val timingMet = run.wnsNs >= 0.0
  val timeStr = remember(run.createdAtMillis) {
    if (run.createdAtMillis > 0L) {
      SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(run.createdAtMillis))
    } else {
      "Recent"
    }
  }

  EdaSurfaceCard(
    onClick = onOpenReport,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Speed,
          contentDescription = null,
          tint = if (timingMet) eda.success else eda.error,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = "${run.designName} · ${lib.shortName}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${engine.shortName} · ${run.cellCount} cells · Fmax ${"%.1f".format(Locale.US, run.fmaxMhz)} MHz · $timeStr",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        EdaStatusPill(
          text = if (timingMet) "MET" else "VIOL",
          color = if (timingMet) eda.success else eda.error,
          containerColor = if (timingMet) eda.successContainer else eda.errorContainer,
        )
        IconButton(
          onClick = onDelete,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = "Remove activity",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateProjectInlineCard(
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
      modifier = Modifier.fillMaxWidth().testTag("new_project_name_input"),
    )
    Text("HDL Language", style = MaterialTheme.typography.labelLarge)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      listOf(RtlLanguage.VERILOG, RtlLanguage.SYSTEM_VERILOG, RtlLanguage.VHDL).forEach { lang ->
        FilterChip(
          selected = language == lang,
          onClick = { language = lang },
          label = { Text(lang.displayName, style = MaterialTheme.typography.labelMedium) },
          modifier = Modifier.testTag("create_lang_${lang.name}"),
        )
      }
    }
    Text("Target Technology Library", style = MaterialTheme.typography.labelLarge)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      TargetLibrary.entries.forEach { lib ->
        FilterChip(
          selected = library == lib,
          onClick = { library = lib },
          label = { Text(lib.shortName, style = MaterialTheme.typography.labelMedium) },
          modifier = Modifier.testTag("create_lib_${lib.id}"),
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
        modifier = Modifier.testTag("confirm_create_project_button"),
      ) {
        Text("Create")
      }
    }
  }
}

@Composable
private fun CompareRunsDialog(
  designs: List<RtlDesign>,
  runs: List<SynthesisRun>,
  onDismiss: () -> Unit,
) {
  val items: List<CompareMetricRow> = remember(designs, runs) {
    if (runs.size >= 2) {
      runs.take(2).map { r ->
        CompareMetricRow(
          title = "${r.designName} (${TargetLibrary.fromId(r.targetLibrary).shortName})",
          cells = r.cellCount,
          area = r.areaUm2,
          fmax = r.fmaxMhz,
          wns = r.wnsNs,
          power = r.powerMw,
        )
      }
    } else {
      designs.take(2).map { d ->
        CompareMetricRow(
          title = "${d.name} (${TargetLibrary.fromId(d.targetLibrary).shortName})",
          cells = d.lastCellCount,
          area = d.lastAreaUm2,
          fmax = if (d.clockPeriodNs > 0) 1000.0 / (d.clockPeriodNs - d.lastWnsNs).coerceAtLeast(0.1) else 0.0,
          wns = d.lastWnsNs,
          power = d.lastPowerMw,
        )
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("PPA Comparison", style = MaterialTheme.typography.titleLarge) },
    text = {
      if (items.size < 2) {
        Text("Save at least two projects or synthesis runs to compare PPA metrics.")
      } else {
        val a = items[0]
        val b = items[1]
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("A: ${a.title}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
          Text("B: ${b.title}", style = MaterialTheme.typography.labelMedium, color = LocalEdaColors.current.cyanHighlight)
          HorizontalDivider()
          CompareLine("Cells", "${a.cells}", "${b.cells}")
          CompareLine("Area (um²)", "${"%.2f".format(Locale.US, a.area)}", "${"%.2f".format(Locale.US, b.area)}")
          CompareLine("Fmax (MHz)", "${"%.1f".format(Locale.US, a.fmax)}", "${"%.1f".format(Locale.US, b.fmax)}")
          CompareLine("WNS (ns)", formatSignedNs(a.wns), formatSignedNs(b.wns))
          CompareLine("Power (mW)", "${"%.3f".format(Locale.US, a.power)}", "${"%.3f".format(Locale.US, b.power)}")
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

private data class CompareMetricRow(
  val title: String,
  val cells: Long,
  val area: Double,
  val fmax: Double,
  val wns: Double,
  val power: Double,
)

@Composable
private fun CompareLine(metric: String, valA: String, valB: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(metric, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    Text(
      valA,
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.weight(0.9f),
    )
    Text(
      valB,
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
      color = LocalEdaColors.current.cyanHighlight,
      modifier = Modifier.weight(0.9f),
    )
  }
}

private fun formatSignedNs(v: Double): String =
  if (v >= 0) "+${"%.2f".format(Locale.US, v)} ns" else "${"%.2f".format(Locale.US, v)} ns"
