package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.AutoSaveStatus
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkbenchScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun workbenchScreen_writeRtlAndTapRun_updatesParsedAndSynthesisOutput() {
    var ranSynthesis = false

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var state by remember {
          val initialCode = "module init_mod(input a, output y); assign y = a; endmodule"
          val parsed = VerilogHdlParser.parse(initialCode) as VerilogParseResult.Success
          mutableStateOf(
            WorkbenchUiState(
              designName = "init_mod",
              verilogCode = initialCode,
              parsedDesign = parsed.design,
            )
          )
        }

        WorkbenchScreen(
          state = state,
          onCodeChange = { newCode ->
            val parsed = VerilogHdlParser.parse(newCode)
            state = state.copy(
              verilogCode = newCode,
              parsedDesign = (parsed as? VerilogParseResult.Success)?.design,
              parseError = parsed as? VerilogParseResult.Error,
            )
          },
          onMetaChange = { _, _ -> },
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onSelectTopModule = {},
          onRunSynthesis = {
            ranSynthesis = true
            val res = RtlSynthesisEngine.synthesize(
              verilogSource = state.verilogCode,
              library = TargetLibrary.ASAP7_7NM,
              goal = OptimizationGoal.BALANCED,
              clockPeriodNs = 2.0,
            )
            state = state.copy(synthesisResult = res)
          },
          onSaveToCloud = {},
          onLoadPreset = {},
          onNewBlankDesign = {},
          onOpenSchematic = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("verilog_code_editor").assertIsDisplayed()
    composeTestRule.onNodeWithTag("run_synthesis_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Run").assertIsDisplayed()

    val customRtl = """
      module my_and_gate (
        input wire in_a,
        input wire in_b,
        output wire out_y
      );
        assign out_y = in_a & in_b;
      endmodule
    """.trimIndent()

    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(customRtl)
    composeTestRule.onNodeWithTag("run_synthesis_button").performClick()
    composeTestRule.waitForIdle()

    assertTrue(ranSynthesis)
    composeTestRule.onNodeWithTag("view_schematic_cta").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun workbenchScreen_invalidVerilog_highlightsLineAndDisplaysErrorBelowEditor() {
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var state by remember {
          val validCode = "module ok(input a, output y); assign y = a; endmodule"
          val parsed = VerilogHdlParser.parse(validCode) as VerilogParseResult.Success
          mutableStateOf(
            WorkbenchUiState(
              designName = "ok",
              verilogCode = validCode,
              parsedDesign = parsed.design,
            )
          )
        }

        WorkbenchScreen(
          state = state,
          onCodeChange = { newCode ->
            val parsed = VerilogHdlParser.parse(newCode)
            state = state.copy(
              verilogCode = newCode,
              parsedDesign = (parsed as? VerilogParseResult.Success)?.design,
              parseError = parsed as? VerilogParseResult.Error,
            )
          },
          onMetaChange = { _, _ -> },
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onSelectTopModule = {},
          onRunSynthesis = {},
          onSaveToCloud = {},
          onLoadPreset = {},
          onNewBlankDesign = {},
          onOpenSchematic = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()

    // Introduce a syntax error (missing semicolon before endmodule on line 3)
    val invalidRtl = "module ok(input a, output y);\n  assign y = a\nendmodule"
    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(invalidRtl)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("editor_error_gutter_line_3").assertExists()
    composeTestRule.onNodeWithTag("editor_error_banner").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Syntax Error · Line 3, Col 1").assertIsDisplayed()

    // Fix the syntax error -> error banner disappears immediately
    val fixedRtl = "module ok(input a, output y);\n  assign y = a;\nendmodule"
    composeTestRule.onNodeWithTag("verilog_code_editor").performTextReplacement(fixedRtl)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("editor_error_banner").assertDoesNotExist()
  }

  @Test
  fun workbenchScreen_opensFilesMenuAndAppliesSdcConstraints() {
    var appliedSdc = ""

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        val code = "module ok(input clk, input a, output y); assign y = a; endmodule"
        val parsed = VerilogHdlParser.parse(code) as VerilogParseResult.Success
        val state = WorkbenchUiState(
          designName = "ok",
          verilogCode = code,
          sdcText = "create_clock -name clk -period 2.00 [get_ports clk]",
          parsedDesign = parsed.design,
          isOfflineMode = true,
        )

        WorkbenchScreen(
          state = state,
          onCodeChange = {},
          onMetaChange = { _, _ -> },
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onSelectTopModule = {},
          onRunSynthesis = {},
          onSaveToCloud = {},
          onLoadPreset = {},
          onNewBlankDesign = {},
          onOpenSchematic = {},
          onUpdateSdc = { appliedSdc = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("workbench_files_menu_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("menu_import_file").assertIsDisplayed()
    composeTestRule.onNodeWithTag("menu_export_rtl").assertIsDisplayed()
    composeTestRule.onNodeWithTag("menu_export_sdc").assertIsDisplayed()

    // Dismiss dropdown menu by clicking outside or tapping menu item then opening constraints sheet
    composeTestRule.onNodeWithTag("open_constraints_sheet_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("open_constraints_sheet_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("sdc_constraints_editor").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("sdc_constraints_editor").performTextReplacement(
      "create_clock -name clk -period 1.50 [get_ports clk]\nset_input_delay -clock clk 0.25 [get_ports a]"
    )
    composeTestRule.onNodeWithTag("apply_sdc_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertTrue(appliedSdc.contains("-period 1.50"))
    assertTrue(appliedSdc.contains("set_input_delay"))
  }

  @Test
  fun workbenchScreen_displaysCloudAutoSaveStatusLabel() {
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        val code = "module ok(input a, output y); assign y = a; endmodule"
        val parsed = VerilogHdlParser.parse(code) as VerilogParseResult.Success
        WorkbenchScreen(
          state = WorkbenchUiState(
            designName = "ok",
            verilogCode = code,
            parsedDesign = parsed.design,
            autoSaveStatus = AutoSaveStatus.SAVED,
            lastAutoSavedLabel = "14:22",
          ),
          onCodeChange = {},
          onMetaChange = { _, _ -> },
          onSelectLibrary = {},
          onSelectGoal = {},
          onClockPeriodChange = {},
          onSelectTopModule = {},
          onRunSynthesis = {},
          onSaveToCloud = {},
          onLoadPreset = {},
          onNewBlankDesign = {},
          onOpenSchematic = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("autosave_status_label").assertIsDisplayed()
    composeTestRule.onNodeWithText("Auto-saved 14:22").assertIsDisplayed()
  }
}
