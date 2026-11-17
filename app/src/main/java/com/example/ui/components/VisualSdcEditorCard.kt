package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PortDirection
import com.example.domain.synth.RtlPort
import com.example.domain.synth.SdcClock
import com.example.domain.synth.SdcConstraintSet
import com.example.domain.synth.SdcIoDelay
import com.example.domain.synth.SdcOutputLoad
import com.example.domain.synth.SdcParser
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale
import kotlin.math.abs

private enum class VisualSdcSection(val label: String, val tag: String) {
  CLOCKS("Clocks", "sdc_tab_clocks"),
  INPUT_DELAYS("Input Delays", "sdc_tab_inputs"),
  OUTPUT_LOADS("Output Loads", "sdc_tab_loads"),
  OUTPUT_DELAYS("Output Delays", "sdc_tab_outputs"),
}

/**
 * Interactive visual Synopsys Design Constraints (SDC) builder & bidirectional script editor.
 * Supports clock frequencies (MHz <-> ns), clock uncertainty, input delays, output delays,
 * capacitive output loads (pF with PDK RC delay preview), and raw SDC script editing.
 */
@Composable
fun VisualSdcEditorCard(
  sdcText: String,
  clockPeriodNs: Double,
  ports: List<RtlPort>,
  targetLibrary: TargetLibrary,
  moduleName: String,
  onUpdateSdc: (String) -> Unit,
  onResetSdc: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  var draftSdc by remember(sdcText) { mutableStateOf(sdcText) }
  var selectedSection by rememberSaveable { mutableStateOf(VisualSdcSection.CLOCKS) }
  var syncVersion by remember { mutableIntStateOf(0) }

  val parsed: SdcConstraintSet = remember(draftSdc, clockPeriodNs) {
    SdcParser.parse(draftSdc, clockPeriodNs)
  }

  val inputPorts = remember(ports) {
    ports.filter { it.direction == PortDirection.INPUT }.map { it.name }.distinct()
  }
  val outputPorts = remember(ports) {
    ports.filter { it.direction == PortDirection.OUTPUT }.map { it.name }.distinct()
  }
  val likelyClockPorts = remember(inputPorts) {
    val clks = inputPorts.filter {
      it.equals("clk", ignoreCase = true) ||
        it.equals("clock", ignoreCase = true) ||
        it.lowercase(Locale.ROOT).contains("clk")
    }
    if (clks.isNotEmpty()) clks else listOf("clk")
  }

  fun commitStructured(
    clocks: List<SdcClock> = parsed.clocks,
    ioDelays: List<SdcIoDelay> = parsed.ioDelays,
    outputLoads: List<SdcOutputLoad> = parsed.outputLoads,
    uncertaintyNs: Double = parsed.clockUncertaintyNs,
    bumpVersion: Boolean = true,
  ) {
    draftSdc = SdcParser.serialize(
      clocks = clocks,
      ioDelays = ioDelays,
      outputLoads = outputLoads,
      clockUncertaintyNs = uncertaintyNs,
      exceptions = parsed.exceptions,
      moduleName = moduleName.takeIf { it.isNotBlank() },
    )
    if (bumpVersion) syncVersion++
  }

  val primaryClock = parsed.clocks.firstOrNull()
  val primaryFreqMhz = primaryClock?.frequencyMhz ?: (1000.0 / clockPeriodNs.coerceAtLeast(0.2))
  val hasPendingEdits = draftSdc.trim() != sdcText.trim()

  Surface(
    color = eda.elevatedCard.copy(alpha = 0.72f),
    shape = MaterialTheme.shapes.medium,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
    modifier = modifier.fillMaxWidth().testTag("visual_sdc_editor_card"),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Live SDC Overview Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(
            text = "Visual SDC Constraint Builder",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "${parsed.totalConstraintCount} constraint(s) · ${targetLibrary.shortName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        EdaStatusPill(
          text = "${"%.0f".format(Locale.US, primaryFreqMhz)} MHz",
          color = eda.cyanHighlight,
          containerColor = eda.cyanContainer,
        )
      }

      // Section Selector Chips (Clocks / Input Delays / Output Loads / Output Delays)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        VisualSdcSection.entries.forEach { sec ->
          val countBadge = when (sec) {
            VisualSdcSection.CLOCKS -> parsed.clocks.size
            VisualSdcSection.INPUT_DELAYS -> parsed.ioDelays.count { it.isInput }
            VisualSdcSection.OUTPUT_LOADS -> parsed.outputLoads.size
            VisualSdcSection.OUTPUT_DELAYS -> parsed.ioDelays.count { !it.isInput }
          }
          val icon = when (sec) {
            VisualSdcSection.CLOCKS -> Icons.Rounded.Schedule
            VisualSdcSection.INPUT_DELAYS -> Icons.AutoMirrored.Rounded.Input
            VisualSdcSection.OUTPUT_LOADS -> Icons.Rounded.ElectricBolt
            VisualSdcSection.OUTPUT_DELAYS -> Icons.Rounded.Output
          }
          FilterChip(
            selected = selectedSection == sec,
            onClick = { selectedSection = sec },
            leadingIcon = {
              Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            },
            label = {
              Text("${sec.label} ($countBadge)", style = MaterialTheme.typography.labelSmall)
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag(sec.tag),
          )
        }
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))

      // Section Body
      when (selectedSection) {
        VisualSdcSection.CLOCKS -> {
          ClocksSection(
            clocks = parsed.clocks,
            uncertaintyNs = parsed.clockUncertaintyNs,
            clockPeriodNs = clockPeriodNs,
            likelyClockPorts = likelyClockPorts,
            syncVersion = syncVersion,
            onClocksChange = { updated, bump -> commitStructured(clocks = updated, bumpVersion = bump) },
            onUncertaintyChange = { uncert -> commitStructured(uncertaintyNs = uncert, bumpVersion = false) },
          )
        }

        VisualSdcSection.INPUT_DELAYS -> {
          val inputs = parsed.ioDelays.filter { it.isInput }
          val nonInputs = parsed.ioDelays.filter { !it.isInput }
          IoDelaysSection(
            title = "Input Arrival Delays (set_input_delay)",
            emptyHint = "No input delays defined. Add set_input_delay to constrain external launch paths.",
            delays = inputs,
            isInput = true,
            availablePorts = listOf("*") + inputPorts.filterNot { it in likelyClockPorts },
            availableClocks = parsed.clocks.map { it.name }.ifEmpty { listOf("clk") },
            syncVersion = syncVersion,
            addTag = "sdc_add_input_delay_button",
            rowTagPrefix = "sdc_input_delay_row",
            onDelaysChange = { updatedInputs, bump ->
              commitStructured(ioDelays = updatedInputs + nonInputs, bumpVersion = bump)
            },
          )
        }

        VisualSdcSection.OUTPUT_LOADS -> {
          OutputLoadsSection(
            loads = parsed.outputLoads,
            availablePorts = listOf("*") + outputPorts,
            targetLibrary = targetLibrary,
            syncVersion = syncVersion,
            onLoadsChange = { updatedLoads, bump ->
              commitStructured(outputLoads = updatedLoads, bumpVersion = bump)
            },
          )
        }

        VisualSdcSection.OUTPUT_DELAYS -> {
          val inputs = parsed.ioDelays.filter { it.isInput }
          val outputs = parsed.ioDelays.filter { !it.isInput }
          IoDelaysSection(
            title = "Output External Delays (set_output_delay)",
            emptyHint = "No external output delays defined. Add set_output_delay to tighten output capture budgets.",
            delays = outputs,
            isInput = false,
            availablePorts = listOf("*") + outputPorts,
            availableClocks = parsed.clocks.map { it.name }.ifEmpty { listOf("clk") },
            syncVersion = syncVersion,
            addTag = "sdc_add_output_delay_button",
            rowTagPrefix = "sdc_output_delay_row",
            onDelaysChange = { updatedOutputs, bump ->
              commitStructured(ioDelays = inputs + updatedOutputs, bumpVersion = bump)
            },
          )
        }
      }

      if (parsed.diagnostics.isNotEmpty()) {
        Surface(
          color = eda.warningContainer.copy(alpha = 0.7f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth().testTag("sdc_diagnostics_banner"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = eda.warning, modifier = Modifier.size(16.dp))
            Text(
              text = parsed.diagnostics.joinToString("\n"),
              style = MaterialTheme.typography.labelSmall,
              color = eda.warning,
            )
          }
        }
      }

      HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))

      // Live Generated / Raw SDC Script Editor (bidirectional sync)
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text("Generated SDC Script", style = MaterialTheme.typography.labelMedium)
          }
          if (hasPendingEdits) {
            EdaStatusPill(
              text = "UNAPPLIED",
              color = eda.warning,
              containerColor = eda.warningContainer,
            )
          }
        }

        OutlinedTextField(
          value = draftSdc,
          onValueChange = {
            draftSdc = it
            syncVersion++
          },
          label = { Text("SDC Constraints") },
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          minLines = 4,
          maxLines = 8,
          modifier = Modifier.fillMaxWidth().testTag("sdc_constraints_editor"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = {
            onResetSdc()
            syncVersion++
          },
          modifier = Modifier.heightIn(min = 48.dp).testTag("reset_sdc_button"),
        ) {
          Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Reset Default")
        }
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = { onUpdateSdc(draftSdc) },
          modifier = Modifier.heightIn(min = 48.dp).testTag("apply_sdc_button"),
        ) {
          Text("Apply SDC")
        }
      }
    }
  }
}

@Composable
private fun ClocksSection(
  clocks: List<SdcClock>,
  uncertaintyNs: Double,
  clockPeriodNs: Double,
  likelyClockPorts: List<String>,
  syncVersion: Int,
  onClocksChange: (List<SdcClock>, Boolean) -> Unit,
  onUncertaintyChange: (Double) -> Unit,
) {
  val eda = LocalEdaColors.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (clocks.isEmpty()) {
      Text(
        text = "No create_clock rules yet. Fallback period is ${"%.2f".format(Locale.US, clockPeriodNs)} ns.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      clocks.forEachIndexed { idx, clk ->
        var freqText by remember(idx, syncVersion) {
          mutableStateOf(String.format(Locale.US, "%.1f", clk.frequencyMhz))
        }
        var periodText by remember(idx, syncVersion) {
          mutableStateOf(String.format(Locale.US, "%.2f", clk.periodNs))
        }

        LaunchedEffect(clk.periodNs) {
          val currentParsedP = periodText.toDoubleOrNull()
          if (currentParsedP == null || abs(currentParsedP - clk.periodNs) > 0.015) {
            periodText = String.format(Locale.US, "%.2f", clk.periodNs)
          }
          val targetFreq = clk.frequencyMhz
          val currentParsedF = freqText.toDoubleOrNull()
          if (currentParsedF == null || abs(currentParsedF - targetFreq) > 0.5) {
            freqText = String.format(Locale.US, "%.1f", targetFreq)
          }
        }

        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("sdc_clock_row_$idx"),
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
              Text(
                text = "Clock #${idx + 1}: ${clk.name} -> [get_ports ${clk.portName ?: clk.name}]",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (clocks.size > 1) {
                IconButton(
                  onClick = {
                    onClocksChange(clocks.filterIndexed { i, _ -> i != idx }, true)
                  },
                  modifier = Modifier.size(48.dp).testTag("sdc_delete_clock_$idx"),
                ) {
                  Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove clock ${clk.name}", tint = eda.error)
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = freqText,
                onValueChange = { raw ->
                  freqText = raw
                  val mhz = raw.toDoubleOrNull()
                  if (mhz != null && mhz in 2.0..5000.0) {
                    val newPeriod = (1000.0 / mhz).coerceIn(0.2, 500.0)
                    periodText = String.format(Locale.US, "%.2f", newPeriod)
                    val updated = clocks.toMutableList()
                    updated[idx] = clk.copy(periodNs = newPeriod)
                    onClocksChange(updated, false)
                  }
                },
                label = { Text("Freq (MHz)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1f).testTag("sdc_clock_freq_input_$idx"),
              )
              OutlinedTextField(
                value = periodText,
                onValueChange = { raw ->
                  periodText = raw
                  val pNs = raw.toDoubleOrNull()
                  if (pNs != null && pNs in 0.2..500.0) {
                    val newMhz = 1000.0 / pNs
                    freqText = String.format(Locale.US, "%.1f", newMhz)
                    val updated = clocks.toMutableList()
                    updated[idx] = clk.copy(periodNs = pNs)
                    onClocksChange(updated, false)
                  }
                },
                label = { Text("Period (ns)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1f).testTag("sdc_clock_period_input_$idx"),
              )
            }

            // Quick frequency presets
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              listOf(100.0, 250.0, 400.0, 500.0, 800.0, 1000.0).forEach { targetMhz ->
                val targetPeriod = 1000.0 / targetMhz
                val selected = abs(clk.frequencyMhz - targetMhz) < 2.0
                FilterChip(
                  selected = selected,
                  onClick = {
                    val updated = clocks.toMutableList()
                    updated[idx] = clk.copy(periodNs = targetPeriod)
                    onClocksChange(updated, true)
                  },
                  label = {
                    Text(
                      if (targetMhz >= 1000.0) "1 GHz" else "${targetMhz.toInt()} MHz",
                      style = MaterialTheme.typography.labelSmall,
                    )
                  },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("sdc_freq_chip_${idx}_${targetMhz.toInt()}"),
                )
              }
            }

            // Port assignment chips
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("Port:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              likelyClockPorts.forEach { pName ->
                FilterChip(
                  selected = (clk.portName ?: clk.name).equals(pName, ignoreCase = true),
                  onClick = {
                    val updated = clocks.toMutableList()
                    updated[idx] = clk.copy(portName = pName)
                    onClocksChange(updated, true)
                  },
                  label = { Text(pName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
            }
          }
        }
      }
    }

    OutlinedButton(
      onClick = {
        val nextIdx = clocks.size + 1
        val port = likelyClockPorts.getOrNull(clocks.size) ?: "clk_$nextIdx"
        val newClk = SdcClock(
          name = if (clocks.isEmpty()) port else "clk_$nextIdx",
          periodNs = clockPeriodNs.coerceIn(0.4, 50.0),
          portName = port,
        )
        onClocksChange(clocks + newClk, true)
      },
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      modifier = Modifier.heightIn(min = 48.dp).testTag("sdc_add_clock_button"),
    ) {
      Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(4.dp))
      Text("Add Clock Domain", style = MaterialTheme.typography.labelMedium)
    }

    // Global clock uncertainty (jitter / skew margin)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("Clock Uncertainty / Jitter", style = MaterialTheme.typography.labelMedium)
        Text(
          text = "${"%.2f".format(Locale.US, uncertaintyNs)} ns",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Slider(
        value = uncertaintyNs.toFloat().coerceIn(0f, 1.5f),
        onValueChange = { onUncertaintyChange(it.toDouble()) },
        valueRange = 0f..1.5f,
        modifier = Modifier.testTag("sdc_uncertainty_slider"),
      )
    }
  }
}

@Composable
private fun IoDelaysSection(
  title: String,
  emptyHint: String,
  delays: List<SdcIoDelay>,
  isInput: Boolean,
  availablePorts: List<String>,
  availableClocks: List<String>,
  syncVersion: Int,
  addTag: String,
  rowTagPrefix: String,
  onDelaysChange: (List<SdcIoDelay>, Boolean) -> Unit,
) {
  val eda = LocalEdaColors.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    if (delays.isEmpty()) {
      Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      delays.forEachIndexed { idx, io ->
        var delayText by remember(idx, syncVersion) {
          mutableStateOf(String.format(Locale.US, "%.2f", io.delayNs))
        }
        var patternText by remember(idx, syncVersion) {
          mutableStateOf(io.portPattern)
        }

        LaunchedEffect(io.delayNs) {
          val cur = delayText.toDoubleOrNull()
          if (cur == null || abs(cur - io.delayNs) > 0.015) {
            delayText = String.format(Locale.US, "%.2f", io.delayNs)
          }
        }

        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("${rowTagPrefix}_$idx"),
        ) {
          Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = patternText,
                onValueChange = { raw ->
                  patternText = raw
                  val updated = delays.toMutableList()
                  updated[idx] = io.copy(portPattern = raw.ifBlank { "*" })
                  onDelaysChange(updated, false)
                },
                label = { Text("Port Pattern") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1.2f).testTag("${rowTagPrefix}_port_$idx"),
              )
              OutlinedTextField(
                value = delayText,
                onValueChange = { raw ->
                  delayText = raw
                  val d = raw.toDoubleOrNull()
                  if (d != null && d in 0.0..200.0) {
                    val updated = delays.toMutableList()
                    updated[idx] = io.copy(delayNs = d)
                    onDelaysChange(updated, false)
                  }
                },
                label = { Text("Delay (ns)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1f).testTag("${rowTagPrefix}_val_$idx"),
              )
              IconButton(
                onClick = { onDelaysChange(delays.filterIndexed { i, _ -> i != idx }, true) },
                modifier = Modifier.size(48.dp).testTag("${rowTagPrefix}_delete_$idx"),
              ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete constraint", tint = eda.error)
              }
            }

            // Quick port chips + delay presets
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              availablePorts.take(8).forEach { port ->
                FilterChip(
                  selected = io.portPattern == port,
                  onClick = {
                    val updated = delays.toMutableList()
                    updated[idx] = io.copy(portPattern = port)
                    onDelaysChange(updated, true)
                  },
                  label = { Text(if (port == "*") "All (*)" else port, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("Delay:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              listOf(0.15, 0.30, 0.60, 1.00, 1.50).forEach { preset ->
                FilterChip(
                  selected = abs(io.delayNs - preset) < 0.02,
                  onClick = {
                    val updated = delays.toMutableList()
                    updated[idx] = io.copy(delayNs = preset)
                    onDelaysChange(updated, true)
                  },
                  label = { Text("${"%.2f".format(Locale.US, preset)}ns", style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              if (availableClocks.size > 1) {
                availableClocks.forEach { clkName ->
                  FilterChip(
                    selected = io.clockName == clkName,
                    onClick = {
                      val updated = delays.toMutableList()
                      updated[idx] = io.copy(clockName = clkName)
                      onDelaysChange(updated, true)
                    },
                    label = { Text("@$clkName", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
              }
            }
          }
        }
      }
    }

    OutlinedButton(
      onClick = {
        val defaultPort = availablePorts.firstOrNull { port ->
          delays.none { it.portPattern == port }
        } ?: "*"
        val newEntry = SdcIoDelay(
          portPattern = defaultPort,
          clockName = availableClocks.firstOrNull(),
          delayNs = 0.35,
          isInput = isInput,
        )
        onDelaysChange(delays + newEntry, true)
      },
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      modifier = Modifier.heightIn(min = 48.dp).testTag(addTag),
    ) {
      Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(4.dp))
      Text(if (isInput) "Add Input Delay" else "Add Output Delay", style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun OutputLoadsSection(
  loads: List<SdcOutputLoad>,
  availablePorts: List<String>,
  targetLibrary: TargetLibrary,
  syncVersion: Int,
  onLoadsChange: (List<SdcOutputLoad>, Boolean) -> Unit,
) {
  val eda = LocalEdaColors.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Output Capacitive Loads (set_load)", style = MaterialTheme.typography.labelMedium)
    if (loads.isEmpty()) {
      Text(
        text = "No capacitive output load constraints set. Add set_load (pF) to model external pin/board capacitance in STA.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      loads.forEachIndexed { idx, load ->
        var loadText by remember(idx, syncVersion) {
          mutableStateOf(String.format(Locale.US, "%.2f", load.loadPf))
        }
        var patternText by remember(idx, syncVersion) {
          mutableStateOf(load.portPattern)
        }

        LaunchedEffect(load.loadPf) {
          val cur = loadText.toDoubleOrNull()
          if (cur == null || abs(cur - load.loadPf) > 0.015) {
            loadText = String.format(Locale.US, "%.2f", load.loadPf)
          }
        }

        val estRcDelayNs = SdcParser.outputLoadDelayNs(load.loadPf, targetLibrary)

        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth().testTag("sdc_output_load_row_$idx"),
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
              Text(
                text = "Est. Buffer RC Delay: +${"%.3f".format(Locale.US, estRcDelayNs)} ns",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
                color = eda.warning,
              )
              FilterChip(
                selected = load.isPinLoad,
                onClick = {
                  val updated = loads.toMutableList()
                  updated[idx] = load.copy(isPinLoad = !load.isPinLoad)
                  onLoadsChange(updated, true)
                },
                label = {
                  Text(if (load.isPinLoad) "-pin_load" else "-wire_load", style = MaterialTheme.typography.labelSmall)
                },
                modifier = Modifier.heightIn(min = 48.dp),
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = patternText,
                onValueChange = { raw ->
                  patternText = raw
                  val updated = loads.toMutableList()
                  updated[idx] = load.copy(portPattern = raw.ifBlank { "*" })
                  onLoadsChange(updated, false)
                },
                label = { Text("Output Port") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1.2f).testTag("sdc_output_load_port_$idx"),
              )
              OutlinedTextField(
                value = loadText,
                onValueChange = { raw ->
                  loadText = raw
                  val pf = raw.toDoubleOrNull()
                  if (pf != null && pf in 0.0..100.0) {
                    val updated = loads.toMutableList()
                    updated[idx] = load.copy(loadPf = pf)
                    onLoadsChange(updated, false)
                  }
                },
                label = { Text("Load (pF)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                modifier = Modifier.weight(1f).testTag("sdc_output_load_val_$idx"),
              )
              IconButton(
                onClick = { onLoadsChange(loads.filterIndexed { i, _ -> i != idx }, true) },
                modifier = Modifier.size(48.dp).testTag("sdc_output_load_delete_$idx"),
              ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete output load", tint = eda.error)
              }
            }

            Slider(
              value = load.loadPf.toFloat().coerceIn(0f, 5f),
              onValueChange = { pf ->
                val updated = loads.toMutableList()
                updated[idx] = load.copy(loadPf = pf.toDouble())
                onLoadsChange(updated, false)
              },
              valueRange = 0f..5f,
              modifier = Modifier.testTag("sdc_output_load_slider_$idx"),
            )

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              availablePorts.take(6).forEach { port ->
                FilterChip(
                  selected = load.portPattern == port,
                  onClick = {
                    val updated = loads.toMutableList()
                    updated[idx] = load.copy(portPattern = port)
                    onLoadsChange(updated, true)
                  },
                  label = { Text(if (port == "*") "All (*)" else port, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              listOf(0.05, 0.25, 0.50, 1.50, 3.00).forEach { presetPf ->
                FilterChip(
                  selected = abs(load.loadPf - presetPf) < 0.02,
                  onClick = {
                    val updated = loads.toMutableList()
                    updated[idx] = load.copy(loadPf = presetPf)
                    onLoadsChange(updated, true)
                  },
                  label = { Text("${"%.2f".format(Locale.US, presetPf)}pF", style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("sdc_load_chip_${idx}_${(presetPf * 100).toInt()}"),
                )
              }
            }
          }
        }
      }
    }

    OutlinedButton(
      onClick = {
        val defaultPort = availablePorts.firstOrNull { port ->
          loads.none { it.portPattern == port }
        } ?: "*"
        onLoadsChange(loads + SdcOutputLoad(portPattern = defaultPort, loadPf = 0.50, isPinLoad = true), true)
      },
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      modifier = Modifier.heightIn(min = 48.dp).testTag("sdc_add_output_load_button"),
    ) {
      Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(4.dp))
      Text("Add Output Load", style = MaterialTheme.typography.labelMedium)
    }
  }
}
