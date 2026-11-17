package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
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
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.PowerAnalysisReport
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.WaveformRadix
import com.example.ui.components.SimulationPowerActivityPanel
import com.example.ui.components.WaveformViewer
import com.example.ui.theme.JetBrainsMonoFamily

import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlin.math.abs

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
  selectedSignals: Set<String> = emptySet(),
  onToggleSignal: (String) -> Unit = {},
  hasTestbench: Boolean = false,
  onRunTestbench: () -> Unit = {},
  clockPeriodNs: Double = 5.0,
  powerAnalysis: PowerAnalysisReport? = null,
  synthesisReport: SynthesisReport? = null,
  initialTab: Int = 0,
  tabRequestToken: Int = 0,
  testbenchSource: String = "",
  onSaveTestbench: ((String) -> Unit)? = null,
  onOpenEditor: (() -> Unit)? = null,
  onNotify: (String) -> Unit = {},
) {
  val context = LocalContext.current
  var radix by rememberSaveable { mutableStateOf(WaveformRadix.HEX) }
  var selectedCycle by rememberSaveable { mutableIntStateOf(0) }
  var cursorBCycle by rememberSaveable { mutableStateOf<Int?>(null) }
  var activeCursorIsB by rememberSaveable { mutableStateOf(false) }
  var zoomScale by rememberSaveable { mutableFloatStateOf(1f) }
  var activeTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) } // 0 = Waveforms, 1 = Console & Assertions, 2 = Power
  var signalSearch by rememberSaveable { mutableStateOf("") }
  var signalGroup by rememberSaveable { mutableIntStateOf(0) }
  var showTbEditor by rememberSaveable { mutableStateOf(false) }
  var tbDraft by remember(testbenchSource, simulation?.moduleName) {
    mutableStateOf(
      testbenchSource.ifBlank {
        val mod = simulation?.moduleName ?: synthesisReport?.moduleName ?: "rtl_top"
        "// Stimulus testbench for $mod\ninitial begin\n  // cycle: signal=value\n  #0;\nend\n"
      }
    )
  }
  var pendingVcdText by remember { mutableStateOf<String?>(null) }
  val vcdExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri: Uri? ->
    val vcd = pendingVcdText
    pendingVcdText = null
    if (uri != null && vcd != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(vcd.toByteArray(Charsets.UTF_8)) }
      }.onSuccess {
        onNotify("Exported IEEE-1364 .vcd waveform.")
      }.onFailure { err ->
        onNotify("VCD export failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }
  androidx.compose.runtime.LaunchedEffect(tabRequestToken) {
    if (tabRequestToken > 0) {
      activeTab = initialTab.coerceIn(0, 2)
    }
  }

  val maxCycle = ((simulation?.cycleCount ?: cycleCount) - 1).coerceAtLeast(0)
  val safeSelectedCycle = selectedCycle.coerceIn(0, maxCycle)
  val safeCursorB = cursorBCycle?.coerceIn(0, maxCycle)

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
        if (simulation != null) {
          Text(
            text = "${simulation.engineLabel} · Runtime ${simulation.executionDurationMs} ms",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("sim_engine_badge"),
          )
        }
      }

      SingleChoiceSegmentedButtonRow(
        modifier = Modifier.width(IntrinsicSize.Max),
      ) {
        WaveformRadix.entries.forEachIndexed { idx, r ->
          SegmentedButton(
            selected = radix == r,
            onClick = { radix = r },
            shape = SegmentedButtonDefaults.itemShape(idx, WaveformRadix.entries.size),
            modifier = Modifier.testTag("sim_radix_${r.name.lowercase()}"),
          ) {
            Text(r.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

    // Mode Switch: Waveforms vs Console & Assertions vs Power & Activity
    val assertFailCount = simulation.assertions.count { it.failedCycles.isNotEmpty() }
    val consoleBadgeCount = simulation.consoleLogs.size + simulation.assertions.size
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      SegmentedButton(
        selected = activeTab == 0,
        onClick = { activeTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(0, 3),
        modifier = Modifier.testTag("sim_tab_waveforms"),
      ) {
        Text(
          text = "Waveforms",
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
      SegmentedButton(
        selected = activeTab == 1,
        onClick = { activeTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(1, 3),
        modifier = Modifier.testTag("sim_tab_console"),
      ) {
        val consoleLabel = buildString {
          append("Console")
          if (consoleBadgeCount > 0) append(" ($consoleBadgeCount)")
          if (assertFailCount > 0) append(" !")
        }
        Text(
          text = consoleLabel,
          style = MaterialTheme.typography.labelMedium,
          color = if (assertFailCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
      SegmentedButton(
        selected = activeTab == 2,
        onClick = { activeTab = 2 },
        shape = SegmentedButtonDefaults.itemShape(2, 3),
        modifier = Modifier.testTag("sim_tab_power"),
      ) {
        val pwrMw = powerAnalysis?.totalPowerMw ?: synthesisReport?.totalPowerMw
        val pwrLabel = if (pwrMw != null && pwrMw > 0.0) {
          "Power (${"%.2f".format(Locale.US, pwrMw)}mW)"
        } else {
          "Power"
        }
        Text(
          text = pwrLabel,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (activeTab == 0) {
      // Pinned Action & Cycle Depth Strip
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

        if (hasTestbench) {
          OutlinedButton(
            onClick = onRunTestbench,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("sim_run_tb_button"),
          ) {
            Text("Run TB", style = MaterialTheme.typography.labelMedium)
          }
        }

        listOf(8, 16, 24, 32).forEach { count ->
          FilterChip(
            selected = cycleCount == count,
            onClick = { onSetCycleCount(count) },
            label = { Text("${count}c", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag("sim_cycles_$count"),
          )
        }

        IconButton(
          onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.6f) },
          modifier = Modifier.size(48.dp).testTag("sim_zoom_out_button"),
        ) {
          Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out waveform")
        }
        IconButton(
          onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) },
          modifier = Modifier.size(48.dp).testTag("sim_zoom_in_button"),
        ) {
          Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in waveform")
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

        OutlinedButton(
          onClick = {
            pendingVcdText = RtlSimulator.exportVcd(simulation, clockPeriodNs)
            vcdExportLauncher.launch("${simulation.moduleName}.vcd")
          },
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("sim_export_vcd_button"),
        ) {
          Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Export .vcd", style = MaterialTheme.typography.labelMedium)
        }

        if (onSaveTestbench != null) {
          OutlinedButton(
            onClick = { showTbEditor = !showTbEditor },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("sim_toggle_tb_editor_button"),
          ) {
            Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (hasTestbench) "Edit TB" else "New TB", style = MaterialTheme.typography.labelMedium)
          }
        }

        if (onOpenEditor != null) {
          TextButton(
            onClick = onOpenEditor,
            modifier = Modifier.heightIn(min = 48.dp).testTag("sim_jump_editor_button"),
          ) {
            Text("Editor", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }

    // Scrollable main content
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (activeTab == 1) {
        SimulationConsoleAndAssertionsTab(
          simulation = simulation,
          onSelectCycleAndJump = { cycle ->
            selectedCycle = cycle
            activeTab = 0
          },
        )
        Spacer(Modifier.height(12.dp))
        return@Column
      }
      if (activeTab == 2) {
        SimulationPowerActivityPanel(
          powerAnalysis = powerAnalysis,
          simulation = simulation,
          synthesisReport = synthesisReport,
        )
        Spacer(Modifier.height(12.dp))
        return@Column
      }

      var showSignalFilter by rememberSaveable { mutableStateOf(false) }

      // Dual-Cursor Delta Measurement & Filter Toggle Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = !activeCursorIsB,
          onClick = { activeCursorIsB = false },
          label = { Text("A: C$safeSelectedCycle", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.testTag("sim_cursor_a_chip"),
        )
        FilterChip(
          selected = activeCursorIsB || safeCursorB != null,
          onClick = {
            if (safeCursorB == null) {
              cursorBCycle = (safeSelectedCycle + 2).coerceAtMost(maxCycle)
              activeCursorIsB = true
            } else {
              activeCursorIsB = !activeCursorIsB
            }
          },
          label = {
            Text(
              text = if (safeCursorB != null) "B: C$safeCursorB" else "+ Cursor B",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          modifier = Modifier.testTag("sim_cursor_b_chip"),
        )
        FilterChip(
          selected = showSignalFilter || signalGroup != 0 || signalSearch.isNotBlank(),
          onClick = { showSignalFilter = !showSignalFilter },
          leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
          label = { Text("Filter", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.testTag("sim_toggle_signal_filter"),
        )
        if (safeCursorB != null) {
          val deltaCycles = abs(safeCursorB - safeSelectedCycle)
          val deltaNs = deltaCycles * clockPeriodNs
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag("sim_cursor_delta_pill"),
          ) {
            Text(
              text = "ΔC=$deltaCycles cyc · Δt=${"%.2f".format(Locale.US, deltaNs)} ns",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              ),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
          TextButton(
            onClick = {
              cursorBCycle = null
              activeCursorIsB = false
            },
            modifier = Modifier.testTag("sim_clear_cursor_b"),
          ) {
            Text("Clear B", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      if (showTbEditor && onSaveTestbench != null) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .testTag("sim_testbench_editor_card"),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text("Inline Verilog / SystemVerilog Testbench", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
              value = tbDraft,
              onValueChange = { tbDraft = it },
              minLines = 4,
              maxLines = 8,
              textStyle = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.5.sp),
              modifier = Modifier.fillMaxWidth().testTag("sim_tb_source_input"),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = {
                  onSaveTestbench(tbDraft)
                  onRunTestbench()
                  showTbEditor = false
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sim_save_and_run_tb_button"),
              ) {
                Text("Save & Run TB", style = MaterialTheme.typography.labelMedium)
              }
              OutlinedButton(
                onClick = { showTbEditor = false },
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text("Close", style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
      }

      if (showSignalFilter) {
        // Signal Search + Group Filter Bar
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          listOf("All", "In", "Out", "Reg", "Clk").forEachIndexed { idx, label ->
            FilterChip(
              selected = signalGroup == idx,
              onClick = { signalGroup = idx },
              label = { Text(label, style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("sim_group_${label.lowercase()}"),
            )
          }
        }
        OutlinedTextField(
          value = signalSearch,
          onValueChange = { signalSearch = it },
          placeholder = { Text("Filter signals (e.g. count, rst, en)...") },
          leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("sim_signal_search_input"),
        )
      }

      // Signal Visibility Selection Strip
      if (simulation.traces.size > 1) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Signals:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          simulation.traces.forEach { trace ->
            val visible = selectedSignals.isEmpty() || trace.name in selectedSignals
            FilterChip(
              selected = visible,
              onClick = { onToggleSignal(trace.name) },
              label = { Text(trace.name, style = MaterialTheme.typography.labelSmall) },
            )
          }
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

      // Digital Waveform Viewer (with pinch-to-zoom + Cursor B + Bus bit expansion)
      WaveformViewer(
        simulation = simulation,
        radix = radix,
        selectedCycle = safeSelectedCycle,
        onSelectCycle = { tappedCycle ->
          if (activeCursorIsB) {
            cursorBCycle = tappedCycle
          } else {
            selectedCycle = tappedCycle
          }
        },
        zoomScale = zoomScale,
        visibleSignals = selectedSignals,
        cursorBCycle = safeCursorB,
        onZoomDelta = { delta ->
          zoomScale = (zoomScale * delta).coerceIn(0.6f, 2.5f)
        },
        signalSearchQuery = signalSearch,
        signalGroupFilter = signalGroup,
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
                text = "Pinch waveform to zoom; tap waveform or step cycles to inspect.",
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

@Composable
private fun SimulationConsoleAndAssertionsTab(
  simulation: SimulationResult,
  onSelectCycleAndJump: (Int) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("sim_console_panel"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Assertions Summary Card
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.large,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Procedural Assertions (${simulation.assertions.size})",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (simulation.assertions.isEmpty()) {
          Text(
            text = "No `assert (...)` statements found in simulated modules. Add `assert (cond)` inside `always` blocks to verify invariants per cycle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          simulation.assertions.forEachIndexed { idx, a ->
            val passed = a.isPassed
            Surface(
              color = if (passed) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
              else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("sim_assertion_row_$idx"),
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
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
                  ) {
                    Icon(
                      imageVector = if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                      contentDescription = null,
                      tint = if (passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                      modifier = Modifier.size(16.dp),
                    )
                    Text(
                      text = "${if (passed) "PASS" else "FAIL"} · L${a.line}",
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Bold,
                      color = if (passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                  }
                  Text(
                    text = "${a.passedCount} ok / ${a.failedCycles.size} fail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(
                  text = a.label,
                  style = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                  ),
                )
                if (a.failedCycles.isNotEmpty()) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = "Fail cycles:",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.error,
                    )
                    a.failedCycles.take(12).forEach { cyc: Int ->
                      FilterChip(
                        selected = false,
                        onClick = { onSelectCycleAndJump(cyc) },
                        label = { Text("C$cyc", style = MaterialTheme.typography.labelSmall) },
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // $display Console Card
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.large,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), MaterialTheme.shapes.large),
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
            imageVector = Icons.Rounded.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "\$display Console Output (${simulation.consoleLogs.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        if (simulation.consoleLogs.isEmpty()) {
          Text(
            text = "No \$display / \$write / \$info messages emitted during this run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          simulation.consoleLogs.forEach { entry ->
            Text(
              text = "[C${entry.cycle} L${entry.line}] ${entry.text}",
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
              ),
            )
          }
        }
      }
    }
  }
}
