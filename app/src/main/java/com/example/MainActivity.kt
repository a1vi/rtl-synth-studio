package com.example

import android.app.Application
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.local.LocalWorkspaceStore
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SynthesisScreen
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.RtlSynthViewModel
import com.example.ui.viewmodel.UiState
import kotlinx.coroutines.launch

enum class SynthDestination(
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  PROJECTS(R.string.tab_projects, Icons.Filled.FolderOpen, Icons.Outlined.FolderOpen, "nav_projects"),
  EDITOR(R.string.tab_editor, Icons.Filled.Code, Icons.Outlined.Code, "nav_editor"),
  SYNTHESIS(R.string.tab_synthesis, Icons.Filled.Memory, Icons.Outlined.Memory, "nav_synthesis"),
  REPORTS(R.string.tab_reports, Icons.Filled.Assessment, Icons.Outlined.Assessment, "nav_reports"),
  SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings"),
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val viewModel: RtlSynthViewModel = viewModel(
        factory = viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY]) as Application
            val localStore = LocalWorkspaceStore(app)
            RtlSynthViewModel(localStore = localStore)
          }
        },
      )
      val settingsState by viewModel.settings.collectAsStateWithLifecycle()
      val isDark = settingsState.darkTheme

      DisposableEffect(isDark) {
        enableEdgeToEdge(
          statusBarStyle = if (isDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
          } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
          },
          navigationBarStyle = if (isDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
          } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
          },
        )
        onDispose {}
      }

      RtlSynthTheme(darkTheme = isDark) {
        MainWorkspaceScreen(viewModel = viewModel)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWorkspaceScreen(
  viewModel: RtlSynthViewModel,
) {
  val workbenchState by viewModel.workbench.collectAsStateWithLifecycle()
  val designsState by viewModel.savedDesigns.collectAsStateWithLifecycle()
  val runsState by viewModel.synthesisRuns.collectAsStateWithLifecycle()
  val settingsState by viewModel.settings.collectAsStateWithLifecycle()

  val eda = LocalEdaColors.current
  val context = LocalContext.current
  var currentTab by rememberSaveable { mutableStateOf(SynthDestination.PROJECTS) }
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  if (currentTab != SynthDestination.PROJECTS) {
    BackHandler {
      currentTab = SynthDestination.PROJECTS
    }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
        viewModel.flushAutoSave()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LaunchedEffect(workbenchState.targetLibrary) {
    viewModel.refreshDeviceAndPdkTelemetry(context)
  }

  LaunchedEffect(workbenchState.statusBanner) {
    val msg = workbenchState.statusBanner
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusBanner()
    }
  }

  val activeFileLabel = workbenchState.activeFile?.name
    ?: "${workbenchState.designName.ifBlank { "rtl_module" }}.${workbenchState.primaryLanguage.extension}"

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        Column {
          TopAppBar(
            title = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Rounded.Memory,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier
                    .padding(end = 10.dp)
                    .size(22.dp),
                )
                Column {
                  Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = "$activeFileLabel · ${workbenchState.targetLibrary.shortName} · ${workbenchState.synthesisEngine.shortName}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            },
            actions = {
              IconButton(
                onClick = viewModel::toggleDarkTheme,
                modifier = Modifier.testTag("toggle_theme_button"),
              ) {
                Icon(
                  imageVector = if (settingsState.darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                  contentDescription = if (settingsState.darkTheme) {
                    "Switch to light theme"
                  } else {
                    "Switch to dark industrial theme"
                  },
                )
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          )
          HorizontalDivider(color = eda.subtleBorder.copy(alpha = if (eda.isDark) 0.7f else 0.92f))
        }
      },
      bottomBar = {
        if (!useRail) {
          Column {
            HorizontalDivider(color = eda.subtleBorder.copy(alpha = if (eda.isDark) 0.7f else 0.92f))
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              tonalElevation = 0.dp,
              modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            ) {
              SynthDestination.entries.forEach { dest ->
                val selected = dest == currentTab
                NavigationBarItem(
                  selected = selected,
                  onClick = { currentTab = dest },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = stringResource(dest.labelRes),
                    )
                  },
                  label = {
                    Text(
                      text = stringResource(dest.labelRes),
                      style = MaterialTheme.typography.labelSmall,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  },
                  colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
                  modifier = Modifier.testTag(dest.testTag),
                )
              }
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useRail) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
          ) {
            SynthDestination.entries.forEach { dest ->
              val selected = dest == currentTab
              NavigationRailItem(
                selected = selected,
                onClick = { currentTab = dest },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = stringResource(dest.labelRes),
                  )
                },
                label = {
                  Text(
                    text = stringResource(dest.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
                colors = NavigationRailItemDefaults.colors(
                  selectedIconColor = MaterialTheme.colorScheme.primary,
                  selectedTextColor = MaterialTheme.colorScheme.primary,
                  indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxSize(),
        ) {
          Crossfade(
            targetState = currentTab,
            animationSpec = tween(durationMillis = 150),
            label = "ide_tab_transition",
          ) { tab ->
            when (tab) {
              SynthDestination.PROJECTS -> ProjectsScreen(
                workbench = workbenchState,
                designsState = designsState,
                runsState = runsState,
                onCreateProject = { name, lang, lib ->
                  viewModel.startBlankDesign(projectName = name, language = lang, library = lib)
                  currentTab = SynthDestination.EDITOR
                },
                onImportProjectFile = { fileName, content ->
                  viewModel.importFileContent(fileName, content)
                  currentTab = SynthDestination.EDITOR
                },
                onOpenDesign = { design ->
                  viewModel.loadSavedDesign(design)
                  currentTab = SynthDestination.EDITOR
                },
                onDuplicateDesign = viewModel::duplicateDesign,
                onDeleteDesign = viewModel::deleteSavedDesign,
                onOpenPreset = { preset ->
                  viewModel.loadPreset(preset)
                  currentTab = SynthDestination.EDITOR
                },
                onOpenActiveEditor = { currentTab = SynthDestination.EDITOR },
                onOpenRunReport = { currentTab = SynthDestination.REPORTS },
                onDeleteRun = viewModel::deleteSynthesisRun,
                onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
              )

              SynthDestination.EDITOR -> EditorScreen(
                state = workbenchState,
                settings = settingsState,
                onFileContentChange = viewModel::updateActiveFileContent,
                onSelectFileTab = viewModel::selectFileTab,
                onCloseFileTab = viewModel::closeFileTab,
                onAddFile = viewModel::addProjectFile,
                onDeleteFile = viewModel::deleteProjectFile,
                onRunSynthesis = {
                  viewModel.runSynthesis()
                  currentTab = SynthDestination.SYNTHESIS
                },
                onSaveProject = viewModel::saveDesign,
                onOpenReports = { currentTab = SynthDestination.REPORTS },
                onImportFile = viewModel::importFileContent,
                onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
              )

              SynthDestination.SYNTHESIS -> SynthesisScreen(
                state = workbenchState,
                onSelectTopModule = viewModel::selectTopModule,
                onSelectEngine = viewModel::selectSynthesisEngine,
                onSelectLibrary = viewModel::selectTargetLibrary,
                onSelectGoal = viewModel::selectOptimizationGoal,
                onClockPeriodChange = viewModel::updateClockPeriodNs,
                onUpdateSdc = viewModel::updateSdcConstraints,
                onResetSdc = viewModel::resetSdcToDefault,
                onRunSynthesis = viewModel::runSynthesis,
                onJumpToEditor = { currentTab = SynthDestination.EDITOR },
                onOpenReports = { currentTab = SynthDestination.REPORTS },
                onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onDesignNameChange = viewModel::updateDesignName,
                onCoreUtilizationChange = viewModel::updateCoreUtilizationPercent,
                onCancelFlow = viewModel::cancelRtlToGdsFlow,
                onResumeFlow = viewModel::resumeRtlToGdsFlow,
              )

              SynthDestination.REPORTS -> ReportsScreen(
                state = workbenchState,
                settings = settingsState,
                onSaveSnapshot = viewModel::saveRunSnapshotOnly,
                onSelectNode = viewModel::selectNode,
                onSetCycleCount = viewModel::setSimCycleCount,
                onCycleStimulusPattern = viewModel::cycleStimulusPattern,
                onStepCustomCellValue = viewModel::stepCustomSimCell,
                onRunSimulation = { viewModel.runSimulation(useTestbenchFile = false) },
                onRunTestbenchSimulation = { viewModel.runSimulation(useTestbenchFile = true) },
                onToggleWaveformSignal = viewModel::toggleWaveformSignal,
                onResetStimulus = viewModel::resetSimulationStimulus,
                onNotify = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
              )

              SynthDestination.SETTINGS -> {
                val designCount = (designsState as? UiState.Success)?.data?.size ?: 0
                val runCount = (runsState as? UiState.Success)?.data?.size ?: 0
                SettingsScreen(
                  settings = settingsState,
                  savedProjectCount = designCount,
                  savedRunCount = runCount,
                  storageKb = viewModel.estimatedStorageKb(),
                  onUpdateSettings = viewModel::updateSettings,
                  onClearSynthesisCache = viewModel::clearSynthesisHistory,
                  onResetWorkspace = viewModel::resetWorkspaceToStarter,
                )
              }
            }
          }
        }
      }
    }
  }
}
