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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import com.example.MainWorkspaceScreen
import com.example.SynthDestination
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.RtlSynthViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h892dp")
class EdaSuiteUpgradeTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun dashboardScreen_showsKpisAndCreatesProject() {
    var createdName = ""
    val design = RtlDesign(
      id = "d1",
      name = "spi_master",
      lastCellCount = 24,
      lastWnsNs = 0.65,
      lastAreaUm2 = 18.2,
    )
    val run = SynthesisRun(
      id = "r1",
      designId = "d1",
      designName = "spi_master",
      targetLibrary = TargetLibrary.ASAP7_7NM.id,
      optimizationGoal = OptimizationGoal.BALANCED.id,
      clockPeriodNs = 2.0,
      fmaxMhz = 740.0,
      wnsNs = 0.65,
      areaUm2 = 18.2,
      cellCount = 24,
      powerMw = 0.41,
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        DashboardScreen(
          workbench = WorkbenchUiState(designName = "spi_master"),
          designsState = UiState.Success(listOf(design)),
          runsState = UiState.Success(listOf(run)),
          onCreateProject = { name, _, _ -> createdName = name },
          onOpenProjects = {},
          onImportProjectFile = { _, _ -> },
          onOpenDesign = {},
          onOpenPreset = {},
          onOpenEditor = {},
          onRunSynthesis = {},
          onOpenSchematic = {},
          onOpenAnalysis = {},
          onOpenReports = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_kpi_total_projects").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_kpi_total_runs").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_new_project_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_create_name_input").performTextReplacement("uart_rx")
    composeTestRule.onNodeWithTag("dashboard_confirm_create_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals("uart_rx", createdName)
  }

  @Test
  fun projectsScreen_supportsRenameDeleteConfirmAndFileBrowser() {
    var renamedTo = ""
    var deletedId = ""
    var openedFileId = ""
    val design = RtlDesign(
      id = "d_ren",
      name = "alu_8bit",
      verilogCode = "module alu_8bit(input a, output y); assign y = a; endmodule",
    )

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ProjectsScreen(
          workbench = WorkbenchUiState(
            designName = "alu_8bit",
            files = listOf(
              RtlFileEntry("file_rtl_top", "alu_8bit.v", RtlLanguage.VERILOG, design.verilogCode),
              RtlFileEntry("file_sdc", "constraints.sdc", RtlLanguage.SDC, "create_clock -period 2.0"),
            ),
            activeFileId = "file_rtl_top",
          ),
          designsState = UiState.Success(listOf(design)),
          runsState = UiState.Success(emptyList()),
          onCreateProject = { _, _, _ -> },
          onImportProjectFile = { _, _ -> },
          onOpenDesign = {},
          onDuplicateDesign = {},
          onDeleteDesign = { deletedId = it },
          onOpenPreset = {},
          onOpenActiveEditor = {},
          onOpenRunReport = {},
          onDeleteRun = {},
          onNotify = {},
          onRenameDesign = { _, newName -> renamedTo = newName },
          onOpenFileInEditor = { openedFileId = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("projects_file_browser_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("project_file_row_file_sdc").performClick()
    assertEquals("file_sdc", openedFileId)

    composeTestRule.onNodeWithTag("project_rename_d_ren").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("rename_project_input").performTextReplacement("alu_16bit")
    composeTestRule.onNodeWithTag("confirm_rename_project_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("alu_16bit", renamedTo)

    composeTestRule.onNodeWithTag("project_delete_d_ren").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("delete_project_confirm_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("d_ren", deletedId)
  }

  @Test
  fun editorScreen_supportsUndoRedoFoldStripAndModuleJump() {
    val initialCode = """
      module demo_blk(input wire clk, output reg q);
        always @(posedge clk) begin
          q <= ~q;
        end
      endmodule
    """.trimIndent()

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        val parsed = (VerilogHdlParser.parse(initialCode) as VerilogParseResult.Success).design
        var state by remember {
          mutableStateOf(
            WorkbenchUiState(
              designName = "demo_blk",
              verilogCode = initialCode,
              files = listOf(RtlFileEntry("file_rtl_top", "demo_blk.v", RtlLanguage.VERILOG, initialCode)),
              openTabIds = listOf("file_rtl_top"),
              activeFileId = "file_rtl_top",
              parsedDesign = parsed,
            )
          )
        }
        EditorScreen(
          state = state,
          settings = AppSettings(),
          onFileContentChange = { updated ->
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
    composeTestRule.onNodeWithTag("editor_action_ribbon").assertIsDisplayed()
    composeTestRule.onNodeWithTag("editor_jump_module_demo_blk").assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("editor_port_chip_clk").assertIsDisplayed().performClick()

    composeTestRule.onNodeWithTag("editor_fold_toggle_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_fold_regions_strip").assertIsDisplayed()

    composeTestRule.onNodeWithTag("symbol_key_;").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_undo_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("editor_redo_button").performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun analysisScreen_displaysTimingAreaAndPowerWithPreCtsTransparency() {
    val rtl = """
      module acc(input clk, input a, output reg y);
        wire w = ~a;
        always @(posedge clk) y <= w;
      endmodule
    """.trimIndent()
    val res = RtlSynthesisEngine.synthesize(rtl, TargetLibrary.ASAP7_7NM, OptimizationGoal.BALANCED, 2.0) as SynthesisResult.Success

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        AnalysisScreen(
          state = WorkbenchUiState(
            designName = "acc",
            verilogCode = rtl,
            synthesisResult = res,
            physicalLayout = null,
          ),
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
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_timing_summary_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("N/A (pre-CTS)").assertIsDisplayed()

    composeTestRule.onNodeWithTag("analysis_tab_area").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_area_summary_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("PnR not run (synthesized cell area only)").assertIsDisplayed()

    composeTestRule.onNodeWithTag("analysis_tab_power").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_power_methodology_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("VECTORLESS EST").assertIsDisplayed()
  }

  @Test
  fun reportsLogsScreen_filtersLogsAndShowsFormattedReports() {
    val rtl = "module inv_m(input a, output y); assign y = ~a; endmodule"
    val res = RtlSynthesisEngine.synthesize(rtl, TargetLibrary.ASAP7_7NM, OptimizationGoal.BALANCED, 2.0) as SynthesisResult.Success

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ReportsLogsScreen(
          state = WorkbenchUiState(
            designName = "inv_m",
            verilogCode = rtl,
            synthesisResult = res,
            flowLogLines = listOf("[SYNTH] Elaborated inv_m", "[WARN] Minor unconstrained input delay"),
          ),
          settings = AppSettings(),
          onSaveSnapshot = {},
          onJumpToRtlLine = {},
          onOpenSynthesis = {},
          onNotify = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_log_console").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reports_log_filter_WARN").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("[WARN] Minor unconstrained input delay").assertIsDisplayed()

    composeTestRule.onNodeWithTag("reports_subtab_formatted").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("report_kind_TIMING").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_formatted_text_list").assertIsDisplayed()

    composeTestRule.onNodeWithTag("reports_subtab_export").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_export_zip_button").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w840dp-h900dp")
  fun mainWorkspace_wideTabletShowsAll8NavigationRailDestinations() {
    val tempDir = Files.createTempDirectory("eda_wide_nav").toFile()
    val vmStore = ViewModelStore()
    try {
      val vm = RtlSynthViewModel(localStore = LocalWorkspaceStore(tempDir))
      vmStore.put("vm", vm)
      composeTestRule.setContent {
        RtlSynthTheme(darkTheme = true) {
          MainWorkspaceScreen(viewModel = vm)
        }
      }
      composeTestRule.waitForIdle()
      SynthDestination.entries.forEach { dest ->
        composeTestRule.onNodeWithTag(dest.testTag).assertExists()
      }
      composeTestRule.onNodeWithTag("nav_schematic").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("nav_analysis").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("analysis_screen").assertIsDisplayed()
    } finally {
      vmStore.clear()
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun mainWorkspace_compactPhoneOpensDrawerWithAll8Destinations() {
    val tempDir = Files.createTempDirectory("eda_compact_nav").toFile()
    val vmStore = ViewModelStore()
    try {
      val vm = RtlSynthViewModel(localStore = LocalWorkspaceStore(tempDir))
      vmStore.put("vm", vm)
      composeTestRule.setContent {
        RtlSynthTheme(darkTheme = true) {
          MainWorkspaceScreen(viewModel = vm)
        }
      }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("workspace_drawer_button").assertIsDisplayed().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("drawer_nav_projects").assertIsDisplayed().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("create_new_project_button").assertIsDisplayed()
    } finally {
      vmStore.clear()
      tempDir.deleteRecursively()
    }
  }
}
