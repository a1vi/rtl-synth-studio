package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogParseResult
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EditorDrcDiagnosticsDrawer
import com.example.ui.components.VerilogDiagnosticFormatter
import com.example.ui.components.VerilogSyntaxVisualTransformation
import com.example.ui.components.rememberVerilogSyntaxPalette
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.AutoSaveStatus
import com.example.ui.viewmodel.WorkbenchUiState
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun EditorScreen(
  state: WorkbenchUiState,
  settings: AppSettings,
  onFileContentChange: (String) -> Unit,
  onSelectFileTab: (String) -> Unit,
  onCloseFileTab: (String) -> Unit,
  onAddFile: (String, RtlLanguage) -> Unit,
  onDeleteFile: (String) -> Unit,
  onRunSynthesis: () -> Unit,
  onSaveProject: () -> Unit,
  onOpenReports: () -> Unit,
  onImportFile: (String, String) -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
  onConsumePendingJump: () -> Unit = {},
  onRunRtlDrc: () -> Unit = {},
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val activeFile = state.activeFile
  val activeContent = activeFile?.content ?: state.verilogCode
  val activeLang = activeFile?.language ?: state.primaryLanguage

  var showExplorerSheet by remember { mutableStateOf(false) }
  var showTemplateSheet by remember { mutableStateOf(false) }
  var showFindBar by rememberSaveable { mutableStateOf(false) }
  var showOutlineStrip by rememberSaveable { mutableStateOf(false) }
  var showDrcPanel by rememberSaveable { mutableStateOf(false) }
  var collapsedFoldLines by remember(activeFile?.id) { mutableStateOf(setOf<Int>()) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var replacementText by rememberSaveable { mutableStateOf("") }
  var showFileMenu by remember { mutableStateOf(false) }
  var pendingExportPayload by remember { mutableStateOf<String?>(null) }
  var clearedBackup by remember(activeFile?.id) { mutableStateOf<String?>(null) }

  val undoHistory = remember(activeFile?.id) { mutableStateListOf<TextFieldValue>() }
  val redoHistory = remember(activeFile?.id) { mutableStateListOf<TextFieldValue>() }
  var codeFieldValue by remember(activeFile?.id) {
    mutableStateOf(TextFieldValue(activeContent, TextRange(activeContent.length)))
  }
  LaunchedEffect(activeContent) {
    if (codeFieldValue.text != activeContent) {
      val clampedSel = codeFieldValue.selection.start.coerceIn(0, activeContent.length)
      codeFieldValue = TextFieldValue(activeContent, TextRange(clampedSel))
    }
  }

  fun commitCodeEdit(nextValue: TextFieldValue, recordUndo: Boolean = true) {
    if (recordUndo && nextValue.text != codeFieldValue.text) {
      undoHistory.add(codeFieldValue)
      if (undoHistory.size > 64) undoHistory.removeAt(0)
      redoHistory.clear()
    }
    codeFieldValue = nextValue
    if (nextValue.text != activeContent) {
      onFileContentChange(nextValue.text)
    }
  }

  fun performUndo() {
    if (undoHistory.isEmpty()) return
    redoHistory.add(codeFieldValue)
    val prev = undoHistory.removeAt(undoHistory.lastIndex)
    codeFieldValue = prev
    onFileContentChange(prev.text)
  }

  fun performRedo() {
    if (redoHistory.isEmpty()) return
    undoHistory.add(codeFieldValue)
    val next = redoHistory.removeAt(redoHistory.lastIndex)
    codeFieldValue = next
    onFileContentChange(next.text)
  }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri != null) {
      val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.v"
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()
      }.onSuccess { text ->
        if (text.isNotBlank()) onImportFile(fileName, text)
      }.onFailure {
        onNotify("Failed to read file: ${it.localizedMessage ?: "error"}")
      }
    }
  }

  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri: Uri? ->
    val payload = pendingExportPayload
    pendingExportPayload = null
    if (uri != null && payload != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(payload.toByteArray(Charsets.UTF_8))
        }
      }.onSuccess {
        onNotify("Exported file successfully.")
      }.onFailure {
        onNotify("Export failed: ${it.localizedMessage ?: "error"}")
      }
    }
  }

  val searchMatches = remember(activeContent, searchQuery) {
    if (searchQuery.isBlank()) 0
    else Regex.escape(searchQuery).toRegex(RegexOption.IGNORE_CASE).findAll(activeContent).count()
  }

  val foldRegions = remember(activeContent) {
    EditorCodeHelpers.extractFoldRegions(activeContent)
  }

  if (showExplorerSheet) {
    ProjectExplorerAndOutlineSheet(
      files = state.files,
      activeFileId = state.activeFileId,
      foldRegions = foldRegions,
      onSelectFile = onSelectFileTab,
      onAddFile = onAddFile,
      onDeleteFile = onDeleteFile,
      onDismiss = { showExplorerSheet = false },
    )
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Compact IDE Toolbar + Multi-File Tabs
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      IconButton(
        onClick = { showExplorerSheet = true },
        modifier = Modifier.size(44.dp).testTag("editor_explorer_button"),
      ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = "Project Explorer", tint = eda.cyanHighlight)
      }

      Row(
        modifier = Modifier
          .weight(1f)
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val openFiles = state.files.filter { it.id in state.openTabIds }.ifEmpty {
          listOfNotNull(activeFile)
        }
        openFiles.forEach { file ->
          val selected = file.id == activeFile?.id
          FileTabChip(
            file = file,
            selected = selected,
            canClose = openFiles.size > 1,
            onSelect = { onSelectFileTab(file.id) },
            onClose = { onCloseFileTab(file.id) },
          )
        }
      }

      IconButton(
        onClick = { showTemplateSheet = true },
        modifier = Modifier.size(48.dp).testTag("editor_templates_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Code,
          contentDescription = "Quick-insert HDL templates",
          tint = eda.cyanHighlight,
        )
      }

      IconButton(
        onClick = { showFindBar = !showFindBar },
        modifier = Modifier.size(44.dp).testTag("editor_find_toggle_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.FindReplace,
          contentDescription = "Find and Replace",
          tint = if (showFindBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      IconButton(
        onClick = {
          if (activeContent.isNotEmpty()) {
            clearedBackup = activeContent
            commitCodeEdit(TextFieldValue("", TextRange(0)))
            onNotify("Cleared ${activeFile?.name ?: "editor"}.")
          }
        },
        enabled = activeContent.isNotEmpty(),
        modifier = Modifier.size(44.dp).testTag("editor_clear_button"),
      ) {
        Icon(
          imageVector = Icons.Rounded.DeleteSweep,
          contentDescription = "Clear editor",
          tint = if (activeContent.isNotEmpty()) {
            eda.warning
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
          },
        )
      }

      IconButton(
        onClick = onSaveProject,
        modifier = Modifier.size(44.dp).testTag("editor_save_button"),
      ) {
        Icon(Icons.Rounded.Save, contentDescription = "Save Project", tint = MaterialTheme.colorScheme.primary)
      }

      Box {
        IconButton(
          onClick = { showFileMenu = true },
          modifier = Modifier.size(44.dp).testTag("editor_more_menu_button"),
        ) {
          Icon(Icons.Rounded.MoreVert, contentDescription = "File actions")
        }
        DropdownMenu(expanded = showFileMenu, onDismissRequest = { showFileMenu = false }) {
          DropdownMenuItem(
            text = { Text("Import HDL / SDC File") },
            onClick = {
              showFileMenu = false
              importLauncher.launch(arrayOf("*/*"))
            },
          )
          DropdownMenuItem(
            text = { Text("Copy Selection / File") },
            onClick = {
              showFileMenu = false
              val sel = codeFieldValue.selection
              val chunk = if (!sel.collapsed) {
                codeFieldValue.text.substring(sel.min.coerceIn(0, codeFieldValue.text.length), sel.max.coerceIn(0, codeFieldValue.text.length))
              } else {
                activeContent
              }
              clipboard.setText(AnnotatedString(chunk))
              onNotify(if (!sel.collapsed) "Copied selection to clipboard." else "Copied file to clipboard.")
            },
          )
          DropdownMenuItem(
            text = { Text("Paste at Cursor") },
            onClick = {
              showFileMenu = false
              val pasted = clipboard.getText()?.text
              if (!pasted.isNullOrEmpty()) {
                val text = codeFieldValue.text
                val s = codeFieldValue.selection.min.coerceIn(0, text.length)
                val e = codeFieldValue.selection.max.coerceIn(0, text.length)
                val updated = text.substring(0, s) + pasted + text.substring(e)
                commitCodeEdit(TextFieldValue(updated, TextRange(s + pasted.length)))
              } else {
                onNotify("Clipboard is empty.")
              }
            },
          )
          DropdownMenuItem(
            text = { Text("Clear Active File") },
            enabled = activeContent.isNotEmpty(),
            onClick = {
              showFileMenu = false
              if (activeContent.isNotEmpty()) {
                clearedBackup = activeContent
                commitCodeEdit(TextFieldValue("", TextRange(0)))
                onNotify("Cleared ${activeFile?.name ?: "editor"}.")
              }
            },
          )
          DropdownMenuItem(
            text = { Text("Export Active File") },
            onClick = {
              showFileMenu = false
              pendingExportPayload = activeContent
              exportLauncher.launch(activeFile?.name ?: "module.v")
            },
          )
          DropdownMenuItem(
            text = { Text("Export Gate-Level Netlist") },
            enabled = state.synthesisResult is SynthesisResult.Success,
            onClick = {
              showFileMenu = false
              val net = (state.synthesisResult as? SynthesisResult.Success)?.report?.netlistVerilog
              if (net != null) {
                pendingExportPayload = net
                exportLauncher.launch("${state.designName}_synth.v")
              }
            },
          )
        }
      }
    }

    // 1b. Secondary IDE Action Ribbon (Undo, Redo, Copy, Paste, Fold Toggle + Module/Port Quick-Jump)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val linesCount = remember(activeContent) { activeContent.count { it == '\n' } + 1 }

    fun jumpToEditorLine(lineNo: Int) {
      val targetLine = lineNo.coerceIn(1, linesCount.coerceAtLeast(1))
      val offset = EditorCodeHelpers.offsetForLine(activeContent, targetLine)
      codeFieldValue = TextFieldValue(activeContent, TextRange(offset))
      val targetScroll = (((targetLine - 1).toFloat() / linesCount.coerceAtLeast(1).toFloat()) * verticalScroll.maxValue).toInt()
      coroutineScope.launch {
        verticalScroll.animateScrollTo(targetScroll.coerceIn(0, verticalScroll.maxValue))
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .testTag("editor_action_ribbon"),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        onClick = ::performUndo,
        enabled = undoHistory.isNotEmpty(),
        color = eda.elevatedCard,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = if (undoHistory.isNotEmpty()) 0.7f else 0.3f)),
        modifier = Modifier.heightIn(min = 36.dp).testTag("editor_undo_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            Icons.AutoMirrored.Rounded.Undo,
            contentDescription = "Undo",
            tint = if (undoHistory.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
          )
          Text("Undo", style = MaterialTheme.typography.labelSmall)
        }
      }
      Surface(
        onClick = ::performRedo,
        enabled = redoHistory.isNotEmpty(),
        color = eda.elevatedCard,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = if (redoHistory.isNotEmpty()) 0.7f else 0.3f)),
        modifier = Modifier.heightIn(min = 36.dp).testTag("editor_redo_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            Icons.AutoMirrored.Rounded.Redo,
            contentDescription = "Redo",
            tint = if (redoHistory.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
          )
          Text("Redo", style = MaterialTheme.typography.labelSmall)
        }
      }
      Surface(
        onClick = {
          val sel = codeFieldValue.selection
          val chunk = if (!sel.collapsed) {
            codeFieldValue.text.substring(sel.min.coerceIn(0, codeFieldValue.text.length), sel.max.coerceIn(0, codeFieldValue.text.length))
          } else {
            activeContent
          }
          clipboard.setText(AnnotatedString(chunk))
          onNotify(if (!sel.collapsed) "Copied selection." else "Copied file.")
        },
        color = eda.elevatedCard,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
        modifier = Modifier.heightIn(min = 36.dp).testTag("editor_copy_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = eda.cyanHighlight, modifier = Modifier.size(14.dp))
          Text("Copy", style = MaterialTheme.typography.labelSmall)
        }
      }
      Surface(
        onClick = {
          val pasted = clipboard.getText()?.text
          if (!pasted.isNullOrEmpty()) {
            val text = codeFieldValue.text
            val s = codeFieldValue.selection.min.coerceIn(0, text.length)
            val e = codeFieldValue.selection.max.coerceIn(0, text.length)
            val updated = text.substring(0, s) + pasted + text.substring(e)
            commitCodeEdit(TextFieldValue(updated, TextRange(s + pasted.length)))
          } else {
            onNotify("Clipboard is empty.")
          }
        },
        color = eda.elevatedCard,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
        modifier = Modifier.heightIn(min = 36.dp).testTag("editor_paste_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = eda.cyanHighlight, modifier = Modifier.size(14.dp))
          Text("Paste", style = MaterialTheme.typography.labelSmall)
        }
      }
      if (foldRegions.isNotEmpty()) {
        Surface(
          onClick = { showOutlineStrip = !showOutlineStrip },
          color = if (showOutlineStrip) MaterialTheme.colorScheme.primaryContainer else eda.elevatedCard,
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, if (showOutlineStrip) MaterialTheme.colorScheme.primary else eda.subtleBorder.copy(alpha = 0.65f)),
          modifier = Modifier.heightIn(min = 36.dp).testTag("editor_fold_toggle_button"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              if (showOutlineStrip) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
              contentDescription = "Toggle code folding bar",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(14.dp),
            )
            Text("Fold (${foldRegions.size})", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      // Detected Modules & Ports Quick-Jump Chips
      val detectedModules = state.parsedDesign?.modules.orEmpty()
      if (detectedModules.isNotEmpty()) {
        detectedModules.forEach { mod ->
          Surface(
            onClick = {
              val line = findTokenDeclarationLine(activeContent, mod.name)
              jumpToEditorLine(line)
            },
            color = eda.codeSurface,
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            modifier = Modifier.heightIn(min = 36.dp).testTag("editor_jump_module_${mod.name}"),
          ) {
            Text(
              text = "mod:${mod.name}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
          }
        }
        state.parsedDesign?.topModule?.ports?.take(8)?.forEach { port ->
          val dirBadge = when (port.direction) {
            com.example.domain.synth.PortDirection.INPUT -> "in"
            com.example.domain.synth.PortDirection.OUTPUT -> "out"
            com.example.domain.synth.PortDirection.INOUT -> "io"
          }
          val portColor = when (port.direction) {
            com.example.domain.synth.PortDirection.INPUT -> eda.success
            com.example.domain.synth.PortDirection.OUTPUT -> eda.cyanHighlight
            com.example.domain.synth.PortDirection.INOUT -> eda.warning
          }
          Surface(
            onClick = {
              val line = findTokenDeclarationLine(activeContent, port.name)
              jumpToEditorLine(line)
            },
            color = eda.codeSurface,
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(1.dp, portColor.copy(alpha = 0.45f)),
            modifier = Modifier.heightIn(min = 36.dp).testTag("editor_port_chip_${port.name}"),
          ) {
            Text(
              text = "$dirBadge ${port.name}${if (port.width > 1) "[${port.width}]" else ""}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = portColor,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            )
          }
        }
      }
    }

    // 1c. Collapsible Inline Code Folding Strip
    if (showOutlineStrip && foldRegions.isNotEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .testTag("editor_fold_regions_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        foldRegions.forEach { region ->
          val folded = region.startLine in collapsedFoldLines
          Surface(
            onClick = {
              collapsedFoldLines = if (folded) collapsedFoldLines - region.startLine else collapsedFoldLines + region.startLine
              jumpToEditorLine(region.startLine)
            },
            color = if (folded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else eda.elevatedCard,
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(1.dp, if (folded) MaterialTheme.colorScheme.primary else eda.subtleBorder),
            modifier = Modifier.testTag("editor_fold_block_${region.startLine}"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = if (folded) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = "L${region.startLine}-${region.endLine}: ${region.title}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
              )
            }
          }
        }
      }
    }

    // 2. Search & Replace Bar (Collapsible)
    if (showFindBar) {
      SearchReplaceBar(
        query = searchQuery,
        replacement = replacementText,
        matchCount = searchMatches,
        onQueryChange = { searchQuery = it },
        onReplacementChange = { replacementText = it },
        onReplaceNext = {
          if (searchQuery.isNotEmpty()) {
            val idx = activeContent.indexOf(searchQuery, ignoreCase = true)
            if (idx >= 0) {
              val next = activeContent.substring(0, idx) + replacementText +
                activeContent.substring(idx + searchQuery.length)
              commitCodeEdit(TextFieldValue(next, TextRange((idx + replacementText.length).coerceIn(0, next.length))))
            }
          }
        },
        onReplaceAll = {
          if (searchQuery.isNotEmpty()) {
            val next = Regex.escape(searchQuery).toRegex(RegexOption.IGNORE_CASE)
              .replace(activeContent, Regex.escapeReplacement(replacementText))
            commitCodeEdit(TextFieldValue(next, TextRange(next.length)))
          }
        },
        onClose = { showFindBar = false },
      )
    }

    // 3. Dedicated Dark Coding Surface with Line Numbers, Error Gutter & Highlighting
    val syntaxPalette = rememberVerilogSyntaxPalette()
    val showRtlError = activeLang in setOf(RtlLanguage.VERILOG, RtlLanguage.SYSTEM_VERILOG, RtlLanguage.VHDL)
    val errorLine = if (showRtlError) state.parseError?.line else null
    val errorColumn = if (showRtlError) state.parseError?.column else null
    val visualTransformation = remember(syntaxPalette, errorLine, errorColumn, activeLang, searchQuery) {
      VerilogSyntaxVisualTransformation(
        palette = syntaxPalette,
        errorLine = errorLine,
        errorColumn = errorColumn,
        language = activeLang,
        searchQuery = searchQuery,
      )
    }
    val codeFontSize = settings.editorFontSizeSp.sp
    val codeLineHeight = (settings.editorFontSizeSp + 6).sp
    val codeStyle = remember(codeFontSize, codeLineHeight) {
      TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = codeFontSize,
        lineHeight = codeLineHeight,
      )
    }

    val bracketStatus = remember(activeContent) {
      EditorCodeHelpers.checkBracketBalance(activeContent)
    }

    val jumpToEditorLine: (Int) -> Unit = { line ->
      val targetLine = line.coerceIn(1, linesCount.coerceAtLeast(1))
      val offset = EditorCodeHelpers.offsetForLine(activeContent, targetLine)
      codeFieldValue = TextFieldValue(activeContent, TextRange(offset))
      val targetScroll = (((targetLine - 1).toFloat() / linesCount.coerceAtLeast(1).toFloat()) * verticalScroll.maxValue).toInt()
      coroutineScope.launch {
        verticalScroll.animateScrollTo(targetScroll.coerceIn(0, verticalScroll.maxValue))
      }
      onNotify("Jumped cursor to line $targetLine")
    }

    LaunchedEffect(state.pendingEditorJump, activeFile?.id, activeContent) {
      val jump = state.pendingEditorJump
      if (jump != null && (jump.first == null || jump.first == activeFile?.id)) {
        val targetLine = jump.second.coerceIn(1, linesCount.coerceAtLeast(1))
        val offset = EditorCodeHelpers.offsetForLine(activeContent, targetLine)
        codeFieldValue = TextFieldValue(activeContent, TextRange(offset))
        val targetScroll = (((targetLine - 1).toFloat() / linesCount.coerceAtLeast(1).toFloat()) * verticalScroll.maxValue).toInt()
        verticalScroll.animateScrollTo(targetScroll.coerceIn(0, verticalScroll.maxValue))
        onConsumePendingJump()
      }
    }

    fun insertAtCursor(snippet: String) {
      val text = codeFieldValue.text
      val start = codeFieldValue.selection.min.coerceIn(0, text.length)
      val end = codeFieldValue.selection.max.coerceIn(0, text.length)
      val updated = text.substring(0, start) + snippet + text.substring(end)
      val nextCursor = start + snippet.length
      commitCodeEdit(TextFieldValue(updated, TextRange(nextCursor)))
    }

    if (showTemplateSheet) {
      QuickTemplateDrawerSheet(
        onInsertTemplate = { snippet ->
          insertAtCursor(snippet)
          onNotify("Inserted RTL template snippet.")
        },
        onDismiss = { showTemplateSheet = false },
      )
    }

    Surface(
      color = eda.codeSurface,
      shape = MaterialTheme.shapes.medium,
      border = BorderStroke(
        width = 1.dp,
        color = if (errorLine != null) eda.error.copy(alpha = 0.65f) else eda.subtleBorder.copy(alpha = 0.8f),
      ),
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Editor Status Header Strip
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(eda.elevatedCard)
            .padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
          ) {
            EdaStatusPill(
              text = activeLang.badge,
              color = eda.cyanHighlight,
              containerColor = eda.cyanContainer,
            )
            val incNote = if (state.resolvedIncludes.isNotEmpty()) {
              " · inc:${state.resolvedIncludes.joinToString(",")}"
            } else ""
            Text(
              text = "${linesCount}L · ${bracketStatus.second}$incNote",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = if (bracketStatus.first) MaterialTheme.colorScheme.onSurfaceVariant else eda.warning,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          val backupToRestore = clearedBackup
          if (activeContent.isEmpty() && backupToRestore != null) {
            Surface(
              onClick = {
                clearedBackup = null
                commitCodeEdit(TextFieldValue(backupToRestore, TextRange(backupToRestore.length)))
                onNotify("Restored cleared buffer.")
              },
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
              shape = MaterialTheme.shapes.extraSmall,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
              modifier = Modifier.padding(end = 8.dp).testTag("editor_undo_clear_button"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo Clear", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(
                  text = "Undo Clear",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          val drcReport = if (showRtlError && state.parseError == null) state.rtlDrcReport else null
          if (drcReport != null) {
            val drcColor = when {
              drcReport.errorCount > 0 -> eda.error
              drcReport.warningCount > 0 -> eda.warning
              else -> eda.success
            }
            val drcContainer = when {
              drcReport.errorCount > 0 -> eda.errorContainer
              drcReport.warningCount > 0 -> eda.warningContainer
              else -> eda.successContainer
            }
            val drcLabel = when {
              drcReport.errorCount > 0 || drcReport.warningCount > 0 ->
                "DRC ${drcReport.errorCount}E/${drcReport.warningCount}W"
              else -> "DRC CLEAN"
            }
            Surface(
              onClick = { showDrcPanel = !showDrcPanel },
              color = drcContainer,
              shape = MaterialTheme.shapes.extraSmall,
              border = BorderStroke(1.dp, drcColor.copy(alpha = 0.6f)),
              modifier = Modifier.padding(end = 6.dp).testTag("editor_drc_pill"),
            ) {
              Text(
                text = drcLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.SemiBold,
                ),
                color = drcColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
              )
            }
          }

          val autoSaveText = when (state.autoSaveStatus) {
            AutoSaveStatus.SAVING -> "Saving..."
            AutoSaveStatus.SAVED -> state.lastAutoSavedLabel?.let { "Auto-saved $it" } ?: "Auto-saved"
            AutoSaveStatus.IDLE -> if (state.hasUnsavedChanges) "Modified" else "Saved"
          }
          Text(
            text = autoSaveText,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = if (state.autoSaveStatus == AutoSaveStatus.SAVED) eda.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("autosave_status_label"),
          )
        }

        HorizontalDivider(color = eda.subtleBorder.copy(alpha = if (eda.isDark) 0.6f else 0.9f))

        // Scrollable Code + Gutter Viewport
        Row(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(verticalScroll)
            .padding(vertical = 10.dp),
        ) {
          if (settings.showLineNumbers) {
            Column(
              modifier = Modifier
                .width(44.dp)
                .background(if (eda.isDark) eda.codeSurface else eda.elevatedCard.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp),
              horizontalAlignment = Alignment.End,
            ) {
              for (ln in 1..linesCount) {
                val isErr = errorLine == ln
                Text(
                  text = ln.toString(),
                  style = codeStyle.copy(
                    color = if (isErr) {
                      eda.error
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (eda.isDark) 0.55f else 0.85f)
                    },
                    fontWeight = if (isErr) FontWeight.Bold else FontWeight.Normal,
                  ),
                  textAlign = TextAlign.End,
                  modifier = if (isErr) {
                    Modifier.testTag("editor_error_gutter_line_$ln")
                  } else {
                    Modifier
                  },
                )
              }
            }
          }

          val fieldMod = Modifier
            .weight(1f)
            .padding(end = 12.dp)
            .let { mod -> if (!settings.wordWrap) mod.horizontalScroll(horizontalScroll) else mod }
            .testTag("verilog_code_editor")

          BasicTextField(
            value = codeFieldValue,
            onValueChange = { incoming ->
              val transformedText = if (settings.autoIndent) {
                EditorCodeHelpers.applyAutoIndentOnNewline(activeContent, incoming.text, settings.tabWidth)
              } else {
                null
              }
              if (transformedText != null) {
                val delta = transformedText.length - incoming.text.length
                val pos = (incoming.selection.end + delta).coerceIn(0, transformedText.length)
                commitCodeEdit(TextFieldValue(transformedText, TextRange(pos)))
              } else {
                commitCodeEdit(incoming)
              }
            },
            textStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            modifier = fieldMod,
          )
        }

        // Inline Syntax Error Diagnostic Strip + Smart Fix Suggestion (if any)
        val err = if (showRtlError) state.parseError else null
        if (err != null) {
          val snippet = remember(activeContent, err, activeLang) {
            VerilogDiagnosticFormatter.buildSnippet(activeContent, err, activeLang)
          }
          val jumpToError: () -> Unit = {
            val offset = EditorCodeHelpers.offsetForLine(activeContent, err.line)
            codeFieldValue = TextFieldValue(activeContent, TextRange(offset))
            val targetScroll = (((err.line - 1).toFloat() / linesCount.coerceAtLeast(1).toFloat()) * verticalScroll.maxValue).toInt()
            coroutineScope.launch {
              verticalScroll.animateScrollTo(targetScroll.coerceIn(0, verticalScroll.maxValue))
            }
            onNotify("Jumped cursor to line ${err.line}")
          }
          Surface(
            color = eda.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("editor_error_banner"),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Surface(
                  onClick = jumpToError,
                  color = Color.Transparent,
                  modifier = Modifier
                    .weight(1f)
                    .testTag("editor_error_jump_header"),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                  ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = eda.error, modifier = Modifier.size(16.dp))
                    Text(
                      text = "Syntax Error · Line ${err.line}, Col ${err.column}",
                      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                      color = eda.error,
                    )
                    Text(
                      text = "· Jump ↗",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
                val fix = snippet.suggestedFix
                if (fix != null) {
                  FilledTonalButton(
                    onClick = {
                      commitCodeEdit(TextFieldValue(fix.patchedSource, TextRange(fix.patchedSource.length)))
                      onNotify("Applied fix: ${fix.title}")
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                      containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                      contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                      .heightIn(min = 48.dp)
                      .testTag("editor_apply_fix_button"),
                  ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                      text = "Fix: ${fix.title}",
                      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
              Text(
                text = err.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (snippet.lineText.isNotBlank()) {
                Surface(
                  onClick = jumpToError,
                  color = eda.codeSurface.copy(alpha = 0.72f),
                  shape = MaterialTheme.shapes.extraSmall,
                  border = BorderStroke(1.dp, eda.error.copy(alpha = 0.35f)),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                      text = snippet.lineText,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = snippet.caretLine,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold),
                      color = eda.error,
                      maxLines = 1,
                    )
                  }
                }
              }
              if (snippet.hint.isNotBlank()) {
                Text(
                  text = snippet.hint,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("editor_error_suggestion_text"),
                )
              }
            }
          }
        }
      }
    }

    val activeDrcReport = if (showRtlError && state.parseError == null) state.rtlDrcReport else null
    if (showDrcPanel && activeDrcReport != null) {
      EditorDrcDiagnosticsDrawer(
        drcReport = activeDrcReport,
        onRescan = onRunRtlDrc,
        onJumpToLine = jumpToEditorLine,
        onClose = { showDrcPanel = false },
      )
    }

    // 4a. Mobile RTL Quick-Symbol Bar
    val quickSymbols = remember {
      listOf("[", "]", "{", "}", ";", "<=", "@", "=", "(", ")", ":", "'b", "'h", "'d", "// ")
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .testTag("editor_symbol_bar"),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      quickSymbols.forEach { sym ->
        val trimmed = sym.trim()
        Surface(
          onClick = { insertAtCursor(sym) },
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = if (eda.isDark) 0.7f else 0.9f)),
          modifier = Modifier.testTag("symbol_key_$trimmed"),
        ) {
          Text(
            text = trimmed,
            style = MaterialTheme.typography.labelMedium.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }
    }

    // 4b. Context-Aware Code Completion Chips
    val portAndSignalNames = remember(state.parsedDesign) {
      state.parsedDesign?.topModule?.ports?.map { it.name }.orEmpty() +
        state.parsedDesign?.topModule?.internalSignals?.map { it.name }.orEmpty()
    }
    val completions = remember(activeLang, portAndSignalNames) {
      EditorCodeHelpers.buildCompletions(activeLang, portAndSignalNames)
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      completions.forEach { (label, snippet) ->
        Surface(
          onClick = { insertAtCursor(snippet) },
          color = eda.elevatedCard,
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = eda.cyanHighlight,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          )
        }
      }
    }

    // 5. Compact Bottom Action Strip (Run Synthesis + Open Reports)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val report = (state.synthesisResult as? SynthesisResult.Success)?.report
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = if (state.parseError == null) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
          contentDescription = null,
          tint = if (state.parseError == null) eda.success else eda.error,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = report?.let {
            "${it.cellCount} cells · WNS ${if (it.wnsNs >= 0) "+" else ""}${"%.2f".format(it.wnsNs)} ns"
          } ?: if (state.parseError != null) "Fix errors to synthesize" else "Ready to synthesize",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      if (report != null) {
        TextButton(
          onClick = onOpenReports,
          modifier = Modifier.heightIn(min = 44.dp).testTag("editor_view_reports_button"),
        ) {
          Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Reports", style = MaterialTheme.typography.labelLarge)
        }
      }

      Button(
        onClick = onRunSynthesis,
        enabled = !state.isSynthesizing,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("run_synthesis_button"),
      ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Synthesize", style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

@Composable
private fun FileTabChip(
  file: RtlFileEntry,
  selected: Boolean,
  canClose: Boolean,
  onSelect: () -> Unit,
  onClose: () -> Unit,
) {
  val eda = LocalEdaColors.current
  Surface(
    onClick = onSelect,
    color = if (selected) eda.elevatedCard else MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else eda.subtleBorder.copy(alpha = 0.6f),
    ),
    modifier = Modifier.testTag("file_tab_${file.id}"),
  ) {
    Row(
      modifier = Modifier.padding(start = 10.dp, end = if (canClose) 4.dp else 10.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = file.name,
        style = MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
      if (canClose) {
        IconButton(
          onClick = onClose,
          modifier = Modifier.size(20.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Close tab",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
          )
        }
      }
    }
  }
}

private fun findTokenDeclarationLine(source: String, token: String): Int {
  if (token.isBlank()) return 1
  val declRe = Regex("""\b(?:module|entity|input|output|inout|wire|reg|logic)\b[^;\n]*\b${Regex.escape(token)}\b""")
  val lines = source.lines()
  lines.forEachIndexed { idx, line ->
    if (declRe.containsMatchIn(line)) return idx + 1
  }
  lines.forEachIndexed { idx, line ->
    if (line.contains(token)) return idx + 1
  }
  return 1
}
