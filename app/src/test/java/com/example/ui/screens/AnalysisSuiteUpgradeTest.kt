package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import kotlinx.coroutines.runBlocking
import com.example.domain.synth.AreaAnalysisHelper
import com.example.domain.synth.DftConfig
import com.example.domain.synth.GateType
import com.example.domain.synth.OperatingCorner
import com.example.domain.synth.PowerAnalysisEngine
import com.example.domain.synth.PowerEstimationConfig
import com.example.domain.synth.TestRtlFixtures
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.RtlToGdsFlowEngine
import com.example.domain.synth.StaticTimingAnalyzer
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.TimingAnalysisStage
import com.example.domain.synth.TimingPathDetail
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.screens.analysis.GdsPhysicalDesignTab
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.RtlSynthViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class AnalysisSuiteUpgradeTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun dashboardScreen_omitsRedundantShortcutButtonsAndKeepsProjectActions() {
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        DashboardScreen(
          workbench = WorkbenchUiState(designName = "counter_8bit"),
          designsState = UiState.Success(listOf(RtlDesign(id = "d1", name = "counter_8bit"))),
          runsState = UiState.Success(emptyList()),
          onCreateProject = { _, _, _ -> },
          onOpenProjects = {},
          onImportProjectFile = { _, _ -> },
          onOpenDesign = {},
          onOpenPreset = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_new_project_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_open_project_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_import_verilog_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dashboard_open_editor_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dashboard_run_synth_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dashboard_open_schematic_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dashboard_open_analysis_button").assertDoesNotExist()
  }

  @Test
  fun staticTimingAnalyzer_handlesPreCtsPostRouteAndZeroDelayHonesty() {
    val preset = TestRtlFixtures.counter8Bit
    val synthRes = RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    // Pre-layout STA: setup paths present, holdWnsNs strictly null until CTS
    val preLayoutSta = StaticTimingAnalyzer.analyze(synthRes.report, layout = null)
    assertTrue(preLayoutSta.isAvailable)
    assertEquals(TimingAnalysisStage.PRE_LAYOUT_POST_SYNTH, preLayoutSta.stage)
    assertTrue(preLayoutSta.paths.isNotEmpty())
    assertNull("Hold slack must be unavailable pre-CTS", preLayoutSta.holdWnsNs)

    // Post-route STA with layout: hold slack becomes populated & post-route stage reported
    val flowEvent = runBlocking {
      RtlToGdsFlowEngine.runFlow(
        rtlSource = preset.verilogCode,
        designName = "counter_8bit",
        library = TargetLibrary.ASAP7_7NM,
        goal = OptimizationGoal.BALANCED,
        engineProfile = SynthesisEngineProfile.SILICON_FLOW,
        clockPeriodNs = 2.0,
        coreUtilizationPercent = 60,
        sdcText = "",
        stepDelayMs = 0L,
        onProgress = {},
      )
    }
    val postRouteSta = StaticTimingAnalyzer.analyze(synthRes.report, layout = flowEvent.layoutSnapshot)
    assertEquals(TimingAnalysisStage.POST_ROUTE, postRouteSta.stage)
    assertNotNull(postRouteSta.holdWnsNs)

    // Blank / unconstrained report with 0 delay must NOT fake Fmax as 1000 / clockPeriodNs
    val blankReport = RtlSynthesisEngine.createBlankManualReport(
      moduleName = "empty_mod",
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )
    assertEquals(0.0, blankReport.fmaxMhz, 1e-6)
    val unavailableSta = StaticTimingAnalyzer.analyze(blankReport, layout = null)
    assertFalse(unavailableSta.isAvailable)
    assertNull(unavailableSta.estimatedFmaxMhz)
  }

  @Test
  fun powerAnalysisAndAreaHelperAndVcdExport_produceHonestEngineeringOutputs() {
    val preset = TestRtlFixtures.counter8Bit
    val synthRes = RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.SKY130_130NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 4.0,
    ) as SynthesisResult.Success
    val design = (VerilogHdlParser.parse(preset.verilogCode) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(design, cycleCount = 12)

    // Power without simulation has null peakCyclePowerMw; with sim it is populated
    val vectorlessPwr = PowerAnalysisEngine.analyze(synthRes.report, simulation = null)
    assertNull(vectorlessPwr.peakCyclePowerMw)
    val expectedPowerCells = synthRes.report.nodes.count { !it.gateType.isPort && it.gateType != GateType.CONST }
    assertEquals(expectedPowerCells, vectorlessPwr.allPowerNodes.size)

    val vcdPwr = PowerAnalysisEngine.analyze(synthRes.report, simulation = sim)
    assertNotNull(vcdPwr.peakCyclePowerMw)

    // Overriding VDD / Frequency / Thermal corner scales power meaningfully
    val hotHighFreqPwr = PowerAnalysisEngine.analyze(
      synthRes.report,
      simulation = sim,
      config = PowerEstimationConfig(
        clockFreqOverrideMhz = 500.0,
        vddOverrideVolts = 1.8,
        temperatureC = 125.0,
        operatingCorner = OperatingCorner.SLOW,
      ),
    )
    assertTrue(hotHighFreqPwr.totalPowerMw > vcdPwr.totalPowerMw)
    assertTrue(hotHighFreqPwr.leakagePowerMw > vcdPwr.leakagePowerMw)

    // Area helper delta comparison
    val baseline = SynthesisRun(
      id = "run_prev",
      designName = "counter_8bit",
      targetLibrary = TargetLibrary.SKY130_130NM.id,
      areaUm2 = synthRes.report.totalAreaUm2 + 12.0,
      cellCount = synthRes.report.cellCount + 3L,
      powerMw = vcdPwr.totalPowerMw + 0.1,
    )
    val delta = AreaAnalysisHelper.compareRuns(synthRes.report, baseline)
    assertEquals(-12.0, delta.deltaAreaUm2, 1e-3)
    assertEquals(-3, delta.deltaCellCount)

    // Standard IEEE-1364 VCD output
    val vcdText = RtlSimulator.exportVcd(sim, 4.0)
    assertTrue(vcdText.contains("\$timescale 1ps \$end"))
    assertTrue(vcdText.contains("\$scope module counter_8bit \$end"))
    assertTrue(vcdText.contains("\$enddefinitions \$end"))
    assertTrue(vcdText.contains("\$dumpvars"))
  }

  @Test
  fun viewModel_tracksOutdatedStatusConfigUpdatesAndSchematicPathHighlight() {
    val tempDir = Files.createTempDirectory("eda_vm_upgrade").toFile()
    val vmStore = ViewModelStore()
    try {
      val vm = RtlSynthViewModel(localStore = LocalWorkspaceStore(tempDir))
      vmStore.put("vm", vm)

      val preset = TestRtlFixtures.counter8Bit
      vm.loadPreset(preset)
      val synthRes = RtlSynthesisEngine.synthesize(
        verilogSource = preset.verilogCode,
        library = TargetLibrary.ASAP7_7NM,
        goal = OptimizationGoal.BALANCED,
        clockPeriodNs = 2.0,
      ) as SynthesisResult.Success
      val sta = StaticTimingAnalyzer.analyze(synthRes.report, null)
      val firstPath = sta.paths.first()

      // Inspect path highlights its cells and nets and preserves activeTimingPathId
      vm.inspectTimingPathInSchematic(firstPath)
      val afterInspect = vm.workbench.value
      assertEquals(firstPath.pathId, afterInspect.activeTimingPathId)
      assertEquals(firstPath.nodeIds, afterInspect.highlightedPathNodeIds)

      vm.clearSchematicPathHighlight()
      assertTrue(vm.workbench.value.highlightedPathNodeIds.isEmpty())

      // Update power config scales estimation cleanly
      vm.updatePowerConfig { it.copy(clockFreqOverrideMhz = 500.0, vddOverrideVolts = 1.2) }
      assertEquals(500.0, vm.workbench.value.powerConfig.clockFreqOverrideMhz ?: 0.0, 1e-6)
      assertEquals(1.2, vm.workbench.value.powerConfig.vddOverrideVolts ?: 0.0, 1e-6)
    } finally {
      vmStore.clear()
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun analysisScreen_exercisesAllSixUpgradedTabsIncludingDftScanInspectAndGdsStatus() {
    val preset = TestRtlFixtures.counter8Bit
    val scanConfig = DftConfig(scanInsertionEnabled = true)
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
      dftConfig = scanConfig,
    ) as SynthesisResult.Success).report
    val design = (VerilogHdlParser.parse(preset.verilogCode) as VerilogParseResult.Success).design
    val sim = RtlSimulator.simulate(design, cycleCount = 8)
    val baselineRun = SynthesisRun(
      id = "r_base",
      designName = "counter_8bit",
      targetLibrary = TargetLibrary.ASAP7_7NM.id,
      areaUm2 = report.totalAreaUm2 + 5.0,
      cellCount = report.cellCount + 2L,
      powerMw = 0.35,
    )

    var viewedPath: TimingPathDetail? = null
    var resynthClicked = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var powerCfg by remember { mutableStateOf(PowerEstimationConfig()) }
        var activePathId by remember { mutableStateOf<String?>(null) }
        AnalysisScreen(
          state = WorkbenchUiState(
            designName = "counter_8bit",
            verilogCode = preset.verilogCode,
            synthesisResult = SynthesisResult.Success(report),
            simulationResult = sim,
            physicalLayout = null,
            isResultOutdated = true,
            synthesisRunSequence = 2,
            dftConfig = scanConfig,
            powerConfig = powerCfg,
            activeTimingPathId = activePathId,
          ),
          runsState = UiState.Success(listOf(baselineRun)),
          onSelectNode = {},
          onOpenSchematic = {},
          onOpenSynthesis = {},
          onJumpToRtlLine = {},
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onRunTestbenchSimulation = {},
          onToggleWaveformSignal = {},
          onResetStimulus = {},
          onRegenerateAtpg = {},
          onEnableScanInsertion = {},
          onNotify = {},
          onRunSynthesis = { resynthClicked = true },
          onViewPathInSchematic = { viewedPath = it },
          onLocateNodesInSchematic = {},
          onSelectActiveTimingPathId = { activePathId = it },
          onUpdatePowerConfig = { transform -> powerCfg = transform(powerCfg) },
        )
      }
    }

    composeTestRule.waitForIdle()
    // Shared Context Header + Outdated banner
    composeTestRule.onNodeWithTag("analysis_context_header").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_outdated_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_resynth_button").performClick()
    assertTrue(resynthClicked)

    // 1. Time Tab
    composeTestRule.onNodeWithTag("analysis_timing_summary_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("N/A (pre-CTS)").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_timing_list")
      .performScrollToNode(hasTestTag("analysis_view_in_schematic_button"))
    composeTestRule.onNodeWithTag("analysis_view_in_schematic_button").performClick()
    assertNotNull("Expected View in Schematic callback with active TimingPathDetail", viewedPath)

    // 2. Area Tab
    composeTestRule.onNodeWithTag("analysis_tab_area").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_area_summary_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_area_list")
      .performScrollToNode(hasTestTag("area_resource_donut_card"))
    composeTestRule.onNodeWithTag("area_resource_donut_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analysis_area_list")
      .performScrollToNode(hasTestTag("area_cell_search_input"))
    composeTestRule.onNodeWithTag("area_cell_search_input").performTextReplacement("DFF")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_area_list")
      .performScrollToNode(hasTestTag("analysis_area_compare_card"))
    composeTestRule.onNodeWithText("Compare against 1 saved run(s)").assertIsDisplayed()

    // 3. Power Tab
    composeTestRule.onNodeWithTag("analysis_tab_power").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_power_methodology_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_config_toggle_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("power_freq_chip_500").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // 4. Simulation Tab
    composeTestRule.onNodeWithTag("analysis_tab_simulate").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_engine_badge").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sim_export_vcd_button").assertExists()
    composeTestRule.onNodeWithTag("sim_toggle_signal_filter").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_group_in").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sim_signal_search_input").assertIsDisplayed()

    // 5. GDS Tab (honest NOT RUN pre-PnR status)
    composeTestRule.onNodeWithTag("analysis_tab_gds").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_gds_progress_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("gds_run_pnr_button").assertIsDisplayed()

    // 6. DFT Tab
    composeTestRule.onNodeWithTag("analysis_tab_dft").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_kpi_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_toggle_chain_nodes_button"))
    composeTestRule.onNodeWithTag("dft_toggle_chain_nodes_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_scan_cell_chip_0_0"))
    composeTestRule.onNodeWithTag("dft_scan_cell_chip_0_0").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_inspected_scan_cell_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dft_locate_cell_schematic_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_report_list")
      .performScrollToNode(hasTestTag("dft_fault_model_transition_delay"))
    composeTestRule.onNodeWithTag("dft_fault_model_transition_delay").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dft_unsupported_fault_model_notice").assertIsDisplayed()

    composeTestRule.onNodeWithTag("dft_atpg_format_hex").performScrollTo().performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun gdsPhysicalDesignTab_showsGdsViewerFirstWhenLayoutReady() {
    val flowEvent = runBlocking {
      RtlToGdsFlowEngine.runFlow(
        rtlSource = "module gds_first(input wire clk, input wire a, output reg y); always @(posedge clk) y <= a; endmodule",
        designName = "gds_first",
        library = TargetLibrary.SKY130_130NM,
        goal = OptimizationGoal.BALANCED,
        engineProfile = SynthesisEngineProfile.SILICON_FLOW,
        clockPeriodNs = 4.0,
        coreUtilizationPercent = 60,
        sdcText = "",
        stepDelayMs = 0L,
        onProgress = {},
      )
    }
    val report = (flowEvent.synthesisResult as SynthesisResult.Success).report
    val layout = flowEvent.layoutSnapshot!!

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        GdsPhysicalDesignTab(
          report = report,
          layout = layout,
          flowStages = flowEvent.stageStates,
          onOpenSynthesis = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    // GDS layout viewer and canvas must be immediately displayed at the top before scrolling
    composeTestRule.onNodeWithTag("analysis_gds_viewer_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("gds_layout_canvas").assertIsDisplayed()

    val viewerBounds = composeTestRule.onNodeWithTag("analysis_gds_viewer_card").fetchSemanticsNode().boundsInRoot
    composeTestRule.onNodeWithTag("analysis_gds_list")
      .performScrollToNode(hasTestTag("analysis_gds_metrics_card"))
    val metricsBounds = composeTestRule.onNodeWithTag("analysis_gds_metrics_card").fetchSemanticsNode().boundsInRoot
    assertTrue("GDS viewer should start at top of GDS tab", viewerBounds.top < 20f)
    assertTrue("Metrics card should appear after GDS viewer", metricsBounds.top > 0f)
  }
}
