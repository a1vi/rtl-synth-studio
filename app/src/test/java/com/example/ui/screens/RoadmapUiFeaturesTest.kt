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
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SimAssertionResult
import com.example.domain.synth.SimConsoleEntry
import com.example.domain.synth.SignalTrace
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.SynthesisResult
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class RoadmapUiFeaturesTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun editorScreen_quickTemplateDrawerInsertsAlwaysFfSnippet() {
    var latestCode = "module top(input logic clk, input logic rst);\n\nendmodule"

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "top",
              verilogCode = latestCode,
              files = listOf(RtlFileEntry("file_rtl_top", "top.sv", RtlLanguage.SYSTEM_VERILOG, latestCode)),
              openTabIds = listOf("file_rtl_top"),
              activeFileId = "file_rtl_top",
              primaryLanguage = RtlLanguage.SYSTEM_VERILOG,
            )
          )
        }
        EditorScreen(
          state = state,
          settings = AppSettings(),
          onFileContentChange = { updated ->
            latestCode = updated
            state = state.copy(
              verilogCode = updated,
              files = state.files.map { it.copy(content = updated) },
            )
          },
          onSelectFileTab = {},
          onCloseFileTab = {},
          onAddFile = { _, _ -> },
          onDeleteFile = {},
          onRunSynthesis = {},
          onSaveProject = {},
          onOpenReports = {},
          onImportFile = { _, _ -> },
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_templates_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_template_sheet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("template_item_always_ff").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertTrue("Expected always_ff template inserted into RTL", latestCode.contains("always_ff @(posedge clk"))
  }

  @Test
  fun simulationScreen_displaysDualCursorDeltaAndConsoleAssertionsTab() {
    val sim = SimulationResult(
      moduleName = "assert_dut",
      cycleCount = 8,
      clockPort = "clk",
      resetPort = "rst",
      traces = listOf(
        SignalTrace("clk", null, 1, isClock = true, isReset = false, isSequentialReg = false, values = listOf(0, 1, 0, 1, 0, 1, 0, 1)),
        SignalTrace("cnt", null, 4, isClock = false, isReset = false, isSequentialReg = true, values = listOf(0, 1, 2, 3, 4, 5, 6, 7)),
      ),
      consoleLogs = listOf(
        SimConsoleEntry(cycle = 2, line = 12, text = "cnt=2"),
        SimConsoleEntry(cycle = 4, line = 12, text = "cnt=4"),
      ),
      assertions = listOf(
        SimAssertionResult(line = 11, label = "cnt < 4'd4", passedCount = 4, failedCycles = listOf(4, 5)),
      ),
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SimulationScreen(
          simulation = sim,
          cycleCount = 8,
          clockPeriodNs = 5.0,
          stimulusPatterns = emptyMap(),
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onResetStimulus = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_cursor_b_chip").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_cursor_delta_pill").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sim_tab_console").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sim_console_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("sim_assertion_row_0").assertIsDisplayed()
    composeTestRule.onNodeWithText("cnt=2", substring = true).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun synthesisScreen_showsPdkDownloaderPanelBitBlastAndRemoteWorkerCard() {
    var toggledBitBlast = false
    var pingedWorker = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SynthesisScreen(
          state = WorkbenchUiState(
            designName = "worker_dut",
            verilogCode = "module worker_dut(input a, output y); assign y = a; endmodule",
            remoteWorkerStatus = "Ready",
          ),
          onSelectTopModule = {},
          onSelectEngine = {},
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onUpdateSdc = {},
          onResetSdc = {},
          onRunSynthesis = {},
          onJumpToEditor = {},
          onOpenReports = {},
          onNotify = {},
          bitBlastGates = false,
          onToggleBitBlastGates = { toggledBitBlast = true },
          localBridgeEnabled = true,
          localBridgeEndpoint = "http://192.168.1.50:8765",
          onTestRemoteWorker = { pingedWorker = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("pdk_manager_toggle"))
    composeTestRule.onNodeWithTag("pdk_manager_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("pdk_download_button"))
    composeTestRule.onNodeWithTag("pdk_url_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pdk_download_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pdk_import_local_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("remote_worker_ping_button"))
    composeTestRule.onNodeWithTag("remote_worker_ping_button").performClick()
    assertTrue(pingedWorker)

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("bit_blast_chip"))
    composeTestRule.onNodeWithTag("bit_blast_chip").performClick()
    assertTrue(toggledBitBlast)
  }

  @Test
  fun projectsScreen_compareDialogRendersRunDiffAttributionAndPercentDeltas() {
    val runA = SynthesisRun(
      id = "r1",
      designId = "d1",
      designName = "alu_core",
      targetLibrary = TargetLibrary.SKY130_130NM.id,
      optimizationGoal = OptimizationGoal.BALANCED.id,
      clockPeriodNs = 10.0,
      fmaxMhz = 120.0,
      wnsNs = 1.66,
      areaUm2 = 420.0,
      cellCount = 60,
      powerMw = 1.8,
      engineId = SynthesisEngineProfile.SILICON_FLOW.id,
    )
    val runB = SynthesisRun(
      id = "r2",
      designId = "d1",
      designName = "alu_core",
      targetLibrary = TargetLibrary.ASAP7_7NM.id,
      optimizationGoal = OptimizationGoal.MIN_DELAY.id,
      clockPeriodNs = 2.0,
      fmaxMhz = 640.0,
      wnsNs = 0.43,
      areaUm2 = 68.0,
      cellCount = 54,
      powerMw = 0.42,
      engineId = SynthesisEngineProfile.YOSYS_ABC.id,
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ProjectsScreen(
          workbench = WorkbenchUiState(),
          designsState = UiState.Success(listOf(RtlDesign(id = "d1", name = "alu_core"))),
          runsState = UiState.Success(listOf(runA, runB)),
          onCreateProject = { _, _, _ -> },
          onImportProjectFile = { _, _ -> },
          onOpenDesign = {},
          onDuplicateDesign = {},
          onDeleteDesign = {},
          onOpenPreset = {},
          onOpenActiveEditor = {},
          onOpenRunReport = {},
          onDeleteRun = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("compare_projects_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("run_diff_attribution_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Run Diff & PPA Delta").assertIsDisplayed()
  }

  @Test
  fun reportsScreen_criticalPathPillJumpsToSourceLineAndZipExportButtonsExist() {
    var jumpedLine = -1
    val rtl = """
      module path_mod(input clk, input a, input b, output reg q);
        wire w1 = a & b;
        always @(posedge clk) q <= ~w1;
      endmodule
    """.trimIndent()
    val res = RtlSynthesisEngine.synthesize(rtl, TargetLibrary.ASAP7_7NM, OptimizationGoal.BALANCED, 2.0) as SynthesisResult.Success

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsScreen(
          state = WorkbenchUiState(
            designName = "path_mod",
            verilogCode = rtl,
            synthesisResult = res,
          ),
          settings = AppSettings(),
          onSaveSnapshot = {},
          onSelectNode = {},
          onSetCycleCount = {},
          onCycleStimulusPattern = {},
          onStepCustomCellValue = { _, _, _, _ -> },
          onRunSimulation = {},
          onRunTestbenchSimulation = {},
          onToggleWaveformSignal = {},
          onResetStimulus = {},
          onNotify = {},
          onJumpToRtlLine = { jumpedLine = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_summary_list")
      .performScrollToNode(hasTestTag("export_zip_bundle_button"))
    composeTestRule.onNodeWithTag("export_zip_bundle_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("share_zip_bundle_button").assertIsDisplayed()
    if (res.report.criticalPath.any { it.sourceLine > 0 }) {
      composeTestRule.onNodeWithTag("reports_summary_list")
        .performScrollToNode(hasTestTag("crit_path_jump_0"))
      composeTestRule.onNodeWithTag("crit_path_jump_0").performClick()
      assertTrue("Expected jump callback to positive line, got $jumpedLine", jumpedLine > 0)
    }
  }
}
