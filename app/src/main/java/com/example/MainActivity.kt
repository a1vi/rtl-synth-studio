package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.local.LocalWorkspaceStore
import com.example.data.repository.RtlDesignRepository
import com.example.domain.synth.PresetRtlDesigns
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SchematicScreen
import com.example.ui.screens.SimulationScreen
import com.example.ui.screens.TimingPpaScreen
import com.example.ui.screens.WorkbenchScreen
import com.example.ui.theme.RtlSynthTheme
import com.example.ui.viewmodel.RtlSynthViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

enum class SynthDestination(
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  WORKBENCH(R.string.tab_workbench, Icons.Filled.Code, Icons.Outlined.Code, "nav_workbench"),
  SCHEMATIC(R.string.tab_schematic, Icons.Filled.AccountTree, Icons.Outlined.AccountTree, "nav_schematic"),
  SIMULATE(R.string.tab_simulate, Icons.Filled.Waves, Icons.Outlined.Waves, "nav_simulate"),
  TIMING(R.string.tab_timing, Icons.Filled.Speed, Icons.Outlined.Speed, "nav_timing"),
  LIBRARY(R.string.tab_library, Icons.Filled.FolderSpecial, Icons.Outlined.FolderSpecial, "nav_library"),
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      var darkTheme by rememberSaveable { mutableStateOf(true) }
      RtlSynthTheme(darkTheme = darkTheme) {
        AppNavigation(
          darkTheme = darkTheme,
          onToggleTheme = { darkTheme = !darkTheme },
        )
      }
    }
  }
}

@Composable
fun AppNavigation(
  darkTheme: Boolean,
  onToggleTheme: () -> Unit,
  auth: FirebaseAuth = Firebase.auth,
) {
  val currentUser by auth.authStateFlow()
    .collectAsStateWithLifecycle(initialValue = auth.currentUser)
  var continueOffline by rememberSaveable { mutableStateOf(false) }

  val user = currentUser
  if (user == null && !continueOffline) {
    AuthScreen(
      onAuthSuccess = { /* AuthStateListener drives navigation */ },
      onContinueOffline = { continueOffline = true },
    )
  } else {
    val workspaceKey = user?.uid ?: "local_offline"
    key(workspaceKey) {
      MainWorkspaceScreen(
        currentUserId = workspaceKey,
        userEmail = user?.email,
        isOfflineMode = user == null,
        darkTheme = darkTheme,
        onToggleTheme = onToggleTheme,
        onExitOfflineToSignIn = { continueOffline = false },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWorkspaceScreen(
  currentUserId: String,
  userEmail: String?,
  isOfflineMode: Boolean,
  darkTheme: Boolean,
  onToggleTheme: () -> Unit,
  onExitOfflineToSignIn: () -> Unit,
) {
  val viewModel: RtlSynthViewModel = viewModel(
    key = currentUserId,
    factory = viewModelFactory {
      initializer {
        val app = checkNotNull(this[APPLICATION_KEY]) as Application
        val localStore = LocalWorkspaceStore(app)
        val repository = if (!isOfflineMode) {
          val databaseId = app.getString(R.string.firestore_database_id)
          val db = FirebaseFirestore.getInstance(databaseId)
          RtlDesignRepository(db)
        } else {
          null
        }
        RtlSynthViewModel(
          repository = repository,
          currentUserId = currentUserId,
          localStore = localStore,
          isOfflineMode = isOfflineMode,
        )
      }
    },
  )

  val workbenchState by viewModel.workbench.collectAsStateWithLifecycle()
  val designsState by viewModel.savedDesigns.collectAsStateWithLifecycle()
  val runsState by viewModel.synthesisRuns.collectAsStateWithLifecycle()
  val localPendingDesigns by viewModel.localPendingDesigns.collectAsStateWithLifecycle()

  var currentTab by rememberSaveable { mutableStateOf(SynthDestination.WORKBENCH) }
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  if (currentTab != SynthDestination.WORKBENCH) {
    BackHandler {
      currentTab = SynthDestination.WORKBENCH
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

  LaunchedEffect(workbenchState.statusBanner) {
    val msg = workbenchState.statusBanner
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusBanner()
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        androidx.compose.foundation.layout.Column {
          TopAppBar(
            title = {
              Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Rounded.Memory,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier
                    .padding(end = 10.dp)
                    .size(22.dp),
                )
                androidx.compose.foundation.layout.Column {
                  Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                  )
                  Text(
                    text = "${workbenchState.designName.ifBlank { "rtl_module" }}.v · ${workbenchState.targetLibrary.shortName}" +
                      if (isOfflineMode) " · Offline" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            },
            actions = {
              IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.testTag("toggle_theme_button"),
              ) {
                Icon(
                  imageVector = if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                  contentDescription = if (darkTheme) "Switch to light blueprint theme" else "Switch to dark silicon theme",
                )
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          )
          androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
          )
        }
      },
      bottomBar = {
        if (!useRail) {
          androidx.compose.foundation.layout.Column {
            androidx.compose.material3.HorizontalDivider(
              color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            )
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
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
                    )
                  },
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
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
          when (currentTab) {
            SynthDestination.WORKBENCH -> WorkbenchScreen(
              state = workbenchState,
              onCodeChange = viewModel::updateVerilogCode,
              onMetaChange = viewModel::updateDesignMetadata,
              onSelectLibrary = viewModel::selectTargetLibrary,
              onSelectGoal = viewModel::selectOptimizationGoal,
              onClockPeriodChange = viewModel::updateClockPeriodNs,
              onSelectTopModule = viewModel::selectTopModule,
              onRunSynthesis = viewModel::runSynthesis,
              onSaveToCloud = viewModel::saveDesignToCloud,
              onLoadPreset = { idx ->
                PresetRtlDesigns.templates.getOrNull(idx)?.let(viewModel::loadPreset)
              },
              onNewBlankDesign = viewModel::startBlankDesign,
              onOpenSchematic = { currentTab = SynthDestination.SCHEMATIC },
              onUpdateSdc = viewModel::updateSdcConstraints,
              onResetSdc = viewModel::resetSdcToDefault,
              onImportFile = viewModel::importFileContent,
              onNotify = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
              },
            )
            SynthDestination.SCHEMATIC -> SchematicScreen(
              synthesisResult = workbenchState.synthesisResult,
              selectedNodeId = workbenchState.selectedNodeId,
              onSelectNode = viewModel::selectNode,
              onNotifyCopied = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
              },
              manualPositions = workbenchState.manualNodePositions,
              isManualSchematic = workbenchState.isManualSchematic,
              onAddPrimitive = viewModel::addPrimitiveToSchematic,
              onConnectNodes = viewModel::connectGatesInSchematic,
              onDeleteNode = viewModel::removeGateFromSchematic,
              onStartBlankSchematic = viewModel::startBlankSchematic,
              onResetSchematic = viewModel::resetSchematicToRtl,
            )
            SynthDestination.SIMULATE -> SimulationScreen(
              simulation = workbenchState.simulationResult,
              cycleCount = workbenchState.simCycleCount,
              stimulusPatterns = workbenchState.stimulusPatterns,
              onSetCycleCount = viewModel::setSimCycleCount,
              onCycleStimulusPattern = viewModel::cycleStimulusPattern,
              onStepCustomCellValue = viewModel::stepCustomSimCell,
              onRunSimulation = viewModel::runSimulation,
              onResetStimulus = viewModel::resetSimulationStimulus,
            )
            SynthDestination.TIMING -> TimingPpaScreen(
              synthesisResult = workbenchState.synthesisResult,
              isSaving = workbenchState.isSaving,
              onSaveSnapshot = viewModel::saveRunSnapshotOnly,
              onInspectNodeInSchematic = { nodeId ->
                viewModel.selectNode(nodeId)
                currentTab = SynthDestination.SCHEMATIC
              },
              isOfflineMode = isOfflineMode,
            )
            SynthDestination.LIBRARY -> LibraryScreen(
              userEmail = userEmail,
              designsState = designsState,
              runsState = runsState,
              onLoadDesign = { design ->
                viewModel.loadSavedDesign(design)
                currentTab = SynthDestination.WORKBENCH
              },
              onLoadPreset = { preset ->
                viewModel.loadPreset(preset)
                currentTab = SynthDestination.WORKBENCH
              },
              onNewBlankDesign = {
                viewModel.startBlankDesign()
                currentTab = SynthDestination.WORKBENCH
              },
              onDeleteDesign = viewModel::deleteCloudDesign,
              onDeleteRun = viewModel::deleteSynthesisRun,
              onSignOut = {
                signOutUser(
                  credentialManager = credentialManager,
                  scope = scope,
                )
              },
              isOfflineMode = isOfflineMode,
              pendingLocalCount = if (isOfflineMode) 0 else localPendingDesigns.size,
              onSyncLocalToCloud = viewModel::syncLocalDesignsToCloud,
              onSignInToCloud = onExitOfflineToSignIn,
            )
          }
        }
      }
    }
  }
}
