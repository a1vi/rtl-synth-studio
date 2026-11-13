package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.PortDirection
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.WaveformRadix
import com.example.ui.components.WaveformViewer
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun SimulationScreen(
  simulation: SimulationResult?,
  cycleCount: Int,
  stimulusPatterns: Map<String, StimulusPattern>,
  onSetCycleCount: (Int) -> Unit,
  onCycleStimulusPattern: (String) -> Unit,
  onStepCustomCellValue: (String, Int, Long, Int) -> Unit,
  onRunSimulation: () -> Unit,
  onResetStimulus: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var radix by rememberSaveable { mutableStateOf(WaveformRadix.HEX) }
  var selectedCycle by rememberSaveable { mutableIntStateOf(0) }

  val maxCycle = ((simulation?.cycleCount ?: cycleCount) - 1).coerceAtLeast(0)
  val safeSelectedCycle = selectedCycle.coerceIn(0, maxCycle)

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Header & Radix / Cycle controls
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = "RTL Simulation",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = simulation?.let {
            "${it.moduleName} · ${it.cycleCount} cycles" +
              (it.clockPort?.let { clk -> " · clk=$clk" } ?: " · combinational")
          } ?: "Verify functional waveforms on-device",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      SingleChoiceSegmentedButtonRow {
        WaveformRadix.entries.forEachIndexed { idx, r ->
          SegmentedButton(
            selected = radix == r,
            onClick = { radix = r },
            shape = SegmentedButtonDefaults.itemShape(idx, WaveformRadix.entries.size),
            modifier = Modifier.testTag("sim_radix_${r.name.lowercase()}"),
          ) {
            Text(r.label, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }

    if (simulation == null) {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
      ) {
        Column(
          modifier = Modifier.padding(28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Waves,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
          )
          Text("No Simulation Trace Yet", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Ensure your Verilog RTL compiles cleanly, then tap Simulate to generate cycle-accurate digital waveforms.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
          Button(
            onClick = onRunSimulation,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("sim_run_button"),
          ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Run Simulation")
          }
        }
      }
      return
    }

    // Scrollable main content
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Action & Cycle Depth Strip
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = onRunSimulation,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("sim_run_button"),
        ) {
          Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text("Simulate", style = MaterialTheme.typography.labelLarge)
        }

        listOf(8, 16, 24, 32).forEach { count ->
          FilterChip(
            selected = cycleCount == count,
            onClick = { onSetCycleCount(count) },
            label = { Text("${count}c", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag("sim_cycles_$count"),
          )
        }

        OutlinedButton(
          onClick = onResetStimulus,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("sim_reset_stimulus_button"),
        ) {
          Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Reset", style = MaterialTheme.typography.labelMedium)
        }
      }

      // Stimulus Pattern Selector Strip for Non-Clock Inputs
      val editableInputs = simulation.traces.filter {
        it.direction == PortDirection.INPUT && !it.isClock
      }
      if (editableInputs.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Input Stimulus Generators (tap to cycle pattern)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            editableInputs.forEach { trace ->
              val pat = stimulusPatterns[trace.name] ?: StimulusPattern.AUTO
              Surface(
                onClick = { onCycleStimulusPattern(trace.name) },
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    MaterialTheme.shapes.small,
                  )
                  .testTag("stim_pattern_${trace.name}"),
              ) {
                Text(
                  text = "${trace.name}: ${pat.label}",
                  style = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  ),
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
              }
            }
          }
        }
      }

      // Digital Waveform Viewer
      WaveformViewer(
        simulation = simulation,
        radix = radix,
        selectedCycle = safeSelectedCycle,
        onSelectCycle = { selectedCycle = it },
      )

      // Cycle Probe Inspector & Manual Bit/Value Tweak Card
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large)
          .testTag("sim_cycle_probe_card"),
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(
                text = "Cycle C$safeSelectedCycle Probe",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Tap waveform or step cycles; tap Step/Flip on inputs to override.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = { if (safeSelectedCycle > 0) selectedCycle = safeSelectedCycle - 1 },
                enabled = safeSelectedCycle > 0,
                modifier = Modifier.size(48.dp).testTag("sim_prev_cycle_button"),
              ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous cycle")
              }
              IconButton(
                onClick = { if (safeSelectedCycle < maxCycle) selectedCycle = safeSelectedCycle + 1 },
                enabled = safeSelectedCycle < maxCycle,
                modifier = Modifier.size(48.dp).testTag("sim_next_cycle_button"),
              ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next cycle")
              }
            }
          }

          simulation.traces.forEach { trace ->
            val currentVal = trace.values.getOrElse(safeSelectedCycle) { 0L }
            val formatted = trace.formatValue(safeSelectedCycle, radix)
            val isEditableInput = trace.direction == PortDirection.INPUT && !trace.isClock
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = trace.name,
                  style = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                  ),
                )
                if (trace.width > 1) {
                  Text(
                    text = "[${trace.width - 1}:0]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = formatted,
                  style = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                      trace.isClock -> MaterialTheme.colorScheme.secondary
                      trace.direction == PortDirection.OUTPUT -> MaterialTheme.colorScheme.tertiary
                      else -> MaterialTheme.colorScheme.primary
                    },
                  ),
                  modifier = Modifier.testTag("sim_value_${trace.name}"),
                )
                if (isEditableInput) {
                  TextButton(
                    onClick = {
                      val nextVal = if (trace.width == 1) {
                        if ((currentVal and 1L) == 0L) 1L else 0L
                      } else {
                        RtlSimulator.maskValue(currentVal + 1L, trace.width)
                      }
                      onStepCustomCellValue(trace.name, safeSelectedCycle, nextVal, trace.width)
                    },
                    modifier = Modifier.testTag("sim_override_${trace.name}"),
                  ) {
                    Text(if (trace.width == 1) "Flip" else "+1", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }
        }
      }

      Spacer(Modifier.height(12.dp))
    }
  }
}
