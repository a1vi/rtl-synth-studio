package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.example.data.model.AppSettings
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.domain.synth.RtlDesignRuleChecker
import com.example.domain.synth.RtlDrcScanOutcome
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
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
class RtlDesignRuleCheckerUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val buggyRtl = """
    module buggy_drc(
      input wire clk,
      input wire en,
      input wire d,
      output reg q,
      output reg latched_y
    );
      wire a, b;
      assign a = b & en;
      assign b = ~a;
      always @(posedge clk) begin
        q <= d;
      end
      always @(*) begin
        if (en) latched_y = a;
      end
    endmodule
  """.trimIndent()

  @Test
  fun editorScreen_displaysDrcPillOpensDiagnosticsDrawerAndJumpsToLine() {
    val parsed = (VerilogHdlParser.parse(buggyRtl) as VerilogParseResult.Success).design
    val drcReport = (RtlDesignRuleChecker.checkSource(buggyRtl) as RtlDrcScanOutcome.Ready).report
    var rescanned = false
    val notifications = mutableListOf<String>()

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        EditorScreen(
          state = WorkbenchUiState(
            designName = "buggy_drc",
            verilogCode = buggyRtl,
            files = listOf(RtlFileEntry("f1", "buggy_drc.v", RtlLanguage.VERILOG, buggyRtl)),
            openTabIds = listOf("f1"),
            activeFileId = "f1",
            parsedDesign = parsed,
            rtlDrcReport = drcReport,
          ),
          settings = AppSettings(),
          onFileContentChange = {},
          onSelectFileTab = {},
          onCloseFileTab = {},
          onAddFile = { _, _ -> },
          onDeleteFile = {},
          onRunSynthesis = {},
          onSaveProject = {},
          onOpenReports = {},
          onImportFile = { _, _ -> },
          onNotify = { notifications += it },
          onRunRtlDrc = { rescanned = true },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_drc_pill").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("editor_drc_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("editor_drc_rescan_button").performClick()
    assertTrue("Clicking Re-Scan should trigger onRunRtlDrc", rescanned)

    composeTestRule.onNodeWithTag("editor_drc_item_0").assertIsDisplayed().performClick()
    assertTrue(
      "Clicking DRC item should jump cursor and notify line",
      notifications.any { it.startsWith("Jumped cursor to line") },
    )
  }

  @Test
  fun synthesisScreen_displaysPreSynthDrcCardFiltersAndLineJump() {
    val parsed = (VerilogHdlParser.parse(buggyRtl) as VerilogParseResult.Success).design
    val drcReport = (RtlDesignRuleChecker.checkSource(buggyRtl) as RtlDrcScanOutcome.Ready).report
    var drcTriggered = false
    var jumpedLine = -1

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SynthesisScreen(
          state = WorkbenchUiState(
            designName = "buggy_drc",
            verilogCode = buggyRtl,
            files = listOf(RtlFileEntry("f1", "buggy_drc.v", RtlLanguage.VERILOG, buggyRtl)),
            openTabIds = listOf("f1"),
            activeFileId = "f1",
            parsedDesign = parsed,
            rtlDrcReport = drcReport,
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
          onRunRtlDrc = { drcTriggered = true },
          onJumpToDrcLine = { jumpedLine = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("synthesis_lazy_list")
      .performScrollToNode(hasTestTag("synth_rtl_drc_card"))

    composeTestRule.onNodeWithTag("synth_rtl_drc_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drc_metric_errors").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drc_metric_warnings").assertIsDisplayed()

    composeTestRule.onNodeWithTag("synth_run_drc_button").performClick()
    assertTrue("Scan DRC button should invoke callback", drcTriggered)

    composeTestRule.onNodeWithTag("drc_filter_ERRORS").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drc_violation_0").performClick()
    assertTrue("Clicking DRC violation should invoke onJumpToDrcLine", jumpedLine > 0)

    composeTestRule.onNodeWithTag("drc_filter_WARNINGS").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drc_violation_0").assertIsDisplayed()
    assertEquals(drcReport.violations.first { it.severity.label == "ERROR" }.line, jumpedLine)
  }
}
