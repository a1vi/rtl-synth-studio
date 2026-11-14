package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.AutoSaveStatus
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class EditorAndSynthesisScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun editorScreen_editsRtlHighlightsSyntaxErrorAndShowsAutoSaveStatus() {
    var ranSynthesis = false
    val initialCode = "module ok(input a, output y); assign y = a; endmodule"

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        val parsed = VerilogHdlParser.parse(initialCode) as VerilogParseResult.Success
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "ok",
              verilogCode = initialCode,
              files = listOf(RtlFileEntry("file_rtl_top", "ok.v", RtlLanguage.VERILOG, initialCode)),
              openTabIds = listOf("file_rtl_top"),
              activeFileId = "file_rtl_top",
              parsedDesign = parsed.design,
              autoSaveStatus = AutoSaveStatus.SAVED,
              lastAutoSavedLabel = "14:22",
            )
          )
        }

        EditorScreen(
          state = state,
          settings = AppSettings(),
          onFileContentChange = { newCode ->
            val nextParse = VerilogHdlParser.parse(newCode)
            state = state.copy(
              verilogCode = newCode,
              files = state.files.map { it.copy(content = newCode) },
              parsedDesign = (nextParse as? VerilogParseResult.Success)?.design,
              parseError = nextParse as? VerilogParseResult.Error,
            )
          },
          onSelectFileTab = {},
          onCloseFileTab = {},
          onAddFile = { _, _ -> },
          onDeleteFile = {},
          onRunSynthesis = {
            ranSynthesis = true
            state = state.copy(
              synthesisResult = RtlSynthesisEngine.synthesize(
                verilogSource = state.verilogCode,
                library = TargetLibrary.ASAP7_7NM,
                goal = OptimizationGoal.BALANCED,
                clockPeriodNs = 2.0,
              )
            )
          },
          onSaveProject = {},
          onOpenReports = {},
          onImportFile = { _, _ -> },
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("autosave_status_label").assertIsDisplayed()
    composeTestRule.onNodeWithText("Auto-saved 14:22").assertIsDisplayed()
    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()

    // Inject syntax error -> line 3 gutter and diagnostic banner appear
    val invalidRtl = "module ok(input a, output y);\n  assign y = a\nendmodule"
    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(invalidRtl)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("editor_error_gutter_line_3").assertExists()
    composeTestRule.onNodeWithTag("editor_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithText("Syntax Error · Line 3, Col 1").assertIsDisplayed()

    // Fix syntax error -> error clears and synthesis runs
    val fixedRtl = "module ok(input a, output y);\n  assign y = a;\nendmodule"
    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(fixedRtl)
    composeTestRule.onNodeWithTag("run_synthesis_button").performClick()
    composeTestRule.waitForIdle()

    assertTrue(ranSynthesis)
    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()
    composeTestRule.onNodeWithTag("editor_view_reports_button").assertIsDisplayed()

    // Quick-symbol bar renders and inserts HDL tokens
    composeTestRule.onNodeWithTag("editor_symbol_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("symbol_key_;").performClick()
    composeTestRule.waitForIdle()

    // Inject missing ';' -> suggested fix & Apply Fix button appear and fix the code
    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(invalidRtl)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_error_suggestion_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("editor_apply_fix_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()

    // Clear button clears the editor and allows Undo Clear restoration
    composeTestRule.onNodeWithTag("editor_clear_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_undo_clear_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()
  }

  @Test
  fun editorScreen_findAndReplaceUpdatesActiveBuffer() {
    var latestCode = "module top(input a, output y); assign y = a; endmodule"

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "top",
              verilogCode = latestCode,
              files = listOf(RtlFileEntry("file_rtl_top", "top.v", RtlLanguage.VERILOG, latestCode)),
              openTabIds = listOf("file_rtl_top"),
              activeFileId = "file_rtl_top",
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
    composeTestRule.onNodeWithTag("editor_find_toggle_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_find_input").performTextReplacement("output y")
    composeTestRule.onNodeWithTag("editor_replace_input").performTextReplacement("output out_y")
    composeTestRule.onNodeWithTag("editor_replace_all_button").performClick()
    composeTestRule.waitForIdle()

    assertTrue(latestCode.contains("output out_y"))
  }

  @Test
  fun synthesisScreen_selectsEngineLibraryAndAppliesSdc() {
    var appliedSdc = ""
    var selectedLib = TargetLibrary.ASAP7_7NM
    var selectedEngine = SynthesisEngineProfile.SILICON_FLOW

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        val code = "module ok(input clk, input a, output y); assign y = a; endmodule"
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "ok",
              verilogCode = code,
              sdcText = "create_clock -name clk -period 2.00 [get_ports clk]",
              files = listOf(RtlFileEntry("file_rtl_top", "ok.v", RtlLanguage.VERILOG, code)),
              targetLibrary = selectedLib,
              synthesisEngine = selectedEngine,
            )
          )
        }

        SynthesisScreen(
          state = state,
          onSelectTopModule = {},
          onSelectEngine = {
            selectedEngine = it
            state = state.copy(synthesisEngine = it)
          },
          onSelectLibrary = {
            selectedLib = it
            state = state.copy(targetLibrary = it)
          },
          onSelectGoal = {},
          onClockPeriodChange = {},
          onUpdateSdc = { appliedSdc = it },
          onResetSdc = {},
          onRunSynthesis = {},
          onJumpToEditor = {},
          onOpenReports = {},
          onNotify = {},
          onDesignNameChange = { state = state.copy(designName = it) },
          onCoreUtilizationChange = { state = state.copy(coreUtilizationPercent = it) },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synth_run_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("flow_stepper_card"))
    composeTestRule.onNodeWithTag("flow_stepper_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("synth_design_name_input"))
    composeTestRule.onNodeWithTag("synth_design_name_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("engine_chip_YOSYS_ABC"))
    composeTestRule.onNodeWithTag("engine_chip_YOSYS_ABC").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("library_chip_SKY130_130NM"))
    composeTestRule.onNodeWithTag("library_chip_SKY130_130NM").performScrollTo().performClick()
    assertEquals(SynthesisEngineProfile.YOSYS_ABC, selectedEngine)
    assertEquals(TargetLibrary.SKY130_130NM, selectedLib)

    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("utilization_slider"))
    composeTestRule.onNodeWithTag("utilization_slider").assertIsDisplayed()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasText("Advanced SDC Timing Constraints"))
    composeTestRule.onNodeWithText("Advanced SDC Timing Constraints").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("sdc_constraints_editor"))
    composeTestRule.onNodeWithTag("sdc_constraints_editor")
      .performTextReplacement("create_clock -name clk -period 1.50 [get_ports clk]")
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("apply_sdc_button"))
    composeTestRule.onNodeWithTag("apply_sdc_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(appliedSdc.contains("-period 1.50"))
  }

  @Test
  fun synthesisScreen_displaysKLayoutSnapshotCardAfterRun() {
    var flowEvent: com.example.domain.synth.RtlToGdsStageEvent? = null
    kotlinx.coroutines.runBlocking {
      flowEvent = com.example.domain.synth.RtlToGdsFlowEngine.runFlow(
        rtlSource = "module inv_chain(input wire a, output wire y); assign y = ~a; endmodule",
        designName = "inv_chain",
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
    val completed = flowEvent!!
    var openedReports = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SynthesisScreen(
          state = WorkbenchUiState(
            designName = "inv_chain",
            synthesisResult = completed.synthesisResult,
            flowStages = completed.stageStates,
            physicalLayout = completed.layoutSnapshot,
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
          onOpenReports = { openedReports = true },
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("klayout_snapshot_card"))
    composeTestRule.onNodeWithTag("klayout_snapshot_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("klayout_snapshot_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("klayout_open_viewer_button"))
    composeTestRule.onNodeWithTag("klayout_open_viewer_button").performClick()
    assertTrue(openedReports)
  }
}
