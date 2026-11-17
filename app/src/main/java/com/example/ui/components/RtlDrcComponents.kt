package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.synth.RtlDrcReport
import com.example.domain.synth.RtlDrcSeverity
import com.example.domain.synth.RtlDrcViolation
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors

private enum class DrcFilterMode(val label: String) {
  ALL("All"),
  ERRORS("Errors"),
  WARNINGS("Warnings"),
}

@Composable
fun RtlDrcPreSynthCard(
  drcReport: RtlDrcReport?,
  hasParseError: Boolean,
  onRunDrcScan: () -> Unit,
  onJumpToLine: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  var filterMode by remember { mutableStateOf(DrcFilterMode.ALL) }

  val pillSpec = when {
    hasParseError -> Triple("SYNTAX ERR", eda.error, eda.errorContainer)
    drcReport == null -> Triple("PENDING", eda.cyanHighlight, eda.cyanContainer)
    drcReport.errorCount > 0 -> Triple("${drcReport.errorCount} ERR · ${drcReport.warningCount} WARN", eda.error, eda.errorContainer)
    drcReport.warningCount > 0 -> Triple("${drcReport.warningCount} WARN", eda.warning, eda.warningContainer)
    else -> Triple("DRC CLEAN", eda.success, eda.successContainer)
  }

  EdaSurfaceCard(
    modifier = modifier
      .fillMaxWidth()
      .testTag("synth_rtl_drc_card"),
  ) {
    Column {
      EdaPanelHeader(
        title = "Pre-Synthesis RTL DRC",
        subtitle = drcReport?.let {
          "${it.modulesScanned} module(s) · ${it.rulesEvaluated} lint rules (${it.scanDurationMs} ms)"
        } ?: "Static check for missing resets, latches & combinational loops",
        icon = Icons.AutoMirrored.Rounded.Rule,
        trailing = {
          EdaStatusPill(
            text = pillSpec.first,
            color = pillSpec.second,
            containerColor = pillSpec.third,
            showDot = true,
          )
        },
      )

      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (hasParseError) {
          Text(
            text = "Fix Verilog syntax errors before running RTL Design Rule Checks.",
            style = MaterialTheme.typography.bodySmall,
            color = eda.error,
          )
        } else if (drcReport != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            EdaCompactMetric(
              label = "Errors",
              value = drcReport.errorCount.toString(),
              valueColor = if (drcReport.errorCount > 0) eda.error else eda.success,
              modifier = Modifier.weight(1f).testTag("drc_metric_errors"),
            )
            EdaCompactMetric(
              label = "Warnings",
              value = drcReport.warningCount.toString(),
              valueColor = if (drcReport.warningCount > 0) eda.warning else eda.success,
              modifier = Modifier.weight(1f).testTag("drc_metric_warnings"),
            )
            EdaCompactMetric(
              label = "Rules",
              value = drcReport.rulesEvaluated.toString(),
              valueColor = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f).testTag("drc_metric_rules"),
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier
              .weight(1f)
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (drcReport != null && drcReport.violations.isNotEmpty()) {
              DrcFilterMode.entries.forEach { mode ->
                FilterChip(
                  selected = filterMode == mode,
                  onClick = { filterMode = mode },
                  label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("drc_filter_${mode.name}"),
                )
              }
            }
          }
          Spacer(Modifier.width(8.dp))
          OutlinedButton(
            onClick = onRunDrcScan,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("synth_run_drc_button"),
          ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Scan DRC", style = MaterialTheme.typography.labelMedium)
          }
        }

        if (drcReport != null && !hasParseError) {
          val visibleViolations = remember(drcReport, filterMode) {
            when (filterMode) {
              DrcFilterMode.ALL -> drcReport.violations
              DrcFilterMode.ERRORS -> drcReport.violations.filter { it.severity == RtlDrcSeverity.ERROR }
              DrcFilterMode.WARNINGS -> drcReport.violations.filter { it.severity == RtlDrcSeverity.WARNING }
            }
          }

          if (visibleViolations.isEmpty()) {
            Surface(
              color = eda.successContainer.copy(alpha = 0.65f),
              shape = MaterialTheme.shapes.small,
              border = BorderStroke(1.dp, eda.success.copy(alpha = 0.45f)),
              modifier = Modifier.fillMaxWidth().testTag("drc_clean_banner"),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = eda.success, modifier = Modifier.size(16.dp))
                  Text(
                    text = if (drcReport.violations.isEmpty()) {
                      "All ${drcReport.rulesEvaluated} RTL DRC rules passed"
                    } else {
                      "No violations match the selected filter"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = eda.success,
                  )
                }
                drcReport.passedChecks.take(4).forEach { passNote ->
                  Text(
                    text = "• $passNote",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              visibleViolations.forEachIndexed { idx, v ->
                DrcViolationRow(
                  violation = v,
                  onClick = { onJumpToLine(v.line) },
                  modifier = Modifier.testTag("drc_violation_$idx"),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun EditorDrcDiagnosticsDrawer(
  drcReport: RtlDrcReport,
  onRescan: () -> Unit,
  onJumpToLine: (Int) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = 1.dp,
      color = when {
        drcReport.errorCount > 0 -> eda.error.copy(alpha = 0.65f)
        drcReport.warningCount > 0 -> eda.warning.copy(alpha = 0.65f)
        else -> eda.success.copy(alpha = 0.55f)
      },
    ),
    modifier = modifier
      .fillMaxWidth()
      .testTag("editor_drc_panel"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.Rule,
            contentDescription = null,
            tint = when {
              drcReport.errorCount > 0 -> eda.error
              drcReport.warningCount > 0 -> eda.warning
              else -> eda.success
            },
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "RTL DRC (${drcReport.summaryBadgeText()})",
            style = MaterialTheme.typography.labelMedium.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(
            onClick = onRescan,
            modifier = Modifier.testTag("editor_drc_rescan_button"),
          ) {
            Text("Re-Scan", style = MaterialTheme.typography.labelSmall)
          }
          TextButton(onClick = onClose) {
            Text("Hide", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))

      if (drcReport.violations.isEmpty()) {
        Text(
          text = "Clean: No missing resets, inferred latches, combinational loops, or multi-drivers detected.",
          style = MaterialTheme.typography.labelSmall,
          color = eda.success,
        )
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 165.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          drcReport.violations.forEachIndexed { idx, v ->
            DrcViolationRow(
              violation = v,
              onClick = { onJumpToLine(v.line) },
              modifier = Modifier.testTag("editor_drc_item_$idx"),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DrcViolationRow(
  violation: RtlDrcViolation,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val (accent, container, icon) = when (violation.severity) {
    RtlDrcSeverity.ERROR -> Triple(eda.error, eda.errorContainer.copy(alpha = 0.55f), Icons.Rounded.ErrorOutline)
    RtlDrcSeverity.WARNING -> Triple(eda.warning, eda.warningContainer.copy(alpha = 0.5f), Icons.Rounded.WarningAmber)
    RtlDrcSeverity.INFO -> Triple(eda.cyanHighlight, eda.cyanContainer.copy(alpha = 0.45f), Icons.Rounded.Info)
  }

  Surface(
    onClick = onClick,
    color = container,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f).padding(end = 6.dp),
        ) {
          Icon(icon, contentDescription = violation.severity.label, tint = accent, modifier = Modifier.size(15.dp))
          Text(
            text = "${violation.ruleId} · ${violation.title}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = "L${violation.line} · Jump ↗",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        text = violation.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Fix: ${violation.recommendation}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
