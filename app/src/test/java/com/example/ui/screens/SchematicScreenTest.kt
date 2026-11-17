package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.ui.theme.RtlSynthTheme
import org.junit.Assert.assertTrue
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

  @Test
  fun schematicScreen_supportsCadenceToolbarSidebarHierarchyRtlSearchStatusBarAndPngExport() {
    val hierRtl = """
      module leaf_and(input a, input b, output y);
        assign y = a & b;
      endmodule

      module top_hier(input clk, input a, input b, output reg q);
        wire w;
        leaf_and u_leaf (.a(a), .b(b), .y(w));
        always @(posedge clk) q <= w;
      endmodule
    """.trimIndent()

    val parsed = (com.example.domain.synth.VerilogHdlParser.parse(hierRtl) as com.example.domain.synth.VerilogParseResult.Success).design
    val synth = RtlSynthesisEngine.synthesize(
      verilogSource = hierRtl,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        SchematicScreen(
          synthesisResult = synth,
          selectedNodeId = selectedNodeId,
          onSelectNode = { selectedNodeId = it },
          onNotifyCopied = {},
          rtlSource = hierRtl,
          parsedDesign = parsed,
        )
      }
    }

    composeTestRule.waitForIdle()
    // Top EDA toolbar, breadcrumb, and bottom status bar present
    composeTestRule.onNodeWithTag("schematic_toolbar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("schematic_breadcrumb_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("schematic_status_bar").assertIsDisplayed()

    // Toggle Select / Pan tools
    composeTestRule.onNodeWithTag("schematic_tool_pan").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_tool_select").performClick()
    composeTestRule.waitForIdle()

    // Toggle Left Sidebar -> inspect Hierarchy Tree & RTL Source viewer
    composeTestRule.onNodeWithTag("schematic_sidebar_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_left_sidebar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("schematic_hierarchy_tree").assertIsDisplayed()

    composeTestRule.onNodeWithTag("sidebar_tab_rtl").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_rtl_viewer").assertIsDisplayed()

    // Drill down into u_leaf submodule via Hierarchy tab, then Prev/Next history
    composeTestRule.onNodeWithTag("sidebar_tab_hierarchy").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("u_leaf (leaf_and)").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_history_prev").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_history_next").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_hierarchy_up").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Close Left Sidebar and exercise Search bar
    composeTestRule.onNodeWithTag("schematic_sidebar_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_search_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schematic_search_panel").assertIsDisplayed()

    // Export dropdown opens cleanly
    composeTestRule.onNodeWithTag("schematic_export_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("export_schematic_png").assertIsDisplayed()
    composeTestRule.onNodeWithTag("export_schematic_svg").assertIsDisplayed()

    // Validate raster PNG generation via SchematicExporter
    val graph = com.example.domain.synth.StructuralNetlistParser.buildSchematicGraph(synth.report, parsed)
    val routed = com.example.domain.synth.NetlistLayoutEngine.computeRoutedSchematicLayout(graph)
    val png = com.example.ui.components.SchematicExporter.buildPngBytes(routed, showNetLabels = true)
    org.junit.Assert.assertTrue(png.size > 100)
    org.junit.Assert.assertEquals(0x89.toByte(), png[0])
    org.junit.Assert.assertEquals('P'.code.toByte(), png[1])
    org.junit.Assert.assertEquals('N'.code.toByte(), png[2])
    org.junit.Assert.assertEquals('G'.code.toByte(), png[3])
  }

  @Test
  fun schematicScreen_handlesSynthesisErrorGracefullyInStatusBarAndDiagnostics() {
    val errResult = SynthesisResult.Error(
      line = 3,
      message = "Syntax error near endmodule",
      log = listOf("Unexpected token near line 3"),
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        SchematicScreen(
          synthesisResult = errResult,
          selectedNodeId = null,
          onSelectNode = {},
          onNotifyCopied = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("empty_schematic_state").assertIsDisplayed()
    composeTestRule.onNodeWithTag("schematic_status_bar").assertIsDisplayed()
    composeTestRule.onNodeWithText("SYNTH ERROR").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp")
  fun schematicScreen_compactPhoneStaysWithinSectionBoundsWithInspectorAndControls() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val targetNodeId = result.report.nodes.first { !it.gateType.isPort }.id

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Box(modifier = Modifier.size(width = 360.dp, height = 640.dp).testTag("schematic_host_box")) {
          SchematicScreen(
            synthesisResult = result,
            selectedNodeId = targetNodeId,
            onSelectNode = {},
            onNotifyCopied = {},
            highlightedPathNodeIds = setOf(targetNodeId),
            highlightedPathNets = setOf(result.report.nodes.first { !it.gateType.isPort }.outputNet),
          )
        }
      }
    }

    composeTestRule.waitForIdle()

    val hostBounds = composeTestRule.onNodeWithTag("schematic_host_box").getUnclippedBoundsInRoot()
    val toolbarBounds = composeTestRule.onNodeWithTag("schematic_toolbar").getUnclippedBoundsInRoot()
    val modeDiagramBounds = composeTestRule.onNodeWithTag("schematic_mode_button").getUnclippedBoundsInRoot()
    val modeNetlistBounds = composeTestRule.onNodeWithTag("netlist_mode_button").getUnclippedBoundsInRoot()
    val canvasBounds = composeTestRule.onNodeWithTag("netlist_canvas").getUnclippedBoundsInRoot()
    val inspectorBounds = composeTestRule.onNodeWithTag("node_inspector_card").getUnclippedBoundsInRoot()
    val statusBounds = composeTestRule.onNodeWithTag("schematic_status_bar").getUnclippedBoundsInRoot()
    val zoomInBounds = composeTestRule.onNodeWithTag("netlist_zoom_in_button").getUnclippedBoundsInRoot()
    val zoomPillBounds = composeTestRule.onNodeWithTag("netlist_zoom_pill").getUnclippedBoundsInRoot()

    // Toolbar, Diagram/Netlist toggle, Canvas, Inspector, and Status Bar all stay inside host bounds
    assertTrue(toolbarBounds.right <= hostBounds.right + 1.dp)
    assertTrue(modeDiagramBounds.right <= hostBounds.right + 1.dp)
    assertTrue(modeNetlistBounds.right <= hostBounds.right + 1.dp)
    assertTrue(canvasBounds.right <= hostBounds.right + 1.dp)
    assertTrue(canvasBounds.bottom <= hostBounds.bottom + 1.dp)
    assertTrue((canvasBounds.bottom - canvasBounds.top) >= 110.dp)
    assertTrue(inspectorBounds.right <= hostBounds.right + 1.dp)
    assertTrue(statusBounds.bottom <= hostBounds.bottom + 1.dp)
    assertTrue(statusBounds.right <= hostBounds.right + 1.dp)

    // Canvas HUD controls stay inside canvas bounds without overlapping bottom zoom pill
    assertTrue(zoomInBounds.top >= canvasBounds.top - 1.dp)
    assertTrue(zoomInBounds.bottom <= canvasBounds.bottom + 1.dp)
    assertTrue(zoomInBounds.bottom <= zoomPillBounds.top + 1.dp || zoomInBounds.left >= zoomPillBounds.right - 1.dp)
    assertTrue(zoomPillBounds.right <= canvasBounds.right + 1.dp)

    composeTestRule.onNodeWithTag("schematic_status_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("node_inspector_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("netlist_canvas").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w1024dp-h768dp")
  fun schematicScreen_wideDesktopPlacesSidebarCanvasAndInspectorInsideBoundsWithoutOverlap() {
    val result = RtlSynthesisEngine.synthesize(
      verilogSource = PresetRtlDesigns.templates.first().verilogCode,
      library = TargetLibrary.ASAP7_7NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 2.0,
    ) as SynthesisResult.Success

    val targetNodeId = result.report.nodes.first { !it.gateType.isPort }.id

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        Box(modifier = Modifier.size(width = 1000.dp, height = 700.dp).testTag("schematic_wide_host")) {
          SchematicScreen(
            synthesisResult = result,
            selectedNodeId = targetNodeId,
            onSelectNode = {},
            onNotifyCopied = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()

    val hostBounds = composeTestRule.onNodeWithTag("schematic_wide_host").getUnclippedBoundsInRoot()
    val sidebarBounds = composeTestRule.onNodeWithTag("schematic_left_sidebar").getUnclippedBoundsInRoot()
    val canvasBounds = composeTestRule.onNodeWithTag("netlist_canvas").getUnclippedBoundsInRoot()
    val inspectorBounds = composeTestRule.onNodeWithTag("node_inspector_card").getUnclippedBoundsInRoot()
    val statusBounds = composeTestRule.onNodeWithTag("schematic_status_bar").getUnclippedBoundsInRoot()

    assertTrue(sidebarBounds.right <= canvasBounds.left + 1.dp)
    assertTrue(canvasBounds.right <= inspectorBounds.left + 1.dp)
    assertTrue(inspectorBounds.right <= hostBounds.right + 1.dp)
    assertTrue(statusBounds.bottom <= hostBounds.bottom + 1.dp)
  }
}
