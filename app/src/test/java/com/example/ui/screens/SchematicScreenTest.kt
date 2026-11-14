package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.theme.RtlSynthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h892dp")
class SchematicScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun schematicScreen_rendersFullWidthCanvasWithoutGatesSidebarAndInspectsCriticalPath() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        SchematicScreen(
          synthesisResult = result,
          selectedNodeId = selectedNodeId,
          onSelectNode = { selectedNodeId = it },
          onNotifyCopied = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_summary_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()

    // Gates palette and manual gate-editing affordances must be removed
    composeTestRule.onNodeWithTag("logic_library_sidebar").assertDoesNotExist()
    composeTestRule.onNodeWithTag("logic_library_collapsed_rail").assertDoesNotExist()
    composeTestRule.onNodeWithTag("library_primitive_AND2").assertDoesNotExist()

    // Tap the Critical Path chip to inspect a critical gate
    composeTestRule.onNodeWithTag("focus_critical_path_chip").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("node_inspector_card").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Power").assertCountEquals(2)
    composeTestRule.onNodeWithText("CRITICAL").assertIsDisplayed()
    composeTestRule.onNodeWithTag("wire_from_selected_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("delete_node_button").assertDoesNotExist()

    // Clear selection cleanly
    composeTestRule.onNodeWithTag("clear_node_selection_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("node_inspector_card").assertDoesNotExist()

    // Switch to structural Netlist tab and back to Diagram
    composeTestRule.onNodeWithTag("netlist_mode_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Synthesized Gate-Level Verilog").assertIsDisplayed()

    composeTestRule.onNodeWithTag("schematic_mode_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
  }

  @Test
  fun schematicScreen_emptyStateDirectsToRtlSynthesisWithoutManualBlankButton() {
    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SchematicScreen(
          synthesisResult = null,
          selectedNodeId = null,
          onSelectNode = {},
          onNotifyCopied = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("empty_schematic_state").assertIsDisplayed()
    composeTestRule.onNodeWithText("No Valid Netlist Yet").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_start_blank_schematic_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("logic_library_sidebar").assertDoesNotExist()
  }
}
