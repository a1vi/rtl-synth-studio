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
import androidx.compose.ui.test.performTextReplacement
import com.example.data.model.AppSettings
import com.example.data.model.RtlDesign
import com.example.data.model.RtlLanguage
import com.example.data.model.TargetLibrary
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkbenchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h892dp")
class ProjectsAndSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun projectsScreen_createsNewProjectWithLanguageAndLibrary() {
    var createdName = ""
    var createdLang = RtlLanguage.VERILOG
    var createdLib = TargetLibrary.ASAP7_7NM

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        ProjectsScreen(
          workbench = WorkbenchUiState(),
          designsState = UiState.Success(
            listOf(
              RtlDesign(
                id = "d1",
                name = "saved_fifo",
                verilogCode = "module saved_fifo(input clk, output y); assign y = clk; endmodule",
                lastCellCount = 12,
                lastWnsNs = 0.45,
                lastAreaUm2 = 8.4,
              )
            )
          ),
          runsState = UiState.Success(emptyList()),
          onCreateProject = { name, lang, lib ->
            createdName = name
            createdLang = lang
            createdLib = lib
          },
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
    composeTestRule.onNodeWithTag("create_new_project_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("new_project_name_input").performTextReplacement("uart_tx_vhdl")
    composeTestRule.onNodeWithTag("create_lang_VHDL").performClick()
    composeTestRule.onNodeWithTag("create_lib_GF180MCU_180NM").performClick()
    composeTestRule.onNodeWithTag("confirm_create_project_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals("uart_tx_vhdl", createdName)
    assertEquals(RtlLanguage.VHDL, createdLang)
    assertEquals(TargetLibrary.GF180MCU_180NM, createdLib)
  }

  @Test
  fun settingsScreen_togglesDarkThemeSwitch() {
    var darkMode = true

    composeTestRule.setContent {
      RtlSynthTheme(darkTheme = true) {
        var settings by remember { mutableStateOf(AppSettings(darkTheme = true)) }
        SettingsScreen(
          settings = settings,
          savedProjectCount = 2,
          savedRunCount = 4,
          storageKb = 12.5,
          onUpdateSettings = { transform ->
            settings = transform(settings)
            darkMode = settings.darkTheme
          },
          onClearSynthesisCache = {},
          onResetWorkspace = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("setting_dark_theme_switch").performClick()
    composeTestRule.waitForIdle()
    assertFalse(darkMode)
  }
}
