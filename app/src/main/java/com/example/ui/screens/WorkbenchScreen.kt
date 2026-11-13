package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogParseResult
import com.example.ui.components.ParsedRtlStructureCard
import com.example.ui.components.VerilogDiagnosticFormatter
import com.example.ui.components.VerilogSyntaxHighlighter
import com.example.ui.components.VerilogSyntaxVisualTransformation
import com.example.ui.components.rememberVerilogSyntaxPalette
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.AutoSaveStatus
import com.example.ui.viewmodel.WorkbenchUiState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
  state: WorkbenchUiState,
  onCodeChange: (String) -> Unit,
  onMetaChange: (String, String) -> Unit,
  onSelectLibrary: (TargetLibrary) -> Unit,
  onSelectGoal: (OptimizationGoal) -> Unit,
  onClockPeriodChange: (Double) -> Unit,
  onSelectTopModule: (String) -> Unit,
  onRunSynthesis: () -> Unit,
  onSaveToCloud: () -> Unit,
  onLoadPreset: (Int) -> Unit,
  onNewBlankDesign: () -> Unit,
  onOpenSchematic: () -> Unit,
  modifier: Modifier = Modifier,
  onUpdateSdc: (String) -> Unit = {},
  onResetSdc: () -> Unit = {},
  onImportFile: (String, String) -> Unit = { _, _ -> },
  onNotify: (String) -> Unit = {},
) {
  var outputTab by remember { mutableIntStateOf(0) } // 0 = Console, 1 = Parsed RTL, 2 = Netlist
  var showConstraintsSheet by remember { mutableStateOf(false) }
  var showMetaDialog by remember { mutableStateOf(false) }
  val context = LocalContext.current

  val baseFileName = state.designName.ifBlank { "rtl_module" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
  val synthReport = (state.synthesisResult as? SynthesisResult.Success)?.report

  val importFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      readDocumentText(context, uri)
        .onSuccess { (fileName, text) -> onImportFile(fileName, text) }
        .onFailure { err -> onNotify("Import failed: ${err.localizedMessage ?: "Cannot read file"}") }
    }
  }

  val exportRtlLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri ->
    if (uri != null) {
      writeDocumentText(context, uri, state.verilogCode)
        .onSuccess { onNotify("Exported RTL to $baseFileName.v") }
        .onFailure { err -> onNotify("Export failed: ${err.localizedMessage ?: "Cannot write file"}") }
    }
  }

  val exportSdcLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri ->
    if (uri != null) {
      writeDocumentText(context, uri, state.sdcText)
        .onSuccess { onNotify("Exported SDC constraints to $baseFileName.sdc") }
        .onFailure { err -> onNotify("Export failed: ${err.localizedMessage ?: "Cannot write file"}") }
    }
  }

  val exportNetlistLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri ->
    val netlist = synthReport?.netlistVerilog
    if (uri != null && netlist != null) {
      writeDocumentText(context, uri, netlist)
        .onSuccess { onNotify("Exported synthesized netlist (${synthReport.moduleName}_synth.v)") }
        .onFailure { err -> onNotify("Export failed: ${err.localizedMessage ?: "Cannot write file"}") }
    }
  }

  if (showMetaDialog) {
    EditMetadataDialog(
      initialName = state.designName,
      initialDescription = state.description,
      onDismiss = { showMetaDialog = false },
      onConfirm = { n, d ->
        onMetaChange(n, d)
        showMetaDialog = false
      },
    )
  }

  if (showConstraintsSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showConstraintsSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      ConstraintsSheetContent(
        state = state,
        onSelectLibrary = onSelectLibrary,
        onSelectGoal = onSelectGoal,
        onClockPeriodChange = onClockPeriodChange,
        onUpdateSdc = onUpdateSdc,
        onResetSdc = onResetSdc,
        onEditMetadata = {
          showConstraintsSheet = false
          showMetaDialog = true
        },
        onClose = { showConstraintsSheet = false },
      )
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Slim top IDE file tabs + target settings + files I/O + save
    IdeTopFileBar(
      state = state,
      hasNetlist = synthReport != null,
      onLoadPreset = onLoadPreset,
      onNewBlankDesign = onNewBlankDesign,
      onOpenConstraints = { showConstraintsSheet = true },
      onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
      onExportRtl = { exportRtlLauncher.launch("$baseFileName.v") },
      onExportSdc = { exportSdcLauncher.launch("$baseFileName.sdc") },
      onExportNetlist = {
        if (synthReport != null) {
          exportNetlistLauncher.launch("${synthReport.moduleName}_synth.v")
        } else {
          onNotify("Run synthesis before exporting a gate-level netlist.")
        }
      },
      onSaveToCloud = onSaveToCloud,
    )

    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
      val isWide = maxWidth >= 840.dp
      if (isWide) {
        Row(
          modifier = Modifier.fillMaxSize(),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          RtlCodeEditorCard(
            state = state,
            onCodeChange = onCodeChange,
            onRunSynthesis = onRunSynthesis,
            onEditMeta = { showMetaDialog = true },
            modifier = Modifier
              .weight(1.25f)
              .fillMaxHeight(),
          )
          RunOutputPanel(
            state = state,
            outputTab = outputTab,
            onSelectOutputTab = { outputTab = it },
            onSelectTopModule = onSelectTopModule,
            onOpenSchematic = onOpenSchematic,
            onExportNetlist = {
              if (synthReport != null) {
                exportNetlistLauncher.launch("${synthReport.moduleName}_synth.v")
              }
            },
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight(),
          )
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          RtlCodeEditorCard(
            state = state,
            onCodeChange = onCodeChange,
            onRunSynthesis = onRunSynthesis,
            onEditMeta = { showMetaDialog = true },
            modifier = Modifier.fillMaxWidth(),
          )
          RunOutputPanel(
            state = state,
            outputTab = outputTab,
            onSelectOutputTab = { outputTab = it },
            onSelectTopModule = onSelectTopModule,
            onOpenSchematic = onOpenSchematic,
            onExportNetlist = {
              if (synthReport != null) {
                exportNetlistLauncher.launch("${synthReport.moduleName}_synth.v")
              }
            },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(12.dp))
        }
      }
    }
  }
}

@Composable
private fun IdeTopFileBar(
  state: WorkbenchUiState,
  hasNetlist: Boolean,
  onLoadPreset: (Int) -> Unit,
  onNewBlankDesign: () -> Unit,
  onOpenConstraints: () -> Unit,
  onImportFile: () -> Unit,
  onExportRtl: () -> Unit,
  onExportSdc: () -> Unit,
  onExportNetlist: () -> Unit,
  onSaveToCloud: () -> Unit,
) {
  var showFilesMenu by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // Scrollable file/template tabs
    Row(
      modifier = Modifier
        .weight(1f)
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        onClick = onNewBlankDesign,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag("new_rtl_tab_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "New blank Verilog file",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = "New",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      }

      PresetRtlDesigns.templates.forEachIndexed { idx, preset ->
        val selected = state.activeCloudDesignId == null && state.designName == preset.name
        Surface(
          onClick = { onLoadPreset(idx) },
          color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
          tonalElevation = if (selected) 2.dp else 1.dp,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier
            .border(
              width = 1.dp,
              color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
              shape = MaterialTheme.shapes.small,
            )
            .testTag("preset_chip_${preset.name}"),
        ) {
          Text(
            text = "${preset.name}.v",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.5.sp,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
              color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          )
        }
      }
    }

    // Compact Constraints & Target pill
    Surface(
      onClick = onOpenConstraints,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.small)
        .testTag("open_constraints_sheet_button"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Tune,
          contentDescription = "Configure synthesis target and SDC constraints",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "${state.targetLibrary.shortName} · ${String.format(Locale.US, "%.1f", state.clockPeriodNs)}ns",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }

    // File Import / Export menu button (48dp touch target)
    Box {
      IconButton(
        onClick = { showFilesMenu = true },
        modifier = Modifier
          .size(48.dp)
          .testTag("workbench_files_menu_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.FolderOpen,
          contentDescription = "Import or export Verilog and SDC files",
          tint = MaterialTheme.colorScheme.secondary,
        )
      }
      DropdownMenu(
        expanded = showFilesMenu,
        onDismissRequest = { showFilesMenu = false },
      ) {
        DropdownMenuItem(
          text = { Text("Import .v / .sv / .sdc") },
          leadingIcon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
          onClick = {
            showFilesMenu = false
            onImportFile()
          },
          modifier = Modifier.testTag("menu_import_file"),
        )
        HorizontalDivider()
        DropdownMenuItem(
          text = { Text("Export RTL (.v)") },
          leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
          onClick = {
            showFilesMenu = false
            onExportRtl()
          },
          modifier = Modifier.testTag("menu_export_rtl"),
        )
        DropdownMenuItem(
          text = { Text("Export SDC (.sdc)") },
          leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
          onClick = {
            showFilesMenu = false
            onExportSdc()
          },
          modifier = Modifier.testTag("menu_export_sdc"),
        )
        DropdownMenuItem(
          text = { Text("Export Netlist (_synth.v)") },
          enabled = hasNetlist,
          leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
          onClick = {
            showFilesMenu = false
            onExportNetlist()
          },
          modifier = Modifier.testTag("menu_export_netlist"),
        )
      }
    }

    // Save icon button (48dp touch target) — saves locally offline or syncs to cloud when signed in
    IconButton(
      onClick = onSaveToCloud,
      enabled = !state.isSaving,
      modifier = Modifier
        .size(48.dp)
        .testTag("save_cloud_button"),
    ) {
      if (state.isSaving) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      } else {
        Icon(
          imageVector = if (state.isOfflineMode) Icons.Rounded.Save else Icons.Rounded.CloudUpload,
          contentDescription = when {
            state.isOfflineMode -> "Save module locally on device"
            state.activeCloudDesignId != null -> "Update module in cloud"
            else -> "Save module to cloud"
          },
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun RtlCodeEditorCard(
  state: WorkbenchUiState,
  onCodeChange: (String) -> Unit,
  onRunSynthesis: () -> Unit,
  onEditMeta: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val code = state.verilogCode
  val lineCount = remember(code) { code.lines().size.coerceAtLeast(1) }
  val errorLine = state.parseError?.line ?: (state.synthesisResult as? SynthesisResult.Error)?.line
  val errorColumn = state.parseError?.column
  var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = modifier.border(
      width = 1.dp,
      color = if (state.parseError != null) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
      },
      shape = MaterialTheme.shapes.large,
    ),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Editor header: active module file name + line count + clear
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
          .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier
            .weight(1f)
            .clickable(onClick = onEditMeta)
            .testTag("edit_module_meta_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
          )
          Text(
            text = "${state.designName.ifBlank { "rtl_module" }}.v",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = "Rename module",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
          )
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "$lineCount lines",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Surface(
            onClick = { onCodeChange("") },
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.testTag("clear_editor_button"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Clear RTL editor",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = "Clear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Slim quick-insert snippet strip
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Insert:",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SnippetChip("assign") {
          onCodeChange("$code\n  assign out_sig = a & b;")
        }
        SnippetChip("always_ff") {
          onCodeChange("$code\n  always @(posedge clk) begin\n    q <= d;\n  end")
        }
        SnippetChip("case") {
          onCodeChange("$code\n  case (sel)\n    1'b0: y = a;\n    default: y = b;\n  endcase")
        }
      }

      // Main editable Verilog RTL code area
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 168.dp, max = 320.dp)
          .verticalScroll(rememberScrollState())
          .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
          .padding(12.dp),
      ) {
        Column(
          modifier = Modifier.padding(end = 10.dp),
          horizontalAlignment = Alignment.End,
        ) {
          for (ln in 1..lineCount) {
            val isErr = errorLine == ln
            Box(
              modifier = Modifier
                .then(
                  if (isErr) {
                    Modifier
                      .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                      )
                      .padding(horizontal = 4.dp)
                      .testTag("editor_error_gutter_line_$ln")
                  } else {
                    Modifier.padding(horizontal = 4.dp)
                  }
                ),
              contentAlignment = Alignment.CenterEnd,
            ) {
              Text(
                text = ln.toString(),
                style = TextStyle(
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 12.5.sp,
                  lineHeight = 20.sp,
                  color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                  fontWeight = if (isErr) FontWeight.Bold else FontWeight.Normal,
                ),
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        ) {
          if (code.isEmpty()) {
            Text(
              text = "module top (input clk, output reg y);\n  ...\nendmodule",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
              ),
            )
          }
          val syntaxPalette = rememberVerilogSyntaxPalette()
          val syntaxTransformation = remember(syntaxPalette, errorLine, errorColumn) {
            VerilogSyntaxVisualTransformation(
              palette = syntaxPalette,
              errorLine = errorLine,
              errorColumn = errorColumn,
            )
          }
          val errBandColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
          val errBarColor = MaterialTheme.colorScheme.error
          BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            onTextLayout = { textLayoutResult = it },
            visualTransformation = syntaxTransformation,
            textStyle = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 12.5.sp,
              lineHeight = 20.sp,
              color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 144.dp)
              .drawBehind {
                val layout = textLayoutResult
                val errLn = errorLine
                if (layout != null && errLn != null) {
                  val range = VerilogSyntaxHighlighter.lineRangeFor(code, errLn)
                  if (range != null && range.first <= layout.layoutInput.text.length) {
                    val safeOffset = range.first.coerceIn(0, layout.layoutInput.text.length)
                    val visualLine = layout.getLineForOffset(safeOffset)
                    val top = layout.getLineTop(visualLine)
                    val bottom = layout.getLineBottom(visualLine)
                    drawRect(
                      color = errBandColor,
                      topLeft = Offset(0f, top),
                      size = Size(size.width, (bottom - top).coerceAtLeast(1f)),
                    )
                    drawRect(
                      color = errBarColor,
                      topLeft = Offset(0f, top),
                      size = Size(3.dp.toPx(), (bottom - top).coerceAtLeast(1f)),
                    )
                  }
                }
              }
              .testTag("verilog_code_editor"),
          )
        }
      }

      // Live real-time diagnostics panel directly below the code editor
      LiveEditorDiagnosticsPanel(
        source = code,
        parseError = state.parseError,
        warnings = state.parsedDesign?.diagnostics.orEmpty(),
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

      // Integrated Bottom Action Bar: Live Parser indicator + prominent RUN button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
          .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val statusIcon = if (state.parseError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle
        val statusColor = if (state.parseError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        val statusText = when {
          state.parseError != null -> "Line ${state.parseError.line}:${state.parseError.column} error"
          state.parsedDesign != null -> {
            val top = state.parsedDesign.topModule
            "${top.name} · ${top.ports.size} ports · ${top.internalSignals.size} sigs"
          }
          else -> "Ready to parse"
        }

        val autoSaveIcon = when (state.autoSaveStatus) {
          AutoSaveStatus.SAVING -> Icons.Rounded.CloudSync
          AutoSaveStatus.SAVED -> Icons.Rounded.CloudDone
          AutoSaveStatus.ERROR -> Icons.Rounded.WarningAmber
          AutoSaveStatus.OFFLINE -> Icons.Rounded.CloudOff
          AutoSaveStatus.IDLE -> Icons.Rounded.CloudQueue
        }
        val autoSaveColor = when (state.autoSaveStatus) {
          AutoSaveStatus.SAVING -> MaterialTheme.colorScheme.primary
          AutoSaveStatus.SAVED -> MaterialTheme.colorScheme.tertiary
          AutoSaveStatus.ERROR -> MaterialTheme.colorScheme.error
          AutoSaveStatus.OFFLINE, AutoSaveStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val autoSaveText = when (state.autoSaveStatus) {
          AutoSaveStatus.SAVING -> "Auto-saving to cloud..."
          AutoSaveStatus.SAVED -> "Auto-saved ${state.lastAutoSavedLabel ?: "to cloud"}"
          AutoSaveStatus.ERROR -> "Auto-save retry pending"
          AutoSaveStatus.OFFLINE -> "Offline · local draft saved"
          AutoSaveStatus.IDLE -> "Cloud auto-save active"
        }

        Column(
          modifier = Modifier
            .weight(1f)
            .padding(end = 10.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = statusIcon,
              contentDescription = null,
              tint = statusColor,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = statusText,
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.5.sp,
                color = statusColor,
              ),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.testTag("autosave_status_label"),
          ) {
            Icon(
              imageVector = autoSaveIcon,
              contentDescription = null,
              tint = autoSaveColor,
              modifier = Modifier.size(13.dp),
            )
            Text(
              text = autoSaveText,
              style = MaterialTheme.typography.labelSmall,
              color = autoSaveColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Button(
          onClick = onRunSynthesis,
          enabled = !state.isSynthesizing,
          shape = MaterialTheme.shapes.medium,
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("run_synthesis_button"),
        ) {
          if (state.isSynthesizing) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Running...", style = MaterialTheme.typography.labelLarge)
          } else {
            Icon(
              imageVector = Icons.Rounded.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Run", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun LiveEditorDiagnosticsPanel(
  source: String,
  parseError: VerilogParseResult.Error?,
  warnings: List<String>,
) {
  AnimatedVisibility(visible = parseError != null) {
    val err = parseError ?: return@AnimatedVisibility
    val snippet = remember(source, err) { VerilogDiagnosticFormatter.buildSnippet(source, err) }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("editor_error_banner"),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.ErrorOutline,
          contentDescription = "Syntax error",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = "Syntax Error · Line ${err.line}, Col ${err.column}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Text(
        text = err.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (snippet.lineText.isNotEmpty()) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
              shape = MaterialTheme.shapes.small,
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
          Text(
            text = "${err.line} | ${snippet.lineText}",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onBackground,
            ),
          )
          val prefixSpaces = " ".repeat(err.line.toString().length + 3)
          Text(
            text = "$prefixSpaces${snippet.caretLine}",
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.error,
            ),
          )
        }
      }
      Text(
        text = snippet.hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  AnimatedVisibility(visible = parseError == null && warnings.isNotEmpty()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("editor_warning_banner"),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      warnings.forEach { warning ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = "RTL lint warning",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = warning,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
private fun RunOutputPanel(
  state: WorkbenchUiState,
  outputTab: Int,
  onSelectOutputTab: (Int) -> Unit,
  onSelectTopModule: (String) -> Unit,
  onOpenSchematic: () -> Unit,
  onExportNetlist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    RunResultStrip(
      result = state.synthesisResult,
      onOpenSchematic = onOpenSchematic,
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      val tabs = listOf("Console", "Parsed RTL", "Netlist")
      tabs.forEachIndexed { idx, label ->
        SegmentedButton(
          selected = outputTab == idx,
          onClick = { onSelectOutputTab(idx) },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
          modifier = Modifier.testTag("output_tab_${label.lowercase().replace(" ", "_")}"),
        ) {
          Text(label, style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    when (outputTab) {
      0 -> ConsoleLogBox(result = state.synthesisResult)
      1 -> ParsedRtlStructureCard(
        parsedDesign = state.parsedDesign,
        parseError = state.parseError,
        onSelectTopModule = onSelectTopModule,
      )
      else -> QuickNetlistBox(
        result = state.synthesisResult,
        onOpenSchematic = onOpenSchematic,
        onExportNetlist = onExportNetlist,
      )
    }
  }
}

@Composable
private fun RunResultStrip(
  result: SynthesisResult?,
  onOpenSchematic: () -> Unit,
) {
  when (result) {
    is SynthesisResult.Success -> {
      val r = result.report
      val timingMet = r.wnsNs >= 0.0
      val accent = if (timingMet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
      Surface(
        color = accent.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, accent.copy(alpha = 0.35f), MaterialTheme.shapes.medium),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = if (timingMet) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
              contentDescription = null,
              tint = accent,
              modifier = Modifier.size(20.dp),
            )
            Column {
              Text(
                text = if (timingMet) "Synthesis Passed · Timing Met" else "Synthesis Passed · Timing Violation",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${r.cellCount} cells · ${String.format(Locale.US, "%.1f", r.fmaxMhz)} MHz · WNS ${if (r.wnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", r.wnsNs)} ns",
                style = TextStyle(
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
              )
            }
          }
          TextButton(
            onClick = onOpenSchematic,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("view_schematic_cta"),
          ) {
            Text("Schematic", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
              contentDescription = "Open gate-level schematic",
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }
    is SynthesisResult.Error -> {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f), MaterialTheme.shapes.medium),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
          )
          Column {
            Text(
              text = "Verilog Error (Line ${result.line})",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              text = result.message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
    null -> Unit
  }
}

@Composable
private fun ConsoleLogBox(result: SynthesisResult?) {
  val logs = when (result) {
    is SynthesisResult.Success -> result.report.synthesisLog
    is SynthesisResult.Error -> result.log
    null -> listOf("Write Verilog RTL above and tap Run to synthesize.")
  }
  val warnings = (result as? SynthesisResult.Success)?.report?.warnings ?: emptyList()

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      logs.forEach { line ->
        val color = when {
          line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
          line.contains("STA complete") || line.contains("finished") -> MaterialTheme.colorScheme.tertiary
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
          text = line,
          style = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = color,
          ),
        )
      }
      warnings.forEach { warn ->
        Text(
          text = "[WARN] $warn",
          style = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.secondary,
          ),
        )
      }
    }
  }
}

@Composable
private fun QuickNetlistBox(
  result: SynthesisResult?,
  onOpenSchematic: () -> Unit,
  onExportNetlist: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  val report = (result as? SynthesisResult.Success)?.report
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
  ) {
    if (report == null) {
      Text(
        text = "Run valid Verilog RTL to inspect the synthesized gate-level netlist.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(14.dp),
      )
    } else {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${report.moduleName}_synth.v (${report.cellCount} cells)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSchematic) {
              Text("Diagram", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
              onClick = onExportNetlist,
              modifier = Modifier
                .size(48.dp)
                .testTag("export_netlist_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Export synthesized Verilog netlist",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
              )
            }
            IconButton(
              onClick = { clipboard.setText(AnnotatedString(report.netlistVerilog)) },
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = "Copy structural Verilog",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        ) {
          Text(
            text = report.netlistVerilog,
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.5.sp,
              lineHeight = 17.sp,
              color = MaterialTheme.colorScheme.onBackground,
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun ConstraintsSheetContent(
  state: WorkbenchUiState,
  onSelectLibrary: (TargetLibrary) -> Unit,
  onSelectGoal: (OptimizationGoal) -> Unit,
  onClockPeriodChange: (Double) -> Unit,
  onUpdateSdc: (String) -> Unit,
  onResetSdc: () -> Unit,
  onEditMetadata: () -> Unit,
  onClose: () -> Unit,
) {
  var sdcDraft by remember(state.sdcText) { mutableStateOf(state.sdcText) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Synthesis Target & Constraints",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = state.designName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onEditMetadata) {
        Text("Edit Module Info")
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "Technology Library",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TargetLibrary.entries.forEach { lib ->
          FilterChip(
            selected = lib == state.targetLibrary,
            onClick = { onSelectLibrary(lib) },
            label = { Text(lib.shortName, style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag("lib_chip_${lib.id}"),
          )
        }
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "Optimization Objective",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OptimizationGoal.entries.forEach { g ->
          FilterChip(
            selected = g == state.optimizationGoal,
            onClick = { onSelectGoal(g) },
            label = { Text(g.displayName, style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("goal_chip_${g.id}"),
          )
        }
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val targetMhz = 1000.0 / state.clockPeriodNs.coerceAtLeast(0.1)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Clock Period Constraint",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "${String.format(Locale.US, "%.1f", state.clockPeriodNs)} ns (${targetMhz.roundToInt()} MHz)",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Slider(
        value = state.clockPeriodNs.toFloat().coerceIn(0.4f, 15.0f),
        onValueChange = { raw ->
          val stepped = (raw * 10f).roundToInt() / 10.0
          onClockPeriodChange(stepped)
        },
        valueRange = 0.4f..15.0f,
        colors = SliderDefaults.colors(
          thumbColor = MaterialTheme.colorScheme.primary,
          activeTrackColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.testTag("clock_period_slider"),
      )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

    // SDC Timing Constraints Editor
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "SDC Timing Constraints",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = sdcDraft,
        onValueChange = { sdcDraft = it },
        label = { Text("create_clock, set_input_delay, set_output_delay, set_false_path...") },
        textStyle = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 11.5.sp,
          lineHeight = 16.sp,
        ),
        minLines = 4,
        maxLines = 8,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("sdc_constraints_editor"),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onResetSdc,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("reset_sdc_button"),
        ) {
          Text("Reset SDC", style = MaterialTheme.typography.labelMedium)
        }
        Button(
          onClick = { onUpdateSdc(sdcDraft) },
          modifier = Modifier
            .weight(1.4f)
            .heightIn(min = 48.dp)
            .testTag("apply_sdc_button"),
        ) {
          Text("Apply SDC", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    Button(
      onClick = onClose,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp),
    ) {
      Text("Done")
    }
    Spacer(Modifier.height(12.dp))
  }
}

@Composable
private fun SnippetChip(label: String, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    shape = MaterialTheme.shapes.extraSmall,
  ) {
    Text(
      text = "+$label",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun EditMetadataDialog(
  initialName: String,
  initialDescription: String,
  onDismiss: () -> Unit,
  onConfirm: (String, String) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var desc by remember { mutableStateOf(initialDescription) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Module Details", style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Module Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = desc,
          onValueChange = { desc = it },
          label = { Text("Architecture Description") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(name.ifBlank { initialName }, desc) },
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

private fun readDocumentText(context: Context, uri: Uri): Result<Pair<String, String>> = runCatching {
  var displayName: String? = null
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) {
        displayName = cursor.getString(idx)
      }
    }
  }
  val resolvedName = displayName?.takeIf { it.isNotBlank() }
    ?: uri.lastPathSegment?.substringAfterLast('/')
    ?: "imported_design.v"

  val content = context.contentResolver.openInputStream(uri)?.use { stream ->
    stream.bufferedReader(Charsets.UTF_8).readText()
  } ?: error("Empty or unreadable file stream.")

  resolvedName to content
}

private fun writeDocumentText(context: Context, uri: Uri, content: String): Result<Unit> = runCatching {
  context.contentResolver.openOutputStream(uri)?.use { stream ->
    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
      writer.write(content)
    }
  } ?: error("Unable to open output stream for writing.")
}

