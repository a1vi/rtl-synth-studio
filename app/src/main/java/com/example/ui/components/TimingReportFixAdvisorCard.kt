package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.ParsedViolationType
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.StaticTimingSummary
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.TimingReportParser
import com.example.domain.synth.VerilogFixSuggestion
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class FixTypeFilter(val label: String) {
  ALL("All Fixes"),
  SETUP("Setup"),
  HOLD("Hold"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingReportFixAdvisorCard(
  report: SynthesisReport?,
  summary: StaticTimingSummary?,
  layout: PhysicalLayoutSnapshot?,
  activeRtlSource: String,
  onJumpToRtlLine: (Int) -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val clipboard = LocalClipboardManager.current

  val activeReportText = remember(report, summary, layout) {
    if (report != null && summary != null && summary.isAvailable) {
      TimingReportParser.formatFromSynthesis(report, summary, layout)
    } else {
      ""
    }
  }

  val hasActiveViolation = remember(summary, report) {
    val setupNeg = (summary?.setupWnsNs ?: report?.wnsNs ?: 0.0) < 0.0
    val holdNeg = (summary?.holdWnsNs ?: 0.0) < 0.0
    setupNeg || holdNeg
  }

  var reportInput by rememberSaveable { mutableStateOf("") }
  var hasUserEditedInput by rememberSaveable { mutableStateOf(false) }
  var showReportEditor by rememberSaveable { mutableStateOf(true) }
  var filter by rememberSaveable { mutableStateOf(FixTypeFilter.ALL) }

  // Keep synced with active report if user hasn't manually overridden or pasted an external report
  LaunchedEffect(activeReportText, hasActiveViolation) {
    if (!hasUserEditedInput && activeReportText.isNotBlank()) {
      reportInput = activeReportText
    }
  }

  val parsed = remember(reportInput, activeRtlSource) {
    TimingReportParser.parseAndSuggestFixes(
      reportText = reportInput,
      activeRtlSource = activeRtlSource,
    )
  }

  val filteredSuggestions = remember(parsed.suggestions, filter) {
    when (filter) {
      FixTypeFilter.ALL -> parsed.suggestions
      FixTypeFilter.SETUP -> parsed.suggestions.filter { it.issueType == ParsedViolationType.SETUP }
      FixTypeFilter.HOLD -> parsed.suggestions.filter { it.issueType == ParsedViolationType.HOLD }
    }
  }

  val pillInfo = when {
    parsed.violatingPaths.isNotEmpty() ->
      Triple("${parsed.violatingPaths.size} VIOLATED", eda.error, eda.errorContainer)
    parsed.paths.isNotEmpty() ->
      Triple("ALL MET", eda.success, eda.successContainer)
    else ->
      Triple("PARSER READY", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  }

  EdaSurfaceCard(
    modifier = modifier
      .fillMaxWidth()
      .testTag("timing_report_fix_advisor_card"),
  ) {
    Column {
      EdaPanelHeader(
        title = "Timing Report Parser & Verilog Fix Advisor",
        subtitle = "${parsed.detectedFormat} · Paste OpenSTA/PrimeTime/STA report for RTL fixes",
        icon = Icons.Rounded.AutoFixHigh,
        trailing = {
          EdaStatusPill(
            text = pillInfo.first,
            color = pillInfo.second,
            containerColor = pillInfo.third,
            showDot = true,
          )
        },
      )

      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Quick load controls
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          if (activeReportText.isNotBlank()) {
            OutlinedButton(
              onClick = {
                hasUserEditedInput = false
                filter = FixTypeFilter.ALL
                reportInput = activeReportText
              },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("timing_parser_load_active"),
            ) {
              Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("Active STA", style = MaterialTheme.typography.labelSmall)
            }
          }
          OutlinedButton(
            onClick = {
              hasUserEditedInput = true
              filter = FixTypeFilter.ALL
              reportInput = TimingReportParser.SAMPLE_SETUP_REPORT
              showReportEditor = true
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("timing_parser_sample_setup"),
          ) {
            Text("Sample Setup Report", style = MaterialTheme.typography.labelSmall)
          }
          OutlinedButton(
            onClick = {
              hasUserEditedInput = true
              filter = FixTypeFilter.ALL
              reportInput = TimingReportParser.SAMPLE_HOLD_REPORT
              showReportEditor = true
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("timing_parser_sample_hold"),
          ) {
            Text("Sample Hold Report", style = MaterialTheme.typography.labelSmall)
          }
          TextButton(
            onClick = { showReportEditor = !showReportEditor },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("timing_parser_toggle_input"),
          ) {
            Icon(
              imageVector = if (showReportEditor) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (showReportEditor) "Hide Report Text" else "Edit Report Text", style = MaterialTheme.typography.labelSmall)
          }
        }

        AnimatedVisibility(visible = showReportEditor) {
          OutlinedTextField(
            value = reportInput,
            onValueChange = {
              hasUserEditedInput = true
              reportInput = it
            },
            label = { Text("Timing Report (OpenSTA / PrimeTime / Tempus / SiliconFlow)") },
            placeholder = { Text("Paste Startpoint / Endpoint / Path Type / slack (VIOLATED) report...") },
            textStyle = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              lineHeight = 15.sp,
            ),
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("timing_report_parser_input"),
          )
        }

        // Parse summary bar
        if (parsed.paths.isNotEmpty()) {
          Surface(
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("timing_parser_summary_strip"),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              val firstViol = parsed.violatingPaths.firstOrNull() ?: parsed.paths.first()
              EdaKeyValueRow(
                label = "Parsed Paths",
                value = "${parsed.paths.size} total (${parsed.violatingPaths.size} violated)",
              )
              EdaKeyValueRow(
                label = "Worst Path (${firstViol.violationType.label})",
                value = "${firstViol.startpoint} → ${firstViol.endpoint} (${"%.3f".format(Locale.US, firstViol.slackNs)} ns)",
              )
              firstViol.bottleneckStage?.let { bn ->
                EdaKeyValueRow(
                  label = "Dominant Cell",
                  value = "${bn.instanceName} [${bn.cellName}] (+${"%.3f".format(Locale.US, bn.incrDelayNs)} ns)",
                )
              }
            }
          }
        }

        if (parsed.parseNotice != null) {
          Text(
            text = parsed.parseNotice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("timing_parser_notice"),
          )
        }

        if (parsed.suggestions.isNotEmpty()) {
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            val setupCount = parsed.suggestions.count { it.issueType == ParsedViolationType.SETUP }
            val holdCount = parsed.suggestions.count { it.issueType == ParsedViolationType.HOLD }
            FilterChip(
              selected = filter == FixTypeFilter.ALL,
              onClick = { filter = FixTypeFilter.ALL },
              label = { Text("All (${parsed.suggestions.size})", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("fix_filter_all"),
            )
            FilterChip(
              selected = filter == FixTypeFilter.SETUP,
              onClick = { filter = FixTypeFilter.SETUP },
              label = { Text("Setup ($setupCount)", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("fix_filter_setup"),
            )
            FilterChip(
              selected = filter == FixTypeFilter.HOLD,
              onClick = { filter = FixTypeFilter.HOLD },
              label = { Text("Hold ($holdCount)", style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("fix_filter_hold"),
            )
          }

          filteredSuggestions.forEachIndexed { idx, suggestion ->
            VerilogFixSuggestionCard(
              index = idx,
              suggestion = suggestion,
              onCopyCode = {
                clipboard.setText(AnnotatedString(suggestion.verilogFixSnippet))
                onNotify("Copied ${suggestion.category.label} Verilog fix to clipboard.")
              },
              onJumpToRtlLine = { line ->
                onJumpToRtlLine(line)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun VerilogFixSuggestionCard(
  index: Int,
  suggestion: VerilogFixSuggestion,
  onCopyCode: () -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
) {
  val eda = LocalEdaColors.current
  var showBefore by rememberSaveable(suggestion.id) { mutableStateOf(false) }

  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.75f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("verilog_fix_card_$index"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f).padding(end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            color = if (suggestion.issueType == ParsedViolationType.SETUP) {
              eda.errorContainer
            } else {
              eda.warningContainer
            },
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = suggestion.category.badge,
              style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = if (suggestion.issueType == ParsedViolationType.SETUP) eda.error else eda.warning,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
          Text(
            text = suggestion.title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Surface(
          color = eda.successContainer.copy(alpha = 0.7f),
          shape = MaterialTheme.shapes.extraSmall,
        ) {
          Text(
            text = suggestion.estimatedTimingGain,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = eda.success,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }

      Text(
        text = suggestion.rationale,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Icon(
            imageVector = Icons.Rounded.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = "Suggested Verilog RTL Fix",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
          )
        }
        TextButton(
          onClick = { showBefore = !showBefore },
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("verilog_fix_toggle_before_$index"),
        ) {
          Text(
            text = if (showBefore) "Hide Before Pattern" else "Compare Before",
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }

      AnimatedVisibility(visible = showBefore) {
        Surface(
          color = eda.errorContainer.copy(alpha = 0.25f),
          shape = MaterialTheme.shapes.extraSmall,
          border = BorderStroke(1.dp, eda.error.copy(alpha = 0.35f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = suggestion.beforeSnippet,
            style = TextStyle(
              fontFamily = JetBrainsMonoFamily,
              fontSize = 11.sp,
              lineHeight = 15.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
              .horizontalScroll(rememberScrollState())
              .padding(8.dp),
          )
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("verilog_fix_code_$index"),
      ) {
        Text(
          text = suggestion.verilogFixSnippet,
          style = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 15.sp,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        )
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (suggestion.targetLineHint > 0) {
          OutlinedButton(
            onClick = { onJumpToRtlLine(suggestion.targetLineHint) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("verilog_fix_jump_$index"),
          ) {
            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Edit Line ${suggestion.targetLineHint}", style = MaterialTheme.typography.labelSmall)
          }
          Spacer(Modifier.width(8.dp))
        }
        Button(
          onClick = onCopyCode,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("verilog_fix_copy_$index"),
        ) {
          Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(Modifier.width(4.dp))
          Text("Copy Verilog", style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
