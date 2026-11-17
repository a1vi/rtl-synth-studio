package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.PowerActivitySource
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.PowerAnalysisReport
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.SignalActivityMetric
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.SynthesisReport
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

@Composable
fun PowerBreakdownSummaryCard(
  power: PowerAnalysisReport,
  modifier: Modifier = Modifier,
  onInspectSimulationPower: (() -> Unit)? = null,
) {
  val eda = LocalEdaColors.current
  val isSimDriven = power.activitySource == PowerActivitySource.SIMULATION_VCD
  val totalMw = power.totalPowerMw.coerceAtLeast(1e-6)
  val swFracTarget = (power.switchingPowerMw / totalMw).toFloat().coerceIn(0f, 1f)
  val intFracTarget = (power.internalPowerMw / totalMw).toFloat().coerceIn(0f, 1f)
  val leakFracTarget = (power.leakagePowerMw / totalMw).toFloat().coerceIn(0f, 1f)

  val swFrac by animateFloatAsState(
    targetValue = swFracTarget,
    animationSpec = tween(420, easing = FastOutSlowInEasing),
    label = "sw_frac",
  )
  val intFrac by animateFloatAsState(
    targetValue = intFracTarget,
    animationSpec = tween(420, easing = FastOutSlowInEasing),
    label = "int_frac",
  )

  val swColor = MaterialTheme.colorScheme.primary
  val intColor = eda.cyanHighlight
  val leakColor = eda.warning

  EdaSurfaceCard(
    modifier = modifier
      .fillMaxWidth()
      .testTag("power_breakdown_card"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        EdaSectionHeader(
          title = "CMOS Power Breakdown",
          subtitle = "Switching, internal & leakage @ ${"%.2f".format(Locale.US, power.vddVolts)}V · ${"%.0f".format(Locale.US, power.clockFreqMhz)} MHz",
          modifier = Modifier.weight(1f),
        )
        EdaStatusPill(
          text = if (isSimDriven) "SIM α=${"%.2f".format(Locale.US, power.averageActivityFactor)}" else "DEFAULT α=0.20",
          color = if (isSimDriven) eda.success else eda.cyanHighlight,
          containerColor = if (isSimDriven) eda.successContainer else eda.cyanContainer,
          modifier = Modifier.testTag("power_activity_source_pill"),
        )
      }

      // Total Power Headline Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        Column {
          Text(
            text = "TOTAL POWER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${"%.3f".format(Locale.US, power.totalPowerMw)} mW",
            style = MaterialTheme.typography.headlineSmall.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("power_total_value"),
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "ENERGY / CYCLE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${"%.2f".format(Locale.US, power.energyPerCyclePj)} pJ",
            style = MaterialTheme.typography.titleMedium.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.SemiBold,
            ),
            color = eda.cyanHighlight,
          )
        }
      }

      // 3-Segment Stacked Horizontal Power Bar
      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(14.dp)
          .clip(MaterialTheme.shapes.small)
          .testTag("power_stacked_bar"),
      ) {
        val swWidth = size.width * swFrac
        val intWidth = size.width * intFrac
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        // Leakage spans base (tail)
        drawRoundRect(color = leakColor, cornerRadius = corner)
        // Internal spans switching + internal
        if (swWidth + intWidth > 0f) {
          drawRect(
            color = intColor,
            topLeft = Offset.Zero,
            size = Size((swWidth + intWidth).coerceAtMost(size.width), size.height),
          )
        }
        // Switching spans front
        if (swWidth > 0f) {
          drawRect(
            color = swColor,
            topLeft = Offset.Zero,
            size = Size(swWidth.coerceAtMost(size.width), size.height),
          )
        }
      }

      // 3 Component Breakdown Tiles
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PowerComponentTile(
          label = "Switching",
          powerMw = power.switchingPowerMw,
          percent = swFracTarget * 100f,
          accent = swColor,
          modifier = Modifier.weight(1f).testTag("power_switching_tile"),
        )
        PowerComponentTile(
          label = "Internal",
          powerMw = power.internalPowerMw,
          percent = intFracTarget * 100f,
          accent = intColor,
          modifier = Modifier.weight(1f).testTag("power_internal_tile"),
        )
        PowerComponentTile(
          label = "Leakage",
          powerMw = power.leakagePowerMw,
          percent = leakFracTarget * 100f,
          accent = leakColor,
          modifier = Modifier.weight(1f).testTag("power_leakage_tile"),
        )
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.55f))

      // Circuit Hierarchy Split (Comb / Seq / Clock Tree)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        EdaCompactMetric(
          label = "Comb Logic",
          value = "${"%.3f".format(Locale.US, power.combinationalPowerMw)} mW",
          modifier = Modifier.weight(1f),
        )
        EdaCompactMetric(
          label = "Sequential",
          value = "${"%.3f".format(Locale.US, power.sequentialPowerMw)} mW",
          modifier = Modifier.weight(1f),
        )
        EdaCompactMetric(
          label = "Clock Tree",
          value = "${"%.3f".format(Locale.US, power.clockTreePowerMw)} mW",
          modifier = Modifier.weight(1f),
        )
      }

      if (onInspectSimulationPower != null) {
        OutlinedButton(
          onClick = onInspectSimulationPower,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("open_power_activity_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Bolt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = if (isSimDriven) {
              "Inspect Simulation Activity & Power"
            } else {
              "Analyze Live Simulation Activity"
            },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }
  }
}

@Composable
private fun PowerComponentTile(
  label: String,
  powerMw: Double,
  percent: Float,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(accent),
        )
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = "${"%.3f".format(Locale.US, powerMw)} mW",
        style = MaterialTheme.typography.labelLarge.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
      Text(
        text = "${"%.1f".format(Locale.US, percent)}%",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
        color = accent,
      )
    }
  }
}

@Composable
fun SimulationPowerActivityPanel(
  powerAnalysis: PowerAnalysisReport?,
  simulation: SimulationResult,
  synthesisReport: SynthesisReport? = null,
  onRunSynthesis: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current

  // If synthesis report is present, always derive live simulation-driven power from it
  val effectivePower = remember(powerAnalysis, simulation, synthesisReport) {
    when {
      synthesisReport != null -> PowerAnalysisEngine.analyze(synthesisReport, simulation)
      powerAnalysis != null -> powerAnalysis
      else -> null
    }
  }

  // Always compute trace-level signal activity even if netlist hasn't been synthesized yet
  val traceActivityList = remember(effectivePower, simulation) {
    effectivePower?.signalActivities ?: extractStandaloneTraceActivities(simulation)
  }
  val cycleProfile = remember(effectivePower, simulation) {
    effectivePower?.cycleToggleProfile ?: extractStandaloneCycleProfile(simulation)
  }
  val avgAlpha = effectivePower?.averageActivityFactor ?: run {
    val nonClk = traceActivityList.filter { !it.isClock }
    if (nonClk.isNotEmpty()) nonClk.map { it.activityFactor }.average() else 0.0
  }
  val totalToggles = effectivePower?.totalToggles ?: cycleProfile.sum()
  val peakCycleIdx = effectivePower?.peakCycleIndex ?: run {
    var bestIdx = 0
    var bestVal = 0
    cycleProfile.forEachIndexed { idx, v ->
      if (v > bestVal) {
        bestVal = v
        bestIdx = idx
      }
    }
    bestIdx
  }
  val peakCycleToggles = effectivePower?.peakCycleToggles ?: cycleProfile.getOrElse(peakCycleIdx) { 0 }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .testTag("sim_power_panel"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (effectivePower != null) {
      PowerBreakdownSummaryCard(power = effectivePower)
    } else {
      EdaSurfaceCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("power_unsynthesized_notice"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Waves,
              contentDescription = null,
              tint = eda.cyanHighlight,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = "Simulation Activity Extracted (α = ${"%.3f".format(Locale.US, avgAlpha)})",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = "Run Synthesis to combine these ${simulation.cycleCount}-cycle trace activity factors with standard-cell capacitances & PDK leakage for Switching, Internal, and Leakage mW totals.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (onRunSynthesis != null) {
            OutlinedButton(
              onClick = onRunSynthesis,
              modifier = Modifier.heightIn(min = 48.dp),
            ) {
              Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Synthesize Netlist for Full Power")
            }
          }
        }
      }
    }

    // Per-Cycle Toggle Density Sparkline Card (IR-drop / Peak Switching Monitor)
    if (cycleProfile.isNotEmpty()) {
      EdaSurfaceCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("power_cycle_profile_card"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            EdaSectionHeader(
              title = "Per-Cycle Bit Toggle Density",
              subtitle = "Total data bit transitions per cycle · Peak @ C$peakCycleIdx ($peakCycleToggles flips)",
              modifier = Modifier.weight(1f),
            )
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
              contentDescription = null,
              tint = eda.cyanHighlight,
              modifier = Modifier.size(18.dp),
            )
          }

          val maxToggles = (cycleProfile.maxOrNull() ?: 1).coerceAtLeast(1)
          val barColor = eda.cyanHighlight
          val peakColor = eda.warning
          val gridColor = eda.subtleBorder.copy(alpha = 0.35f)
          Canvas(
            modifier = Modifier
              .fillMaxWidth()
              .height(84.dp)
              .testTag("power_cycle_toggle_chart"),
          ) {
            val count = cycleProfile.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(4f)
            // Baseline
            drawLine(
              color = gridColor,
              start = Offset(0f, size.height - 2f),
              end = Offset(size.width, size.height - 2f),
              strokeWidth = 1.dp.toPx(),
            )
            cycleProfile.forEachIndexed { idx, toggles ->
              val frac = (toggles.toFloat() / maxToggles).coerceIn(0.06f, 1f)
              val barH = if (toggles == 0) 3.dp.toPx() else (size.height - 6.dp.toPx()) * frac
              val x = idx * (barWidth + gap)
              val y = size.height - barH
              drawRoundRect(
                color = if (idx == peakCycleIdx && toggles > 0) peakColor else barColor.copy(alpha = if (toggles == 0) 0.25f else 0.85f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Avg α = ${"%.3f".format(Locale.US, avgAlpha)}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "Total Data Toggles: $totalToggles",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = eda.cyanHighlight,
            )
          }
        }
      }
    }

    // Signal Activity Factor (α) Breakdown List
    if (traceActivityList.isNotEmpty()) {
      EdaSurfaceCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("power_signal_activity_card"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          EdaSectionHeader(
            title = "Signal Switching Activity (α)",
            subtitle = "Bit transition density per net (α = bit flips / ((N−1) × width))",
          )
          traceActivityList.take(14).forEachIndexed { idx, sig ->
            SignalActivityRow(
              metric = sig,
              modifier = Modifier.testTag("power_signal_row_${sig.signalName}"),
            )
            if (idx < traceActivityList.take(14).lastIndex) {
              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.28f))
            }
          }
        }
      }
    }

    // Top Power-Consuming Cells Table
    val topNodes = effectivePower?.topPowerNodes.orEmpty()
    if (topNodes.isNotEmpty()) {
      EdaSurfaceCard(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("power_top_cells_card"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Memory,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            EdaSectionHeader(
              title = "Top Power Hotspot Cells (µW)",
              subtitle = "Ranked by Switching + Internal + Leakage dissipation",
            )
          }
          topNodes.take(8).forEachIndexed { idx, node ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .testTag("power_hotspot_row_$idx"),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = "${node.instanceName} · ${node.cellName}",
                  style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                  ),
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "α=${"%.2f".format(Locale.US, node.activityFactor)} | Sw ${"%.1f".format(Locale.US, node.switchingPowerUw)} · Int ${"%.1f".format(Locale.US, node.internalPowerUw)} · Leak ${"%.1f".format(Locale.US, node.leakagePowerUw)} µW",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Text(
                text = "${"%.2f".format(Locale.US, node.totalPowerUw)} µW",
                style = MaterialTheme.typography.labelLarge.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
              )
            }
            if (idx < topNodes.take(8).lastIndex) {
              HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.25f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SignalActivityRow(
  metric: SignalActivityMetric,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val barColor = when {
    metric.isClock -> MaterialTheme.colorScheme.secondary
    metric.activityFactor >= 0.45 -> eda.warning
    metric.activityFactor >= 0.15 -> eda.cyanHighlight
    else -> MaterialTheme.colorScheme.primary
  }
  val widthLabel = if (metric.bitWidth > 1) "[${metric.bitWidth - 1}:0]" else ""
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = "${metric.signalName}$widthLabel",
          style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (metric.isClock || metric.isSequentialReg) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = if (metric.isClock) "CLK" else "REG",
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }
      }
      Text(
        text = "α=${"%.3f".format(Locale.US, metric.activityFactor)} (${metric.toggleCount}t)",
        style = MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.SemiBold,
        ),
        color = barColor,
      )
    }

    val barFrac = metric.activityFactor.toFloat().coerceIn(0f, 1f)
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(7.dp)
        .clip(MaterialTheme.shapes.extraSmall),
    ) {
      drawRoundRect(
        color = eda.subtleBorder.copy(alpha = 0.35f),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
      )
      if (barFrac > 0f) {
        drawRoundRect(
          color = barColor,
          size = Size(size.width * barFrac, size.height),
          cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
      }
    }
  }
}

private fun extractStandaloneTraceActivities(simulation: SimulationResult): List<SignalActivityMetric> {
  val denomCycles = (simulation.cycleCount - 1).coerceAtLeast(1)
  return simulation.traces.map { trace ->
    val w = trace.width.coerceAtLeast(1)
    var toggles = 0
    for (c in 1 until simulation.cycleCount) {
      val prev = RtlSimulator.maskValue(trace.values.getOrElse(c - 1) { 0L }, w)
      val curr = RtlSimulator.maskValue(trace.values.getOrElse(c) { 0L }, w)
      toggles += java.lang.Long.bitCount(prev xor curr)
    }
    val possible = (denomCycles * w).coerceAtLeast(1)
    val alpha = if (trace.isClock) (if (toggles > 0) 1.0 else 0.0) else (toggles.toDouble() / possible).coerceIn(0.0, 1.0)
    SignalActivityMetric(
      signalName = trace.name,
      bitWidth = w,
      toggleCount = if (trace.isClock) (if (toggles > 0) denomCycles * 2 else 0) else toggles,
      possibleTransitions = possible,
      activityFactor = alpha,
      isClock = trace.isClock,
      isReset = trace.isReset,
      isSequentialReg = trace.isSequentialReg,
      netCapacitanceFf = 0.0,
      switchingPowerUw = 0.0,
    )
  }.sortedWith(compareBy<SignalActivityMetric> { it.isClock }.thenByDescending { it.activityFactor })
}

private fun extractStandaloneCycleProfile(simulation: SimulationResult): List<Int> {
  if (simulation.cycleCount <= 0) return emptyList()
  val arr = IntArray(simulation.cycleCount)
  simulation.traces.filter { !it.isClock }.forEach { trace ->
    val w = trace.width.coerceAtLeast(1)
    for (c in 1 until simulation.cycleCount) {
      val prev = RtlSimulator.maskValue(trace.values.getOrElse(c - 1) { 0L }, w)
      val curr = RtlSimulator.maskValue(trace.values.getOrElse(c) { 0L }, w)
      arr[c] += java.lang.Long.bitCount(prev xor curr)
    }
  }
  return arr.toList()
}
