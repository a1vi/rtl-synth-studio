package com.example.ui.screens.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.OperatingCorner
import com.example.domain.synth.PowerActivitySource
import com.example.domain.synth.PowerAnalysisReport
import com.example.domain.synth.PowerEstimationConfig
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.SynthesisReport
import com.example.ui.components.EdaCompactMetric
import com.example.ui.components.EdaKeyValueRow
import com.example.ui.components.EdaPanelHeader
import com.example.ui.components.EdaStatusPill
import com.example.ui.components.EdaSurfaceCard
import com.example.ui.components.PowerBreakdownSummaryCard
import com.example.ui.components.SimulationPowerActivityPanel
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

private enum class PowerCellSort(val label: String) {
  TOTAL("Total ↓"),
  SWITCHING("Switch ↓"),
  INTERNAL("Internal ↓"),
  LEAKAGE("Leakage ↓"),
  ALPHA("Alpha ↓"),
}

@Composable
fun PowerAnalysisTab(
  report: SynthesisReport,
  power: PowerAnalysisReport,
  powerConfig: PowerEstimationConfig,
  simulation: SimulationResult?,
  pastRuns: List<SynthesisRun>,
  onUpdatePowerConfig: ((PowerEstimationConfig) -> PowerEstimationConfig) -> Unit,
  onOpenSimulateTab: () -> Unit,
  onRunSimulation: () -> Unit,
  onLocateNodeInSchematic: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val isSim = power.activitySource == PowerActivitySource.SIMULATION_VCD
  var showConfigPanel by rememberSaveable { mutableStateOf(false) }
  var cellSort by rememberSaveable { mutableStateOf(PowerCellSort.TOTAL) }
  var selectedBaselineRunId by rememberSaveable { mutableStateOf<String?>(pastRuns.firstOrNull()?.id) }

  val sortedNodes = remember(power.allPowerNodes, cellSort) {
    when (cellSort) {
      PowerCellSort.TOTAL -> power.allPowerNodes.sortedByDescending { it.totalPowerUw }
      PowerCellSort.SWITCHING -> power.allPowerNodes.sortedByDescending { it.switchingPowerUw }
      PowerCellSort.INTERNAL -> power.allPowerNodes.sortedByDescending { it.internalPowerUw }
      PowerCellSort.LEAKAGE -> power.allPowerNodes.sortedByDescending { it.leakagePowerUw }
      PowerCellSort.ALPHA -> power.allPowerNodes.sortedByDescending { it.activityFactor }
    }
  }
  val maxCellPowerUw = (sortedNodes.maxOfOrNull { it.totalPowerUw } ?: 1.0).coerceAtLeast(1e-6)
  val baselineRun = remember(pastRuns, selectedBaselineRunId) {
    pastRuns.firstOrNull { it.id == selectedBaselineRunId } ?: pastRuns.firstOrNull()
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth().testTag("analysis_power_list"),
    contentPadding = PaddingValues(bottom = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // 1. Power Methodology & Honest Activity Source
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_power_methodology_card")) {
        Column {
          EdaPanelHeader(
            title = "Power Estimation & Methodology",
            subtitle = if (isSim) "VCD Trace Activity (${power.simulatedCycles} cycles)" else "Vectorless Estimate (α=${"%.2f".format(Locale.US, powerConfig.defaultActivityFactor)})",
            icon = Icons.Rounded.Bolt,
            trailing = {
              EdaStatusPill(
                text = if (isSim) "VCD ACTIVITY" else "VECTORLESS EST",
                color = if (isSim) eda.success else eda.warning,
                containerColor = if (isSim) eda.successContainer else eda.warningContainer,
                showDot = true,
              )
            },
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Total Power",
                value = formatPowerAuto(power.totalPowerMw),
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Dynamic",
                value = formatPowerAuto(power.dynamicPowerMw),
                valueColor = eda.cyanHighlight,
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Static Leakage",
                value = formatPowerAuto(power.leakagePowerMw),
                valueColor = eda.warning,
                modifier = Modifier.weight(1f),
              )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              EdaCompactMetric(
                label = "Clock Tree",
                value = formatPowerAuto(power.clockTreePowerMw),
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Avg Cycle Pwr",
                value = formatPowerAuto(power.averageCyclePowerMw),
                modifier = Modifier.weight(1f),
              )
              EdaCompactMetric(
                label = "Peak Cycle Pwr",
                value = power.peakCyclePowerMw?.let { formatPowerAuto(it) } ?: "N/A (no VCD)",
                valueColor = if (power.peakCyclePowerMw != null) eda.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
            }
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.5f))
            EdaKeyValueRow("Switching / Internal / Leakage", "${formatPowerAuto(power.switchingPowerMw)} / ${formatPowerAuto(power.internalPowerMw)} / ${formatPowerAuto(power.leakagePowerMw)}")
            EdaKeyValueRow("Comb / Seq / Buf-Inv", "${formatPowerAuto(power.combinationalPowerMw)} / ${formatPowerAuto(power.sequentialPowerMw)} / ${formatPowerAuto(power.bufferPowerMw)}")
            EdaKeyValueRow("VDD / Freq / Corner / Temp", "${"%.2f".format(Locale.US, power.vddVolts)} V · ${"%.1f".format(Locale.US, power.clockFreqMhz)} MHz · ${power.operatingCorner.shortName} · ${"%.0f".format(Locale.US, power.temperatureC)}°C")
            EdaKeyValueRow("Mean Toggle Activity (α)", "${"%.3f".format(Locale.US, power.averageActivityFactor)} · Energy/cycle ${"%.2f".format(Locale.US, power.energyPerCyclePj)} pJ")
            if (!isSim) {
              OutlinedButton(
                onClick = onRunSimulation,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analysis_power_run_sim_button"),
              ) {
                Icon(Icons.Rounded.Waves, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Run Simulation for VCD Activity Power", style = MaterialTheme.typography.labelLarge)
              }
            }
          }
        }
      }
    }

    // 2. Interactive Power Configuration Panel (VDD, Frequency, Default α, Temp, Corner)
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_power_config_card")) {
        Column {
          EdaPanelHeader(
            title = "Operating Conditions & Activity Config",
            subtitle = "${power.operatingCorner.label} · α=${"%.2f".format(Locale.US, powerConfig.defaultActivityFactor)}",
            icon = Icons.Rounded.Tune,
            trailing = {
              TextButton(
                onClick = { showConfigPanel = !showConfigPanel },
                modifier = Modifier.heightIn(min = 48.dp).testTag("power_config_toggle_button"),
              ) {
                Text(if (showConfigPanel) "Hide" else "Configure", style = MaterialTheme.typography.labelSmall)
                Icon(
                  imageVector = if (showConfigPanel) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              }
            },
          )
          AnimatedVisibility(visible = showConfigPanel) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text("Clock Frequency Override", style = MaterialTheme.typography.labelSmall)
              Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                  selected = powerConfig.clockFreqOverrideMhz == null,
                  onClick = { onUpdatePowerConfig { it.copy(clockFreqOverrideMhz = null) } },
                  label = { Text("Synth (${"%.0f".format(Locale.US, 1000.0 / report.clockPeriodNs)} MHz)", style = MaterialTheme.typography.labelSmall) },
                )
                listOf(50.0, 100.0, 250.0, 500.0).forEach { freq ->
                  FilterChip(
                    selected = powerConfig.clockFreqOverrideMhz == freq,
                    onClick = { onUpdatePowerConfig { it.copy(clockFreqOverrideMhz = freq) } },
                    label = { Text("${freq.toInt()} MHz", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("power_freq_chip_${freq.toInt()}"),
                  )
                }
              }

              Text("Supply Voltage (VDD)", style = MaterialTheme.typography.labelSmall)
              Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                  selected = powerConfig.vddOverrideVolts == null,
                  onClick = { onUpdatePowerConfig { it.copy(vddOverrideVolts = null) } },
                  label = { Text("PDK Nom (${report.targetLibrary.nominalVoltage})", style = MaterialTheme.typography.labelSmall) },
                )
                listOf(0.8, 1.0, 1.2, 1.8).forEach { v ->
                  FilterChip(
                    selected = powerConfig.vddOverrideVolts == v,
                    onClick = { onUpdatePowerConfig { it.copy(vddOverrideVolts = v) } },
                    label = { Text("${"%.1f".format(Locale.US, v)}V", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("power_vdd_chip_${"%.1f".format(Locale.US, v)}"),
                  )
                }
              }

              Text("Vectorless Toggle Rate (α)", style = MaterialTheme.typography.labelSmall)
              Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.10, 0.20, 0.35, 0.50).forEach { alpha ->
                  FilterChip(
                    selected = kotlin.math.abs(powerConfig.defaultActivityFactor - alpha) < 0.01,
                    onClick = { onUpdatePowerConfig { it.copy(defaultActivityFactor = alpha) } },
                    label = { Text("α=${"%.2f".format(Locale.US, alpha)}", style = MaterialTheme.typography.labelSmall) },
                  )
                }
              }

              Text("Operating Corner & Temperature", style = MaterialTheme.typography.labelSmall)
              Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OperatingCorner.entries.forEach { corner ->
                  FilterChip(
                    selected = powerConfig.operatingCorner == corner,
                    onClick = {
                      val cornerTemp = when (corner) {
                        OperatingCorner.SLOW -> 125.0
                        OperatingCorner.TYPICAL -> 25.0
                        OperatingCorner.FAST -> -40.0
                      }
                      onUpdatePowerConfig { it.copy(operatingCorner = corner, temperatureC = cornerTemp) }
                    },
                    label = { Text(corner.shortName, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("power_corner_${corner.shortName.lowercase()}"),
                  )
                }
                listOf(25.0, 85.0, 125.0).forEach { temp ->
                  FilterChip(
                    selected = kotlin.math.abs(powerConfig.temperatureC - temp) < 1.0,
                    onClick = { onUpdatePowerConfig { it.copy(temperatureC = temp) } },
                    label = { Text("${temp.toInt()}°C", style = MaterialTheme.typography.labelSmall) },
                  )
                }
              }
            }
          }
        }
      }
    }

    // 3. Dynamic vs Leakage & Domain Charts
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_power_charts_card")) {
        Column {
          EdaPanelHeader(
            title = "Power Component Distribution",
            subtitle = "Switching, Internal & Static Leakage Shares",
            icon = Icons.Rounded.Bolt,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            EdaStackedRatioBar(
              segments = listOf(
                Triple("Switching", power.switchingPowerMw, MaterialTheme.colorScheme.primary),
                Triple("Internal", power.internalPowerMw, eda.cyanHighlight),
                Triple("Leakage", power.leakagePowerMw, eda.warning),
              ),
            )
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
            EdaStackedRatioBar(
              segments = listOf(
                Triple("Comb", power.combinationalPowerMw, MaterialTheme.colorScheme.primary),
                Triple("Sequential", power.sequentialPowerMw, eda.success),
                Triple("Clock Tree", power.clockTreePowerMw, eda.warning),
              ),
            )
          }
        }
      }
    }

    // 4. Existing PowerBreakdownSummaryCard (keeps PowerAnalysisUiTest tags compatible)
    item {
      PowerBreakdownSummaryCard(
        power = power,
        onInspectSimulationPower = onOpenSimulateTab,
      )
    }

    // 5. Sortable Cell-Level Power Table with Schematic Highlight
    item {
      EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_power_cell_table_card")) {
        Column {
          EdaPanelHeader(
            title = "Cell-Level Power Hotspots",
            subtitle = "Top ${sortedNodes.take(15).size} of ${sortedNodes.size} instances · Tap locate to highlight",
            icon = Icons.Rounded.Bolt,
          )
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              PowerCellSort.entries.forEach { s ->
                FilterChip(
                  selected = cellSort == s,
                  onClick = { cellSort = s },
                  label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.testTag("power_sort_${s.name.lowercase()}"),
                )
              }
            }
            sortedNodes.take(15).forEach { nodePwr ->
              val frac = (nodePwr.totalPowerUw / maxCellPowerUw).toFloat().coerceIn(0.05f, 1f)
              Surface(
                color = eda.elevatedCard,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("power_cell_row_${nodePwr.nodeId}"),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text(
                        text = "${nodePwr.instanceName} (${nodePwr.cellName})",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                      )
                      Text(
                        text = "${"%.2f".format(Locale.US, nodePwr.totalPowerUw)} µW",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                    Text(
                      text = "α=${"%.2f".format(Locale.US, nodePwr.activityFactor)} · Sw ${"%.2f".format(Locale.US, nodePwr.switchingPowerUw)} · Int ${"%.2f".format(Locale.US, nodePwr.internalPowerUw)} · Leak ${"%.2f".format(Locale.US, nodePwr.leakagePowerUw)} µW",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                      val barColor = eda.cyanHighlight
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
                    onClick = { onLocateNodeInSchematic(nodePwr.nodeId) },
                    modifier = Modifier.size(48.dp).testTag("power_locate_${nodePwr.nodeId}"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.AccountTree,
                      contentDescription = "Highlight ${nodePwr.instanceName} in Schematic",
                      tint = eda.cyanHighlight,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // 6. Simulation Trace Activity Panel (when sim exists)
    if (simulation != null) {
      item {
        SimulationPowerActivityPanel(
          powerAnalysis = power,
          simulation = simulation,
          synthesisReport = report,
        )
      }
    }

    // 7. Run-to-Run Power Comparison
    if (pastRuns.isNotEmpty() && baselineRun != null) {
      item {
        EdaSurfaceCard(modifier = Modifier.fillMaxWidth().testTag("analysis_power_compare_card")) {
          Column {
            EdaPanelHeader(
              title = "Power Run Comparison",
              subtitle = "Compare total power against saved run snapshots",
              icon = Icons.AutoMirrored.Rounded.CompareArrows,
            )
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pastRuns.take(6).forEach { run ->
                  FilterChip(
                    selected = baselineRun.id == run.id,
                    onClick = { selectedBaselineRunId = run.id },
                    label = { Text("${run.designName} (${TargetLibrary.fromId(run.targetLibrary).shortName})", style = MaterialTheme.typography.labelSmall) },
                  )
                }
              }
              val deltaMw = power.totalPowerMw - baselineRun.powerMw
              val deltaPct = if (baselineRun.powerMw > 1e-6) (deltaMw / baselineRun.powerMw) * 100.0 else 0.0
              val sign = if (deltaMw >= 0) "+" else ""
              EdaKeyValueRow("Baseline Run", "${baselineRun.designName} · ${formatPowerAuto(baselineRun.powerMw)}")
              EdaKeyValueRow(
                label = "Total Power Delta",
                value = "$sign${formatPowerAuto(deltaMw)} ($sign${"%.1f".format(Locale.US, deltaPct)}%)",
                valueColor = if (deltaMw <= 0.0) eda.success else eda.error,
              )
            }
          }
        }
      }
    }
  }
}
