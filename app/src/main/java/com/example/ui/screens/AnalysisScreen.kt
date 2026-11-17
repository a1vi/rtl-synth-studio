package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.LecConfig
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.PowerEstimationConfig
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.TimingPathDetail
import com.example.ui.components.EdaEmptyStatePanel
import com.example.ui.components.TimingReportFixAdvisorCard
import com.example.ui.screens.analysis.AnalysisContextHeader
import com.example.ui.screens.analysis.AreaAnalysisTab
import com.example.ui.screens.analysis.FormalVerificationTab
import com.example.ui.screens.analysis.GdsPhysicalDesignTab
import com.example.ui.screens.analysis.PowerAnalysisTab
import com.example.ui.screens.analysis.TimingAnalysisTab
import com.example.ui.theme.LocalEdaColors
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState

private data class AnalysisSubTabSpec(
  val label: String,
  val icon: ImageVector,
  val testTag: String,
)

@Composable
fun AnalysisScreen(
  state: WorkbenchUiState,
  onSelectNode: (String?) -> Unit,
  onOpenSchematic: () -> Unit,
  onOpenSynthesis: () -> Unit,
  onJumpToRtlLine: (Int) -> Unit,
  onSetCycleCount: (Int) -> Unit,
  onCycleStimulusPattern: (String) -> Unit,
  onStepCustomCellValue: (String, Int, Long, Int) -> Unit,
  onRunSimulation: () -> Unit,
  onRunTestbenchSimulation: () -> Unit,
  onToggleWaveformSignal: (String) -> Unit,
  onResetStimulus: () -> Unit,
  onRegenerateAtpg: (Int) -> Unit,
  onEnableScanInsertion: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
  runsState: UiState<List<SynthesisRun>> = UiState.Success(emptyList()),
  onRunSynthesis: () -> Unit = onOpenSynthesis,
  onViewPathInSchematic: (TimingPathDetail) -> Unit = { path ->
    val focusId = path.stages.lastOrNull { !it.gateType.isPort }?.nodeId ?: path.stages.lastOrNull()?.nodeId
    onSelectNode(focusId)
    onOpenSchematic()
  },
  onLocateNodesInSchematic: (Set<String>) -> Unit = { ids ->
    onSelectNode(ids.firstOrNull())
    onOpenSchematic()
  },
  onSelectActiveTimingPathId: (String?) -> Unit = {},
  onUpdatePowerConfig: ((PowerEstimationConfig) -> PowerEstimationConfig) -> Unit = {},
  onSaveTestbench: ((String) -> Unit)? = null,
  onOpenEditor: (() -> Unit)? = null,
  onApplyClockPeriod: (Double) -> Unit = {},
  onApplyOptimizationGoal: (OptimizationGoal) -> Unit = {},
  onApplySynthesisEngine: (SynthesisEngineProfile) -> Unit = {},
  onApplyTargetLibrary: (TargetLibrary) -> Unit = {},
  requestedSubTab: Int? = null,
  subTabRequestToken: Int = 0,
  onRunLec: (String?) -> Unit = {},
  onUpdateLecConfig: ((LecConfig) -> LecConfig) -> Unit = {},
  onUpdateSdc: (String) -> Unit = {},
  onResetSdc: () -> Unit = {},
) {
  var subTab by rememberSaveable { mutableIntStateOf(0) } // 0=Timing, 1=Area, 2=Power, 3=Sim, 4=GDS, 5=DFT, 6=LEC
  LaunchedEffect(subTabRequestToken) {
    if (subTabRequestToken > 0 && requestedSubTab != null && requestedSubTab in 0..6) {
      subTab = requestedSubTab
    }
  }

  val report = (state.synthesisResult as? SynthesisResult.Success)?.report
  val pastRuns = remember(runsState) { (runsState as? UiState.Success)?.data.orEmpty() }
  val eda = LocalEdaColors.current

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .testTag("analysis_screen"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    AnalysisContextHeader(
      state = state,
      onRunSynthesis = onRunSynthesis,
    )

    val tabs = remember {
      listOf(
        AnalysisSubTabSpec("Time", Icons.Rounded.Timeline, "analysis_tab_timing"),
        AnalysisSubTabSpec("Area", Icons.Rounded.Memory, "analysis_tab_area"),
        AnalysisSubTabSpec("Pwr", Icons.Rounded.Bolt, "analysis_tab_power"),
        AnalysisSubTabSpec("Sim", Icons.Rounded.Waves, "analysis_tab_simulate"),
        AnalysisSubTabSpec("GDS", Icons.Rounded.GridView, "analysis_tab_gds"),
        AnalysisSubTabSpec("DFT", Icons.Rounded.BugReport, "analysis_tab_dft"),
        AnalysisSubTabSpec("LEC", Icons.AutoMirrored.Rounded.FactCheck, "analysis_tab_lec"),
      )
    }

    Surface(
      shape = MaterialTheme.shapes.medium,
      color = eda.elevatedCard,
      border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        tabs.forEachIndexed { idx, spec ->
          val selected = subTab == idx
          Box(
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 40.dp)
              .clip(MaterialTheme.shapes.small)
              .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
              )
              .clickable { subTab = idx }
              .padding(horizontal = 2.dp, vertical = 3.dp)
              .testTag(spec.testTag),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
              Icon(
                imageVector = spec.icon,
                contentDescription = spec.label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                  fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }

    when (subTab) {
      0 -> {
        if (report == null) {
          Column(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            EdaEmptyStatePanel(
              icon = Icons.Rounded.Speed,
              title = "No Timing Analysis Yet",
              message = "Synthesize your RTL design to compute STA arrival/required times, setup/hold slack, and critical path delay, or paste an external timing report below.",
              actionLabel = "Go to Synthesis",
              onAction = onOpenSynthesis,
            )
            TimingReportFixAdvisorCard(
              report = null,
              summary = null,
              layout = null,
              activeRtlSource = state.verilogCode,
              onJumpToRtlLine = onJumpToRtlLine,
              onNotify = onNotify,
            )
          }
        } else {
          TimingAnalysisTab(
            report = report,
            layout = state.physicalLayout,
            activePathId = state.activeTimingPathId,
            onSelectActivePathId = onSelectActiveTimingPathId,
            onInspectNode = { nodeId -> onLocateNodesInSchematic(setOf(nodeId)) },
            onViewPathInSchematic = onViewPathInSchematic,
            onJumpToRtlLine = onJumpToRtlLine,
            onApplyClockPeriod = onApplyClockPeriod,
            onApplyOptimizationGoal = onApplyOptimizationGoal,
            onApplySynthesisEngine = onApplySynthesisEngine,
            onApplyTargetLibrary = onApplyTargetLibrary,
            activeRtlSource = state.verilogCode,
            onNotify = onNotify,
            sdcText = state.sdcText,
            ports = state.parsedDesign?.topModule?.ports.orEmpty(),
            onUpdateSdc = onUpdateSdc,
            onResetSdc = onResetSdc,
            modifier = Modifier.weight(1f),
          )
        }
      }

      1 -> {
        if (report == null) {
          EdaEmptyStatePanel(
            icon = Icons.Rounded.Layers,
            title = "No Area Breakdown Yet",
            message = "Run synthesis to inspect cell counts, buffer/inverter usage, synthesized silicon area, and physical core utilization.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          AreaAnalysisTab(
            report = report,
            layout = state.physicalLayout,
            pastRuns = pastRuns,
            onLocateCellsInSchematic = onLocateNodesInSchematic,
            modifier = Modifier.weight(1f),
          )
        }
      }

      2 -> {
        if (report == null) {
          EdaEmptyStatePanel(
            icon = Icons.Rounded.Bolt,
            title = "No Power Analysis Yet",
            message = "Run synthesis (and optionally RTL simulation for VCD toggle activity) to compute switching, internal, and leakage power.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          val resolvedPower = remember(state.powerAnalysis, report, state.simulationResult, state.powerConfig) {
            if (state.powerConfig != PowerEstimationConfig()) {
              PowerAnalysisEngine.analyze(report, state.simulationResult, state.powerConfig)
            } else {
              state.powerAnalysis ?: report.powerAnalysis ?: PowerAnalysisEngine.analyze(report, state.simulationResult, state.powerConfig)
            }
          }
          PowerAnalysisTab(
            report = report,
            power = resolvedPower,
            powerConfig = state.powerConfig,
            simulation = state.simulationResult,
            pastRuns = pastRuns,
            onUpdatePowerConfig = onUpdatePowerConfig,
            onOpenSimulateTab = { subTab = 3 },
            onRunSimulation = onRunSimulation,
            onLocateNodeInSchematic = { nodeId -> onLocateNodesInSchematic(setOf(nodeId)) },
            modifier = Modifier.weight(1f),
          )
        }
      }

      3 -> {
        val tbSource = state.files.firstOrNull { it.language == RtlLanguage.TESTBENCH }?.content.orEmpty()
        val hasTb = tbSource.isNotBlank()
        SimulationScreen(
          simulation = state.simulationResult,
          cycleCount = state.simCycleCount,
          stimulusPatterns = state.stimulusPatterns,
          onSetCycleCount = onSetCycleCount,
          onCycleStimulusPattern = onCycleStimulusPattern,
          onStepCustomCellValue = onStepCustomCellValue,
          onRunSimulation = onRunSimulation,
          onResetStimulus = onResetStimulus,
          selectedSignals = state.selectedWaveformSignals,
          onToggleSignal = onToggleWaveformSignal,
          hasTestbench = hasTb,
          onRunTestbench = onRunTestbenchSimulation,
          clockPeriodNs = state.clockPeriodNs,
          powerAnalysis = state.powerAnalysis ?: report?.powerAnalysis,
          synthesisReport = report,
          testbenchSource = tbSource,
          onSaveTestbench = onSaveTestbench,
          onOpenEditor = onOpenEditor,
          onNotify = onNotify,
          modifier = Modifier.weight(1f),
        )
      }

      4 -> {
        GdsPhysicalDesignTab(
          report = report,
          layout = state.physicalLayout,
          flowStages = state.flowStages,
          onOpenSynthesis = onOpenSynthesis,
          onNotify = onNotify,
          modifier = Modifier.weight(1f),
        )
      }

      5 -> {
        val dftReport = report?.dftReport
        if (report == null || dftReport == null) {
          EdaEmptyStatePanel(
            icon = Icons.Rounded.BugReport,
            title = "No DFT / ATPG Report Available",
            message = "Run RTL synthesis to inspect scan chains, DRC-DFT rules, fault coverage, and ATPG test vectors.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          DftReportTab(
            report = report,
            dft = dftReport,
            onRegenerateAtpg = onRegenerateAtpg,
            onNotify = onNotify,
            modifier = Modifier.weight(1f),
            onEnableScanInsertion = onEnableScanInsertion,
            onLocateScanCellInSchematic = { nodeId -> onLocateNodesInSchematic(setOf(nodeId)) },
          )
        }
      }

      6 -> {
        if (report == null) {
          EdaEmptyStatePanel(
            icon = Icons.AutoMirrored.Rounded.FactCheck,
            title = "No Formal LEC Report Yet",
            message = "Synthesize your RTL design to run Golden RTL vs. Revised Gate-Level Netlist equivalence checking.",
            actionLabel = "Go to Synthesis",
            onAction = onOpenSynthesis,
          )
        } else {
          FormalVerificationTab(
            report = report,
            lecReport = state.lecReport,
            lecConfig = state.lecConfig,
            isRunningLec = state.isRunningLec,
            isResultOutdated = state.isResultOutdated,
            onUpdateLecConfig = onUpdateLecConfig,
            onRunLec = onRunLec,
            onLocateConeInSchematic = onLocateNodesInSchematic,
            onJumpToRtlLine = onJumpToRtlLine,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}
