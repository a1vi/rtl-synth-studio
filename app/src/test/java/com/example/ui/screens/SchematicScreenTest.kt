package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.theme.RtlSynthTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
  fun schematicScreen_summaryBarFocusesCriticalPathAndOpensInspector() {
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
    composeTestRule.onNodeWithTag("logic_library_sidebar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()

    // Tap the Critical Path chip to inspect a critical gate
    composeTestRule.onNodeWithTag("focus_critical_path_chip").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("node_inspector_card").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Power").assertCountEquals(2)
    composeTestRule.onNodeWithText("CRITICAL").assertIsDisplayed()

    // Clear selection cleanly
    composeTestRule.onNodeWithTag("clear_node_selection_button").performClick()
    composeTestRule.waitForIdle()

    // Switch to structural Netlist tab and back to Diagram
    composeTestRule.onNodeWithTag("netlist_mode_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Synthesized Gate-Level Verilog").assertIsDisplayed()

    composeTestRule.onNodeWithTag("schematic_mode_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
  }

  @Test
  fun schematicScreen_librarySidebarSupportsDragAndDropAndTapToPlacePrimitives() {
    var droppedGateType: GateType? = null
    var droppedWorldPos: Offset? = null
    var activeReport = RtlSynthesisEngine.createBlankManualReport(
      moduleName = "manual_test",
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var reportState by remember { mutableStateOf(activeReport) }
        var manualPositions by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }

        SchematicScreen(
          synthesisResult = SynthesisResult.Success(reportState),
          selectedNodeId = selectedNodeId,
          onSelectNode = { selectedNodeId = it },
          onNotifyCopied = {},
          manualPositions = manualPositions,
          isManualSchematic = true,
          onAddPrimitive = { gateType, worldPos ->
            droppedGateType = gateType
            droppedWorldPos = worldPos
            val idx = reportState.nodes.size + 1
            val newNode = GateNode(
              id = "man_$idx",
              cellName = gateType.shortLabel,
              gateType = gateType,
              instanceName = "u_${gateType.shortLabel.lowercase()}_$idx",
              inputs = listOf("in_a", "in_b"),
              outputNet = "w_$idx",
              bitWidth = 1,
              delayNs = 0.0,
              areaUm2 = 0.0,
              powerUw = 0.0,
            )
            if (worldPos != null) {
              manualPositions = manualPositions + (newNode.id to worldPos)
            }
            reportState = RtlSynthesisEngine.recomputeReportFromNodes(
              moduleName = reportState.moduleName,
              ports = reportState.ports,
              rawNodes = reportState.nodes + newNode,
              library = TargetLibrary.ASAP7_7NM,
              goal = OptimizationGoal.BALANCED,
              clockPeriodNs = 2.0,
            )
            activeReport = reportState
            selectedNodeId = newNode.id
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    // Core primitives: AND, OR, NOT, FLIP-FLOP are all visible in the sidebar
    composeTestRule.onNodeWithTag("library_primitive_AND2").assertIsDisplayed()
    composeTestRule.onNodeWithTag("library_primitive_OR2").assertIsDisplayed()
    composeTestRule.onNodeWithTag("library_primitive_INV").assertIsDisplayed()
    composeTestRule.onNodeWithTag("library_primitive_DFF").assertIsDisplayed()

    // Drag AND gate horizontally from the left library sidebar onto the schematic canvas
    composeTestRule.onNodeWithTag("library_primitive_AND2").performTouchInput {
      swipe(
        start = center,
        end = center + Offset(220f, 40f),
        durationMillis = 300,
      )
    }
    composeTestRule.waitForIdle()

    assertEquals(GateType.AND2, droppedGateType)
    assertNotNull(droppedWorldPos)
    assertEquals(4, activeReport.nodes.size)
    composeTestRule.onNodeWithTag("node_inspector_card").assertIsDisplayed()

    // Tap FLIP-FLOP primitive to place as well
    composeTestRule.onNodeWithTag("logic_library_list")
      .performScrollToNode(hasTestTag("library_primitive_DFF"))
    composeTestRule.onNodeWithTag("library_primitive_DFF").performClick()
    composeTestRule.waitForIdle()
    assertEquals(GateType.DFF, droppedGateType)
    assertEquals(5, activeReport.nodes.size)

    // Toggle sidebar collapse and expand
    composeTestRule.onNodeWithTag("toggle_library_sidebar_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("logic_library_collapsed_rail").assertIsDisplayed()
    composeTestRule.onNodeWithTag("toggle_library_sidebar_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("logic_library_sidebar").assertIsDisplayed()
  }
}
