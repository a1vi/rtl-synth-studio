package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors

internal data class CodeBlockFoldRegion(
  val title: String,
  val startLine: Int,
  val endLine: Int,
  val previewLines: List<String>,
)

internal object EditorCodeHelpers {

  fun applyAutoIndentOnNewline(oldText: String, newText: String, tabWidth: Int): String? {
    if (newText.length != oldText.length + 1) return null
    val insertIdx = newText.indices.firstOrNull { i ->
      i >= oldText.length || newText[i] != oldText[i]
    } ?: return null
    if (newText[insertIdx] != '\n') return null

    val lineStart = newText.lastIndexOf('\n', startIndex = (insertIdx - 1).coerceAtLeast(0)) + 1
    val prevLine = newText.substring(lineStart, insertIdx)
    val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val trimmedPrev = prevLine.trim().lowercase()
    val shouldIncrease = trimmedPrev.endsWith("begin") ||
      trimmedPrev.endsWith("(") ||
      trimmedPrev.endsWith("then") ||
      trimmedPrev.startsWith("module ") ||
      trimmedPrev.startsWith("case ") ||
      trimmedPrev.startsWith("entity ") ||
      trimmedPrev.startsWith("architecture ")
    val extra = if (shouldIncrease) " ".repeat(tabWidth.coerceIn(2, 4)) else ""
    val indent = baseIndent + extra
    if (indent.isEmpty()) return null
    return newText.substring(0, insertIdx + 1) + indent + newText.substring(insertIdx + 1)
  }

  fun checkBracketBalance(source: String): Pair<Boolean, String> {
    var parens = 0
    var brackets = 0
    var braces = 0
    source.forEach { ch ->
      when (ch) {
        '(' -> parens++
        ')' -> parens--
        '[' -> brackets++
        ']' -> brackets--
        '{' -> braces++
        '}' -> braces--
      }
      if (parens < 0 || brackets < 0 || braces < 0) {
        return false to "Unmatched closing delimiter"
      }
    }
    return if (parens == 0 && brackets == 0 && braces == 0) {
      true to "() [] {} balanced"
    } else {
      false to "Open ()=$parens []=$brackets {}=$braces"
    }
  }

  fun extractFoldRegions(source: String): List<CodeBlockFoldRegion> {
    val lines = source.lines()
    val regions = mutableListOf<CodeBlockFoldRegion>()
    val stack = ArrayDeque<Pair<String, Int>>()
    val openRe = Regex("""(?i)^\s*(module\s+\w+|always(?:_ff|_comb|_latch)?\b.*|case\b.*|entity\s+\w+.*|architecture\s+\w+.*|process\b.*)""")
    val closeRe = Regex("""(?i)^\s*(endmodule|endcase|end\s+entity|end\s+architecture|end\s+process|end\b)""")

    lines.forEachIndexed { idx, line ->
      val lineNo = idx + 1
      val oMatch = openRe.find(line)
      if (oMatch != null && !line.trim().endsWith(";")) {
        stack.addLast(oMatch.groupValues[1].trim().take(36) to lineNo)
      } else if (oMatch != null && (line.trimStart().startsWith("module", true) || line.trimStart().startsWith("entity", true) || line.trimStart().startsWith("architecture", true))) {
        stack.addLast(oMatch.groupValues[1].trim().take(36) to lineNo)
      } else if (closeRe.containsMatchIn(line) && stack.isNotEmpty()) {
        val (title, start) = stack.removeLast()
        if (lineNo > start) {
          regions += CodeBlockFoldRegion(
            title = title,
            startLine = start,
            endLine = lineNo,
            previewLines = lines.subList((start - 1).coerceAtLeast(0), lineNo.coerceAtMost(lines.size)),
          )
        }
      }
    }
    return regions.sortedBy { it.startLine }
  }

  fun buildCompletions(
    language: RtlLanguage,
    portAndSignalNames: List<String>,
  ): List<Pair<String, String>> {
    val keywords = when (language) {
      RtlLanguage.VHDL -> listOf(
        "signal" to "signal sig_name : std_logic;",
        "process(clk)" to "process(clk)\nbegin\n  if rising_edge(clk) then\n    \n  end if;\nend process;",
        "std_logic_vector" to "std_logic_vector(7 downto 0)",
        "rising_edge" to "rising_edge(clk)",
        "<=" to " <= ",
        "when else" to " when cond else ",
      )
      RtlLanguage.SYSTEM_VERILOG -> listOf(
        "logic" to "logic ",
        "always_ff" to "always_ff @(posedge clk) begin\n  \nend",
        "always_comb" to "always_comb begin\n  \nend",
        "assign" to "assign ",
        "case" to "case ()\n  default: ;\nendcase",
        "<=" to " <= ",
      )
      RtlLanguage.SDC -> listOf(
        "create_clock" to "create_clock -name clk -period 2.00 [get_ports clk]",
        "set_input_delay" to "set_input_delay -clock clk 0.25 [all_inputs]",
        "set_output_delay" to "set_output_delay -clock clk 0.25 [all_outputs]",
        "set_clock_uncertainty" to "set_clock_uncertainty 0.10 [get_clocks clk]",
        "set_false_path" to "set_false_path -from [get_ports rst]",
      )
      else -> listOf(
        "input wire" to "input wire ",
        "output reg" to "output reg ",
        "assign" to "assign ",
        "always @(posedge clk)" to "always @(posedge clk) begin\n  \nend",
        "always @(*)" to "always @(*) begin\n  \nend",
        "case" to "case ()\n  default: ;\nendcase",
      )
    }
    val signals = portAndSignalNames.distinct().take(6).map { it to it }
    return keywords + signals
  }
}

@Composable
internal fun SearchReplaceBar(
  query: String,
  replacement: String,
  matchCount: Int,
  onQueryChange: (String) -> Unit,
  onReplacementChange: (String) -> Unit,
  onReplaceNext: () -> Unit,
  onReplaceAll: () -> Unit,
  onClose: () -> Unit,
) {
  val eda = LocalEdaColors.current
  EdaSurfaceCard(
    elevated = true,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("editor_search_replace_bar"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(Icons.Rounded.FindReplace, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Surface(
          color = eda.codeSurface,
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, eda.subtleBorder),
          modifier = Modifier.weight(1f),
        ) {
          BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodySmall.copy(
              fontFamily = JetBrainsMonoFamily,
              color = MaterialTheme.colorScheme.onSurface,
            ),
            decorationBox = { inner ->
              Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                if (query.isEmpty()) {
                  Text("Find in file...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
              }
            },
            modifier = Modifier.testTag("editor_find_input"),
          )
        }
        Text(
          text = "$matchCount hits",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Rounded.Close, contentDescription = "Close find bar", modifier = Modifier.size(16.dp))
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          color = eda.codeSurface,
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, eda.subtleBorder),
          modifier = Modifier.weight(1f),
        ) {
          BasicTextField(
            value = replacement,
            onValueChange = onReplacementChange,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodySmall.copy(
              fontFamily = JetBrainsMonoFamily,
              color = MaterialTheme.colorScheme.onSurface,
            ),
            decorationBox = { inner ->
              Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                if (replacement.isEmpty()) {
                  Text("Replace with...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
              }
            },
            modifier = Modifier.testTag("editor_replace_input"),
          )
        }
        TextButton(
          onClick = onReplaceNext,
          enabled = query.isNotEmpty() && matchCount > 0,
          modifier = Modifier.testTag("editor_replace_next_button"),
        ) {
          Text("Replace", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
          onClick = onReplaceAll,
          enabled = query.isNotEmpty() && matchCount > 0,
          modifier = Modifier.testTag("editor_replace_all_button"),
        ) {
          Text("All", style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectExplorerAndOutlineSheet(
  files: List<RtlFileEntry>,
  activeFileId: String,
  foldRegions: List<CodeBlockFoldRegion>,
  onSelectFile: (String) -> Unit,
  onAddFile: (String, RtlLanguage) -> Unit,
  onDeleteFile: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val eda = LocalEdaColors.current
  var showAddFileDialog by remember { mutableStateOf(false) }
  val expandedRegions = remember { mutableStateOf(setOf<Int>()) }

  if (showAddFileDialog) {
    AddFileDialog(
      onDismiss = { showAddFileDialog = false },
      onConfirm = { name, lang ->
        showAddFileDialog = false
        onAddFile(name, lang)
      },
    )
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Text("Project Explorer & Code Folding", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(
          onClick = { showAddFileDialog = true },
          modifier = Modifier.testTag("explorer_add_file_button"),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("New File")
        }
      }

      Text("Project Files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      files.forEach { file ->
        val selected = file.id == activeFileId
        Surface(
          onClick = {
            onSelectFile(file.id)
            onDismiss()
          },
          color = if (selected) MaterialTheme.colorScheme.primaryContainer else eda.elevatedCard,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else eda.subtleBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(Icons.Rounded.Description, contentDescription = null, tint = eda.cyanHighlight, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(8.dp))
              Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              EdaStatusPill(
                text = file.language.badge,
                color = eda.cyanHighlight,
                containerColor = eda.cyanContainer,
              )
              if (file.id != "file_rtl_top") {
                IconButton(onClick = { onDeleteFile(file.id) }, modifier = Modifier.size(32.dp)) {
                  Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove file", tint = eda.error, modifier = Modifier.size(16.dp))
                }
              }
            }
          }
        }
      }

      if (foldRegions.isNotEmpty()) {
        HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.6f))
        Text(
          text = "Code Folding Blocks (${foldRegions.size})",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        foldRegions.forEach { region ->
          val isExpanded = region.startLine in expandedRegions.value
          EdaSurfaceCard(
            onClick = {
              expandedRegions.value = if (isExpanded) {
                expandedRegions.value - region.startLine
              } else {
                expandedRegions.value + region.startLine
              }
            },
            elevated = true,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Icon(
                    imageVector = if (isExpanded) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = region.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                Text(
                  text = "L${region.startLine}–${region.endLine}",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (isExpanded) {
                Text(
                  text = region.previewLines.joinToString("\n"),
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun AddFileDialog(
  onDismiss: () -> Unit,
  onConfirm: (String, RtlLanguage) -> Unit,
) {
  var name by remember { mutableStateOf("submodule.v") }
  var language by remember { mutableStateOf(RtlLanguage.VERILOG) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Project File", style = MaterialTheme.typography.titleMedium) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("File Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          RtlLanguage.entries.forEach { lang ->
            FilterChip(
              selected = language == lang,
              onClick = {
                language = lang
                val base = name.substringBeforeLast('.').ifBlank { "rtl_file" }
                name = "$base.${lang.extension}"
              },
              label = { Text(lang.badge, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { onConfirm(name, language) }) { Text("Add") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
