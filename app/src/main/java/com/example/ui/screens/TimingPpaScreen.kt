package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.StaTimingTableView
import java.util.Locale

@Composable
fun TimingPpaScreen(
  synthesisResult: SynthesisResult?,
  isSaving: Boolean,
  onSaveSnapshot: () -> Unit,
  modifier: Modifier = Modifier,
  onInspectNodeInSchematic: (String) -> Unit = {},
  isOfflineMode: Boolean = false,
) {
  val report = (synthesisResult as? SynthesisResult.Success)?.report
  var viewMode by rememberSaveable { mutableIntStateOf(0) } // 0 = Overview, 1 = STA Table

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (report == null) {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
      ) {
        Column(
          modifier = Modifier.padding(32.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
          )
          Text("No Timing Report Available", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Run a clean RTL synthesis in Workbench to generate Static Timing Analysis and PPA metrics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
        }
      }
      return
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = "Timing & PPA",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${report.moduleName} · ${report.targetLibrary.shortName} · ${report.nodes.size} nodes",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      SingleChoiceSegmentedButtonRow {
        SegmentedButton(
          selected = viewMode == 0,
          onClick = { viewMode = 0 },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          icon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp)) },
          modifier = Modifier.testTag("timing_mode_overview"),
        ) {
          Text("Overview", style = MaterialTheme.typography.labelMedium)
        }
        SegmentedButton(
          selected = viewMode == 1,
          onClick = { viewMode = 1 },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          icon = { Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(16.dp)) },
          modifier = Modifier.testTag("timing_mode_table"),
        ) {
          Text("STA Table", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    if (viewMode == 1) {
      StaTimingTableView(
        report = report,
        onInspectNode = onInspectNodeInSchematic,
        modifier = Modifier.weight(1f),
      )
    } else {
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Hero Timing Slack Banner
        TimingHeroCard(
          report = report,
          isSaving = isSaving,
          isOfflineMode = isOfflineMode,
          onSaveSnapshot = onSaveSnapshot,
        )

        // 2x2 PPA KPI Grid
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PpaMetricCard(
            icon = Icons.Rounded.GridView,
            tint = MaterialTheme.colorScheme.primary,
            title = "Silicon Area",
            value = "${String.format(Locale.US, "%.2f", report.totalAreaUm2)} µm²",
            subtitle = "${report.targetLibrary.processNode} · ${report.optimizationGoal.displayName}",
            modifier = Modifier.weight(1f),
          )
          PpaMetricCard(
            icon = Icons.Rounded.Layers,
            tint = MaterialTheme.colorScheme.secondary,
            title = if (report.targetLibrary.isFpga) "Primitives" else "Standard Cells",
            value = "${report.cellCount}",
            subtitle = "${report.combCellCount} comb · ${report.seqCellCount} seq",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PpaMetricCard(
            icon = Icons.Rounded.ElectricBolt,
            tint = MaterialTheme.colorScheme.tertiary,
            title = "Total Power",
            value = "${String.format(Locale.US, "%.3f", report.totalPowerMw)} mW",
            subtitle = "Dyn ${String.format(Locale.US, "%.2f", report.dynamicPowerMw)} · Lkg ${String.format(Locale.US, "%.2f", report.leakagePowerMw)}",
            modifier = Modifier.weight(1f),
          )
          PpaMetricCard(
            icon = Icons.Rounded.Timeline,
            tint = MaterialTheme.colorScheme.primary,
            title = "Critical Depth",
            value = "${report.criticalPath.size} stages",
            subtitle = "Delay ${String.format(Locale.US, "%.2f", report.criticalPathDelayNs)} ns",
            modifier = Modifier.weight(1f),
          )
        }

        // Gate Distribution Histogram
        GateHistogramCard(report = report)

        // Critical Path Stage-by-Stage Waterfall
        CriticalPathWaterfallCard(report = report)

        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun TimingHeroCard(
  report: SynthesisReport,
  isSaving: Boolean,
  isOfflineMode: Boolean,
  onSaveSnapshot: () -> Unit,
) {
  val met = report.wnsNs >= 0.0
  val statusColor = if (met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, statusColor.copy(alpha = 0.4f), MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (met) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(24.dp),
          )
          Column {
            Text(
              text = if (met) "Timing Constraints Met" else "Timing Violation",
              style = MaterialTheme.typography.titleSmall,
              color = statusColor,
            )
            Text(
              text = "${report.moduleName} @ ${String.format(Locale.US, "%.1f", report.clockPeriodNs)} ns clock",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Surface(
          color = statusColor.copy(alpha = 0.14f),
          shape = MaterialTheme.shapes.small,
        ) {
          Text(
            text = "WNS ${if (report.wnsNs >= 0) "+" else ""}${String.format(Locale.US, "%.2f", report.wnsNs)} ns",
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }

      if (report.appliedSdcConstraints > 0 && report.sdcSummary.isNotBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "SDC (${report.appliedSdcConstraints}): ${report.sdcSummary}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("Achieved Fmax", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = "${String.format(Locale.US, "%.1f", report.fmaxMhz)} MHz",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          Text("Total Negative Slack", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = "${String.format(Locale.US, "%.2f", report.tnsNs)} ns",
            style = MaterialTheme.typography.headlineSmall,
            color = if (report.tnsNs >= 0.0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
          )
        }
      }

      val ratio = (report.criticalPathDelayNs / report.clockPeriodNs.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1.3f)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
          progress = { ratio.coerceIn(0f, 1f) },
          color = statusColor,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape),
        )
        Text(
          text = "Data Arrival ${String.format(Locale.US, "%.2f", report.criticalPathDelayNs)} ns vs Clock Requirement ${String.format(Locale.US, "%.2f", report.clockPeriodNs)} ns",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
        onClick = onSaveSnapshot,
        enabled = !isSaving,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("snapshot_ppa_button"),
      ) {
        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          text = if (isOfflineMode) "Save PPA Run Snapshot Locally" else "Save PPA Run Snapshot to Cloud",
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

@Composable
private fun PpaMetricCard(
  icon: ImageVector,
  tint: Color,
  title: String,
  value: String,
  subtitle: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.border(
      1.dp,
      MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
      MaterialTheme.shapes.medium,
    ),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun GateHistogramCard(report: SynthesisReport) {
  val maxCount = report.gateHistogram.values.maxOrNull()?.coerceAtLeast(1) ?: 1

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "Cell & Primitive Utilization",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (report.gateHistogram.isEmpty()) {
        Text("No mapped cells.", style = MaterialTheme.typography.bodySmall)
      } else {
        report.gateHistogram.forEach { (cellType, count) ->
          val frac = count.toFloat() / maxCount.toFloat()
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = cellType,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.width(68.dp),
            )
            Box(
              modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall),
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth(frac.coerceIn(0.05f, 1f))
                  .height(14.dp)
                  .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
              )
            }
            Text(
              text = "$count",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.width(32.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CriticalPathWaterfallCard(report: SynthesisReport) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "Critical Path Breakdown",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      report.criticalPath.forEachIndexed { idx, step ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              if (idx % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent,
              MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "${idx + 1}. ${step.cellName} (${step.instanceName})",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "net: ${step.netName}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Column(horizontalAlignment = Alignment.End) {
            Text(
              text = "+${String.format(Locale.US, "%.3f", step.incrDelayNs)} ns",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
            )
            Text(
              text = "@ ${String.format(Locale.US, "%.3f", step.arrivalNs)} ns",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}
