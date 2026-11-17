package com.example.ui.screens.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.AreaAnalysisHelper
import com.example.domain.synth.CellAreaStat
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.SynthesisReport
import com.example.ui.components.AreaDistributionDonutCard
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class AreaSortMode(val label: String) {
  TOTAL_AREA("Total Area ↓"),
  CELL_COUNT("Count ↓"),
  SINGLE_AREA("Cell Area ↓"),
  NAME("Name ↑"),
}

@Composable
fun AreaAnalysisTab(
  report: SynthesisReport,
  layout: PhysicalLayoutSnapshot?,
  pastRuns: List<SynthesisRun>,
  onLocateCellsInSchematic: (Set<String>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val summary = remember(report, layout) { AreaAnalysisHelper.analyze(report, layout) }
  val isFpga = report.targetLibrary.isFpga

  var searchQuery by rememberSaveable { mutableStateOf("") }
  var sortMode by rememberSaveable { mutableStateOf(AreaSortMode.TOTAL_AREA) }
  var selectedBaselineRunId by rememberSaveable { mutableStateOf<String?>(pastRuns.firstOrNull()?.id) }

  val filteredStats = remember(summary.cellStats, searchQuery, sortMode) {
    val q = searchQuery.trim().lowercase(Locale.ROOT)
    val base = summary.cellStats.filter {
      q.isEmpty() || it.cellName.lowercase(Locale.ROOT).contains(q) || it.category.label.lowercase(Locale.ROOT).contains(q)
    }
    when (sortMode) {
      AreaSortMode.TOTAL_AREA -> base.sortedByDescending { it.totalAreaUm2 }
      AreaSortMode.CELL_COUNT -> base.sortedByDescending { it.count }
      AreaSortMode.SINGLE_AREA -> base.sortedByDescending { it.singleCellAreaUm2 }
      AreaSortMode.NAME -> base.sortedBy { it.cellName }
    }
  }
  val maxGroupArea = (summary.cellStats.maxOfOrNull { it.totalAreaUm2 } ?: 1.0).coerceAtLeast(1e-6)
  val baselineRun = remember(pastRuns, selectedBaselineRunId) {
    pastRuns.firstOrNull { it.id == selectedBaselineRunId } ?: pastRuns.firstOrNull()
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("analysis_area_list"),
    contentPadding = PaddingValues(bottom = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 1. Area Summary
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_area_summary_card")) {
        Column {
          EdaPanelHeader(
            title = "Cell & Silicon Area Summary",
            subtitle = "${report.moduleName} · ${report.targetLibrary.displayName}",
            icon = Icons.Rounded.Memory,
            trailing = {
              EdaStatusPill(
                text = if (summary.hasPhysicalPnR) "POST-PNR" else "SYNTH AREA",
                color = if (summary.hasPhysicalPnR) eda.success else eda.cyanHighlight,
                containerColor = if (summary.hasPhysicalPnR) eda.successContainer else eda.cyanContainer,
              )
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = if (isFpga) "Slice Units" else "Total Cell Area",
                value = formatAreaMetric(summary.totalAreaUm2, isFpga),
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric("Total Cells", summary.totalCellCount.toString(), modifier = Modifier.weight(1f))
              EdaCompactMetric(
                label = "Seq / Comb",
                value = "${summary.sequentialCount} / ${summary.combinationalCount}",
                valueColor = eda.cyanHighlight,
                modifier = Modifier.weight(1f),
              )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Comb. Area",
                value = formatAreaMetric(summary.combinationalAreaUm2, isFpga),
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Seq. Area",
                value = formatAreaMetric(summary.sequentialAreaUm2, isFpga),
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Buf / Inv Area",
                value = formatAreaMetric(summary.bufferAreaUm2 + summary.inverterAreaUm2, isFpga),
                modifier = Modifier.weight(1f),
              )
            }
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
            EdaKeyValueRow("Buffers / Inverters", "${summary.bufferCount} BUF (${formatAreaMetric(summary.bufferAreaUm2, isFpga)}) · ${summary.inverterCount} INV (${formatAreaMetric(summary.inverterAreaUm2, isFpga)})")
            if (layout != null && summary.dieAreaUm2 != null && summary.coreAreaUm2 != null) {
              EdaKeyValueRow("Physical Die Area", "${"%.1f".format(Locale.US, summary.dieAreaUm2)} µm² (${"%.1f".format(Locale.US, layout.dieWidthUm)} × ${"%.1f".format(Locale.US, layout.dieHeightUm)} µm)")
              EdaKeyValueRow("Physical Core Area", "${"%.1f".format(Locale.US, summary.coreAreaUm2)} µm² (${layout.rowCount} rows)")
              EdaKeyValueRow("Core Utilization", "${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}% actual (target ${layout.coreUtilizationPercent}%)")
            } else {
              EdaKeyValueRow("Physical Die / Core Area", "PnR not run (synthesized cell area only)", valueColor = MaterialTheme.colorScheme.onSurfaceVariant)
              EdaKeyValueRow("Core Utilization", "N/A until Floorplan/Placement", valueColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }

    // 1b. LUTs vs. Flip-Flops vs. DSPs Donut Chart
    item {
      AreaDistributionDonutCard(
        summary = summary,
        isFpga = isFpga,
        onLocateBucketInSchematic = onLocateCellsInSchematic,
      )
    }

    // 2. Area Visualizations (Comb vs Seq + Categories + Top Cells)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_area_visuals_card")) {
        Column {
          EdaPanelHeader(
            title = "Area Breakdown Charts",
            subtitle = "Combinational vs Sequential & Cell Categories",
            icon = Icons.Rounded.Layers,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text("Combinational vs Sequential Silicon Area", style = MaterialTheme.typography.labelMedium)
            EdaStackedRatioBar(
              segments = listOf(
                Triple("Combinational", summary.combinationalAreaUm2, MaterialTheme.colorScheme.primary),
                Triple("Sequential", summary.sequentialAreaUm2, eda.cyanHighlight),
              ),
            )
            if (summary.categorySummaries.isNotEmpty()) {
              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
              Text("Category Area Share", style = MaterialTheme.typography.labelMedium)
              summary.categorySummaries.forEach { cat ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = "${cat.category.label} (${cat.cellCount})",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  )
                  Text(
                    text = "${formatAreaMetric(cat.areaUm2, isFpga)} (${"%.1f".format(Locale.US, cat.areaPct)}%)",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }

    // 3. Sortable & Searchable Standard Cell Distribution Table
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_area_distribution_card")) {
        Column {
          EdaPanelHeader(
            title = "Cell Distribution Table",
            subtitle = "${filteredStats.size} of ${summary.cellStats.size} cell type(s) · Tap locate for schematic",
            icon = Icons.Rounded.Layers,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("Filter standard cells or category...") },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("area_cell_search_input"),
            )
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              AreaSortMode.entries.forEach { mode ->
                FilterChip(
                  selected = sortMode == mode,
                  onClick = { sortMode = mode },
                  label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("area_sort_${mode.name.lowercase()}"),
                )
              }
            }

            if (filteredStats.isEmpty()) {
              Text(
                text = "No standard cells match \"$searchQuery\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              filteredStats.take(25).forEach { stat ->
                AreaCellStatRow(
                  stat = stat,
                  maxArea = maxGroupArea,
                  isFpga = isFpga,
                  onLocate = { onLocateCellsInSchematic(stat.instanceNodeIds.toSet()) },
                )
              }
            }
          }
        }
      }
    }

    // 4. Synthesis Run Area Comparison
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_area_compare_card")) {
        Column {
          EdaPanelHeader(
            title = "Area Run Comparison",
            subtitle = if (pastRuns.isEmpty()) "Save synthesis runs to compare area deltas" else "Compare against ${pastRuns.size} saved run(s)",
            icon = Icons.AutoMirrored.Rounded.CompareArrows,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (baselineRun == null) {
              Text(
                text = "No prior saved runs available for delta comparison yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                pastRuns.take(6).forEach { run ->
                  FilterChip(
                    selected = baselineRun.id == run.id,
                    onClick = { selectedBaselineRunId = run.id },
                    label = {
                      Text(
                        text = "${run.designName} (${TargetLibrary.fromId(run.targetLibrary).shortName})",
                        style = MaterialTheme.typography.labelSmall,
                      )
                    },
                  )
                }
              }
              val delta = AreaAnalysisHelper.compareRuns(report, baselineRun)
              val deltaSign = if (delta.deltaAreaUm2 >= 0) "+" else ""
              val deltaColor = when {
                !delta.sameLibrary -> eda.warning
                delta.deltaAreaUm2 <= 0.0 -> eda.success
                else -> eda.error
              }
              EdaKeyValueRow("Baseline Run", "${baselineRun.designName} · ${baselineRun.cellCount} cells · ${formatAreaMetric(baselineRun.areaUm2, isFpga)}")
              EdaKeyValueRow(
                label = "Area Delta",
                value = "$deltaSign${formatAreaMetric(delta.deltaAreaUm2, isFpga)} ($deltaSign${"%.1f".format(Locale.US, delta.deltaAreaPct)}%)",
                valueColor = deltaColor,
              )
              EdaKeyValueRow("Cell Count Delta", "${if (delta.deltaCellCount >= 0) "+" else ""}${delta.deltaCellCount} cells")
              if (!delta.sameLibrary) {
                Text(
                  text = "Note: Baseline used a different target PDK (${TargetLibrary.fromId(baselineRun.targetLibrary).shortName}); compare absolute areas carefully.",
                  style = MaterialTheme.typography.labelSmall,
                  color = eda.warning,
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
private fun AreaCellStatRow(
  stat: CellAreaStat,
  maxArea: Double,
  isFpga: Boolean,
  onLocate: () -> Unit,
) {
  val eda = LocalEdaColors.current
  val frac = (stat.totalAreaUm2 / maxArea).toFloat().coerceIn(0.04f, 1f)
  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag("area_cell_row_${stat.cellName}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "${stat.cellName} ×${stat.count}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          Text(
            text = "${formatAreaMetric(stat.totalAreaUm2, isFpga)} (${"%.1f".format(Locale.US, stat.areaPercentage)}%)",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = "${stat.category.label} · ${"%.2f".format(Locale.US, stat.singleCellAreaUm2)}/cell",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth().height(7.dp)) {
          val barColor = MaterialTheme.colorScheme.primary
          val bgColor = eda.subtleBorder.copy(alpha = 0.35f)
          Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(color = bgColor, cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()))
            drawRoundRect(
              color = barColor,
              size = Size(size.width * frac, size.height),
              cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
          }
        }
      }
      IconButton(
        onClick = onLocate,
        modifier = Modifier.size(48.dp).testTag("area_locate_${stat.cellName}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.AccountTree,
          contentDescription = "Locate ${stat.cellName} in Schematic",
          tint = eda.cyanHighlight,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
