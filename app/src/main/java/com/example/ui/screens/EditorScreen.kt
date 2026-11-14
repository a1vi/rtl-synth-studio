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
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
) {
  val eda = LocalEdaColors.current
  val context = LocalContext.current
  val activeFile = state.activeFile
  val activeContent = activeFile?.content ?: state.verilogCode
  val activeLang = activeFile?.language ?: state.primaryLanguage

  var showExplorerSheet by remember { mutableStateOf(false) }
  var showFindBar by rememberSaveable { mutableStateOf(false) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var replacementText by rememberSaveable { mutableStateOf("") }
  var showFileMenu by remember { mutableStateOf(false) }
  var pendingExportPayload by remember { mutableStateOf<String?>(null) }

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
              onFileContentChange(next)
            }
          }
        },
        onReplaceAll = {
          if (searchQuery.isNotEmpty()) {
            val next = Regex.escape(searchQuery).toRegex(RegexOption.IGNORE_CASE)
              .replace(activeContent, Regex.escapeReplacement(replacementText))
            onFileContentChange(next)
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
    val linesCount = remember(activeContent) { activeContent.count { it == '\n' } + 1 }
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
            Text(
              text = "${linesCount}L · ${bracketStatus.second}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = if (bracketStatus.first) MaterialTheme.colorScheme.onSurfaceVariant else eda.warning,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
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
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
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
            value = activeContent,
            onValueChange = { incoming ->
              val transformed = if (settings.autoIndent) {
                EditorCodeHelpers.applyAutoIndentOnNewline(activeContent, incoming, settings.tabWidth)
                  ?: incoming
              } else {
                incoming
              }
              onFileContentChange(transformed)
            },
            textStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            modifier = fieldMod,
          )
        }

        // Inline Syntax Error Diagnostic Strip (if any)
        val err = if (showRtlError) state.parseError else null
        if (err != null) {
          val snippet = remember(activeContent, err) {
            VerilogDiagnosticFormatter.buildSnippet(activeContent, err)
          }
          Surface(
            color = eda.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("editor_error_banner"),
          ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = eda.error, modifier = Modifier.size(16.dp))
                Text(
                  text = "Syntax Error · Line ${err.line}, Col ${err.column}",
                  style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                  color = eda.error,
                )
              }
              Text(
                text = err.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (snippet.hint.isNotBlank()) {
                Text(
                  text = snippet.hint,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }

    // 4. Context-Aware Code Completion Chips
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
          onClick = {
            val separator = if (activeContent.endsWith('\n') || activeContent.isEmpty() || snippet.startsWith(" ")) "" else " "
            onFileContentChange(activeContent + separator + snippet)
          },
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
