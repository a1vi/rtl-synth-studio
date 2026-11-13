package com.example.ui.viewmodel

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalDraftSnapshot
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlWorkspaceDraft
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.data.repository.RtlDesignRepository
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.NetlistLayoutEngine
import com.example.domain.synth.ParsedVerilogDesign
import com.example.domain.synth.PortDirection
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlPort
import com.example.domain.synth.RtlPresetTemplate
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.SdcParser
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogHdlParser
import com.example.domain.synth.VerilogParseResult
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

enum class AutoSaveStatus {
  IDLE,
  SAVING,
  SAVED,
  ERROR,
  OFFLINE,
}

interface WorkspaceAutoSaveGateway {
  suspend fun loadAutoSaveDraft(): Result<RtlWorkspaceDraft?>
  suspend fun saveAutoSaveDraft(draft: RtlWorkspaceDraft, knownExists: Boolean?): Result<Unit>
  suspend fun updateActiveDesign(design: RtlDesign): Result<Unit>
  suspend fun getDesignById(designId: String): Result<RtlDesign?>
}

private class FirestoreAutoSaveGateway(
  private val repository: RtlDesignRepository,
) : WorkspaceAutoSaveGateway {
  override suspend fun loadAutoSaveDraft(): Result<RtlWorkspaceDraft?> =
    repository.getAutoSaveDraft()

  override suspend fun saveAutoSaveDraft(
    draft: RtlWorkspaceDraft,
    knownExists: Boolean?,
  ): Result<Unit> = repository.saveAutoSaveDraft(draft, knownExists = knownExists)

  override suspend fun updateActiveDesign(design: RtlDesign): Result<Unit> =
    repository.updateDesign(design)

  override suspend fun getDesignById(designId: String): Result<RtlDesign?> =
    repository.getDesignById(designId)
}

data class WorkbenchUiState(
  val activeCloudDesignId: String? = null,
  val activeCloudCreatedAt: Timestamp? = null,
  val designName: String = PresetRtlDesigns.templates.first().name,
  val description: String = PresetRtlDesigns.templates.first().description,
  val verilogCode: String = PresetRtlDesigns.templates.first().verilogCode,
  val targetLibrary: TargetLibrary = PresetRtlDesigns.templates.first().defaultLibrary,
  val optimizationGoal: OptimizationGoal = PresetRtlDesigns.templates.first().defaultGoal,
  val clockPeriodNs: Double = PresetRtlDesigns.templates.first().defaultClockNs,
  val sdcText: String = "",
  val selectedTopModule: String? = null,
  val parsedDesign: ParsedVerilogDesign? = null,
  val parseError: VerilogParseResult.Error? = null,
  val synthesisResult: SynthesisResult? = null,
  val selectedNodeId: String? = null,
  val manualNodePositions: Map<String, Offset> = emptyMap(),
  val isManualSchematic: Boolean = false,
  val isSynthesizing: Boolean = false,
  val isSaving: Boolean = false,
  val statusBanner: String? = null,
  val hasUnsavedChanges: Boolean = false,
  val isOfflineMode: Boolean = false,
  val autoSaveStatus: AutoSaveStatus = if (isOfflineMode) AutoSaveStatus.OFFLINE else AutoSaveStatus.IDLE,
  val lastAutoSavedLabel: String? = null,
  val simulationResult: SimulationResult? = null,
  val simCycleCount: Int = 16,
  val stimulusPatterns: Map<String, StimulusPattern> = emptyMap(),
  val customSimValues: Map<Pair<String, Int>, Long> = emptyMap(),
)

private data class ProjectSaveSignature(
  val activeCloudDesignId: String,
  val designName: String,
  val description: String,
  val verilogCode: String,
  val sdcText: String,
  val targetLibrary: TargetLibrary,
  val optimizationGoal: OptimizationGoal,
  val clockPeriodNs: Double,
  val lastWnsNs: Double,
  val lastAreaUm2: Double,
  val lastCellCount: Long,
  val lastPowerMw: Double,
)

@OptIn(FlowPreview::class)
class RtlSynthViewModel(
  private val repository: RtlDesignRepository? = null,
  private val currentUserId: String = "local_offline",
  private val localStore: LocalWorkspaceStore? = null,
  private val isOfflineMode: Boolean = repository == null,
  private val autoSaveGateway: WorkspaceAutoSaveGateway? =
    if (!isOfflineMode && repository != null) FirestoreAutoSaveGateway(repository) else null,
  private val autoSaveIntervalMs: Long = DEFAULT_AUTOSAVE_INTERVAL_MS,
  private val autoSaveDebounceMs: Long = DEFAULT_AUTOSAVE_DEBOUNCE_MS,
  private val clockMillisProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

  val savedDesigns: StateFlow<UiState<List<RtlDesign>>> = if (!isOfflineMode && repository != null) {
    repository.observeDesigns(currentUserId)
      .map<List<RtlDesign>, UiState<List<RtlDesign>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
          .filterIsInstance<FirebaseFirestoreException>()
          .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing saved designs", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load saved designs"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )
  } else {
    (localStore?.localDesigns ?: flowOf(emptyList()))
      .map<List<RtlDesign>, UiState<List<RtlDesign>>> { UiState.Success(it) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Success(emptyList()),
      )
  }

  val synthesisRuns: StateFlow<UiState<List<SynthesisRun>>> = if (!isOfflineMode && repository != null) {
    repository.observeSynthesisRuns(currentUserId)
      .map<List<SynthesisRun>, UiState<List<SynthesisRun>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
          .filterIsInstance<FirebaseFirestoreException>()
          .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing synthesis runs", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load synthesis runs"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )
  } else {
    (localStore?.localRuns ?: flowOf(emptyList()))
      .map<List<SynthesisRun>, UiState<List<SynthesisRun>>> { UiState.Success(it) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Success(emptyList()),
      )
  }

  val localPendingDesigns: StateFlow<List<RtlDesign>> =
    localStore?.localDesigns ?: MutableStateFlow(emptyList())

  private val autoSaveMutex = Mutex()
  private var initialLocalDraft: LocalDraftSnapshot? = null
  private var userEditedSinceStartup: Boolean = false
  private var dirtyForCloudAutoSave: Boolean = false
  private var cloudAutoSaveExists: Boolean? = null
  private var lastAutoSavedSignature: ProjectSaveSignature? = null

  private val _workbench = MutableStateFlow(createInitialState())
  val workbench: StateFlow<WorkbenchUiState> = _workbench.asStateFlow()

  init {
    refreshLiveParse()
    runSynthesis()
    startCloudAutoSaveLoops()
  }

  private fun createInitialState(): WorkbenchUiState {
    val draft = localStore?.loadActiveDraft()
    initialLocalDraft = draft
    val base = if (draft != null) {
      WorkbenchUiState(
        designName = draft.designName,
        description = draft.description,
        verilogCode = draft.verilogCode,
        sdcText = draft.sdcText,
        targetLibrary = draft.targetLibrary,
        optimizationGoal = draft.optimizationGoal,
        clockPeriodNs = draft.clockPeriodNs,
        isOfflineMode = isOfflineMode,
        autoSaveStatus = if (isOfflineMode) AutoSaveStatus.OFFLINE else AutoSaveStatus.IDLE,
      )
    } else {
      val first = PresetRtlDesigns.templates.first()
      val parsed = (VerilogHdlParser.parse(first.verilogCode) as? VerilogParseResult.Success)?.design
      val initialSdc = SdcParser.generateDefaultSdc(
        moduleName = first.name,
        ports = parsed?.topModule?.ports.orEmpty(),
        clockPeriodNs = first.defaultClockNs,
      )
      WorkbenchUiState(
        designName = first.name,
        description = first.description,
        verilogCode = first.verilogCode,
        sdcText = initialSdc,
        targetLibrary = first.defaultLibrary,
        optimizationGoal = first.defaultGoal,
        clockPeriodNs = first.defaultClockNs,
        isOfflineMode = isOfflineMode,
        autoSaveStatus = if (isOfflineMode) AutoSaveStatus.OFFLINE else AutoSaveStatus.IDLE,
      )
    }
    return base
  }

  private fun persistDraft(state: WorkbenchUiState, markDirtyForAutoSave: Boolean = true) {
    if (markDirtyForAutoSave) {
      userEditedSinceStartup = true
      dirtyForCloudAutoSave = true
    }
    localStore?.saveActiveDraft(
      LocalDraftSnapshot(
        designName = state.designName,
        description = state.description,
        verilogCode = state.verilogCode,
        sdcText = state.sdcText,
        targetLibrary = state.targetLibrary,
        optimizationGoal = state.optimizationGoal,
        clockPeriodNs = state.clockPeriodNs,
        savedAtEpochMs = clockMillisProvider(),
      )
    )
  }

  private fun WorkbenchUiState.toSaveSignature(): ProjectSaveSignature {
    val report = (synthesisResult as? SynthesisResult.Success)?.report
    return ProjectSaveSignature(
      activeCloudDesignId = activeCloudDesignId.orEmpty(),
      designName = designName.trim(),
      description = description.trim(),
      verilogCode = verilogCode,
      sdcText = sdcText,
      targetLibrary = targetLibrary,
      optimizationGoal = optimizationGoal,
      clockPeriodNs = clockPeriodNs,
      lastWnsNs = report?.wnsNs ?: 0.0,
      lastAreaUm2 = report?.totalAreaUm2 ?: 0.0,
      lastCellCount = (report?.cellCount ?: 0).toLong(),
      lastPowerMw = report?.totalPowerMw ?: 0.0,
    )
  }

  private fun startCloudAutoSaveLoops() {
    if (isOfflineMode || autoSaveGateway == null) return

    viewModelScope.launch {
      recoverCloudAutoSaveIfNewer()
    }

    // Periodic interval auto-save to Firestore
    viewModelScope.launch {
      while (isActive) {
        delay(autoSaveIntervalMs)
        performAutoSaveIfDirty()
      }
    }

    // Short debounce after edits settle so progress is saved promptly even between intervals
    viewModelScope.launch {
      _workbench
        .map { it.toSaveSignature() }
        .distinctUntilChanged()
        .drop(1)
        .debounce(autoSaveDebounceMs)
        .collect {
          performAutoSaveIfDirty()
        }
    }
  }

  private suspend fun recoverCloudAutoSaveIfNewer() {
    val gateway = autoSaveGateway ?: return
    val result = gateway.loadAutoSaveDraft()
    val cloudDraft = result.getOrNull()
    if (cloudDraft != null) {
      cloudAutoSaveExists = true
    } else if (result.isSuccess) {
      cloudAutoSaveExists = false
      return
    } else {
      return
    }

    if (userEditedSinceStartup || cloudDraft.verilogCode.isBlank()) return

    val cloudUpdatedMs = cloudDraft.updatedAt?.toDate()?.time ?: 0L
    val localSavedMs = initialLocalDraft?.savedAtEpochMs ?: 0L
    val shouldRestore = initialLocalDraft == null || cloudUpdatedMs > localSavedMs
    if (!shouldRestore) return

    val restoredDesignId = cloudDraft.activeCloudDesignId.ifBlank { null }
    val resolvedCreatedAt = if (restoredDesignId != null && !restoredDesignId.startsWith("local_")) {
      gateway.getDesignById(restoredDesignId).getOrNull()?.createdAt
    } else {
      null
    }

    val restoredLib = TargetLibrary.fromId(cloudDraft.targetLibrary)
    val restoredGoal = OptimizationGoal.fromId(cloudDraft.optimizationGoal)
    val restoredLabel = formatClockTime(if (cloudUpdatedMs > 0L) cloudUpdatedMs else clockMillisProvider())

    _workbench.update { current ->
      applyParseToState(
        current.copy(
          activeCloudDesignId = restoredDesignId,
          activeCloudCreatedAt = resolvedCreatedAt,
          designName = cloudDraft.designName.ifBlank { "rtl_module" },
          description = cloudDraft.description,
          verilogCode = cloudDraft.verilogCode,
          sdcText = cloudDraft.sdcText,
          targetLibrary = restoredLib,
          optimizationGoal = restoredGoal,
          clockPeriodNs = cloudDraft.clockPeriodNs.coerceIn(0.4, 50.0),
          hasUnsavedChanges = false,
          autoSaveStatus = AutoSaveStatus.SAVED,
          lastAutoSavedLabel = restoredLabel,
          statusBanner = "Restored auto-saved workspace '${cloudDraft.designName.ifBlank { "rtl_module" }}' from cloud.",
        )
      )
    }

    localStore?.saveActiveDraft(
      LocalDraftSnapshot(
        designName = _workbench.value.designName,
        description = _workbench.value.description,
        verilogCode = _workbench.value.verilogCode,
        sdcText = _workbench.value.sdcText,
        targetLibrary = restoredLib,
        optimizationGoal = restoredGoal,
        clockPeriodNs = _workbench.value.clockPeriodNs,
        savedAtEpochMs = cloudUpdatedMs.coerceAtLeast(clockMillisProvider()),
      )
    )
    lastAutoSavedSignature = _workbench.value.toSaveSignature()
    dirtyForCloudAutoSave = false
    runSynthesis()
  }

  fun flushAutoSave() {
    persistDraft(_workbench.value, markDirtyForAutoSave = false)
    if (isOfflineMode || autoSaveGateway == null) return
    viewModelScope.launch {
      withContext(NonCancellable) {
        performAutoSaveIfDirty()
      }
    }
  }

  suspend fun performAutoSaveIfDirty() {
    val gateway = autoSaveGateway ?: return
    if (isOfflineMode) return
    autoSaveMutex.withLock {
      val state = _workbench.value
      val sig = state.toSaveSignature()
      if (!dirtyForCloudAutoSave && sig == lastAutoSavedSignature) return
      if (!dirtyForCloudAutoSave) return

      _workbench.update { it.copy(autoSaveStatus = AutoSaveStatus.SAVING) }

      val report = (state.synthesisResult as? SynthesisResult.Success)?.report
      val draftPayload = RtlWorkspaceDraft(
        id = RtlDesignRepository.DEFAULT_AUTOSAVE_ID,
        userId = currentUserId,
        activeCloudDesignId = state.activeCloudDesignId.orEmpty(),
        designName = state.designName.trim().take(80),
        description = state.description.trim().take(300),
        verilogCode = state.verilogCode.take(20000),
        sdcText = state.sdcText.take(10000),
        targetLibrary = state.targetLibrary.id,
        optimizationGoal = state.optimizationGoal.id,
        clockPeriodNs = state.clockPeriodNs.coerceIn(0.4, 1000.0),
        lastWnsNs = report?.wnsNs ?: 0.0,
        lastAreaUm2 = report?.totalAreaUm2 ?: 0.0,
        lastCellCount = (report?.cellCount ?: 0).toLong(),
        lastPowerMw = report?.totalPowerMw ?: 0.0,
      )

      val draftResult = gateway.saveAutoSaveDraft(draftPayload, knownExists = cloudAutoSaveExists)
      if (draftResult.isSuccess) {
        cloudAutoSaveExists = true
        lastAutoSavedSignature = sig
        val activeId = state.activeCloudDesignId
        val activeCreatedAt = state.activeCloudCreatedAt
        var syncedActiveDesign = false
        if (
          activeId != null &&
          !activeId.startsWith("local_") &&
          activeCreatedAt != null &&
          state.designName.isNotBlank() &&
          state.verilogCode.isNotBlank()
        ) {
          val updateOutcome = gateway.updateActiveDesign(
            RtlDesign(
              id = activeId,
              userId = currentUserId,
              name = state.designName.trim(),
              description = state.description.trim(),
              verilogCode = state.verilogCode,
              targetLibrary = state.targetLibrary.id,
              optimizationGoal = state.optimizationGoal.id,
              clockPeriodNs = state.clockPeriodNs,
              lastWnsNs = report?.wnsNs ?: 0.0,
              lastAreaUm2 = report?.totalAreaUm2 ?: 0.0,
              lastCellCount = (report?.cellCount ?: 0).toLong(),
              lastPowerMw = report?.totalPowerMw ?: 0.0,
              createdAt = activeCreatedAt,
            )
          )
          syncedActiveDesign = updateOutcome.isSuccess
        }

        val currentSigAfterSave = _workbench.value.toSaveSignature()
        if (currentSigAfterSave == sig) {
          dirtyForCloudAutoSave = false
        }
        val savedTime = formatClockTime(clockMillisProvider())
        _workbench.update {
          it.copy(
            autoSaveStatus = AutoSaveStatus.SAVED,
            lastAutoSavedLabel = savedTime,
            hasUnsavedChanges = if (syncedActiveDesign && currentSigAfterSave == sig) false else it.hasUnsavedChanges,
          )
        }
      } else {
        _workbench.update { it.copy(autoSaveStatus = AutoSaveStatus.ERROR) }
      }
    }
  }

  private fun formatClockTime(epochMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMs))
  }

  override fun onCleared() {
    persistDraft(_workbench.value, markDirtyForAutoSave = false)
    val gateway = autoSaveGateway
    if (!isOfflineMode && gateway != null && dirtyForCloudAutoSave) {
      val state = _workbench.value
      val report = (state.synthesisResult as? SynthesisResult.Success)?.report
      val draftPayload = RtlWorkspaceDraft(
        id = RtlDesignRepository.DEFAULT_AUTOSAVE_ID,
        userId = currentUserId,
        activeCloudDesignId = state.activeCloudDesignId.orEmpty(),
        designName = state.designName.trim().take(80),
        description = state.description.trim().take(300),
        verilogCode = state.verilogCode.take(20000),
        sdcText = state.sdcText.take(10000),
        targetLibrary = state.targetLibrary.id,
        optimizationGoal = state.optimizationGoal.id,
        clockPeriodNs = state.clockPeriodNs.coerceIn(0.4, 1000.0),
        lastWnsNs = report?.wnsNs ?: 0.0,
        lastAreaUm2 = report?.totalAreaUm2 ?: 0.0,
        lastCellCount = (report?.cellCount ?: 0).toLong(),
        lastPowerMw = report?.totalPowerMw ?: 0.0,
      )
      val existsSnapshot = cloudAutoSaveExists
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        gateway.saveAutoSaveDraft(draftPayload, knownExists = existsSnapshot)
      }
    }
    super.onCleared()
  }

  private fun applyParseToState(state: WorkbenchUiState): WorkbenchUiState {
    return when (val parsed = VerilogHdlParser.parse(state.verilogCode, state.selectedTopModule)) {
      is VerilogParseResult.Success -> {
        val validTop = state.selectedTopModule?.takeIf { sel ->
          parsed.design.modules.any { it.name == sel }
        }
        val nextSdc = state.sdcText.ifBlank {
          SdcParser.generateDefaultSdc(
            moduleName = parsed.design.topModule.name,
            ports = parsed.design.topModule.ports,
            clockPeriodNs = state.clockPeriodNs,
          )
        }
        state.copy(
          selectedTopModule = validTop,
          parsedDesign = parsed.design,
          parseError = null,
          sdcText = nextSdc,
        )
      }
      is VerilogParseResult.Error -> state.copy(
        parsedDesign = null,
        parseError = parsed,
      )
    }
  }

  private fun refreshLiveParse() {
    _workbench.update { applyParseToState(it) }
  }

  fun updateVerilogCode(code: String) {
    _workbench.update { state ->
      applyParseToState(state.copy(verilogCode = code, hasUnsavedChanges = true)).also {
        persistDraft(it)
      }
    }
  }

  fun selectTopModule(moduleName: String) {
    _workbench.update { state ->
      applyParseToState(state.copy(selectedTopModule = moduleName)).also {
        persistDraft(it)
      }
    }
    runSynthesis()
  }

  fun updateDesignMetadata(name: String, description: String) {
    _workbench.update {
      it.copy(
        designName = name.take(80),
        description = description.take(300),
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
  }

  fun selectTargetLibrary(library: TargetLibrary) {
    _workbench.update { it.copy(targetLibrary = library, hasUnsavedChanges = true).also(::persistDraft) }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else {
      runSynthesis()
    }
  }

  fun selectOptimizationGoal(goal: OptimizationGoal) {
    _workbench.update { it.copy(optimizationGoal = goal, hasUnsavedChanges = true).also(::persistDraft) }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else {
      runSynthesis()
    }
  }

  fun updateClockPeriodNs(periodNs: Double) {
    val clamped = periodNs.coerceIn(0.4, 50.0)
    _workbench.update { state ->
      val syncedSdc = if (state.sdcText.isNotBlank()) {
        SdcParser.updateClockPeriodInSdc(state.sdcText, clamped)
      } else {
        state.sdcText
      }
      state.copy(
        clockPeriodNs = clamped,
        sdcText = syncedSdc,
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else {
      runSynthesis()
    }
  }

  fun updateSdcConstraints(sdc: String) {
    val parsedSdc = SdcParser.parse(sdc, _workbench.value.clockPeriodNs)
    val nextPeriod = parsedSdc.primaryClockPeriodNs?.coerceIn(0.4, 50.0) ?: _workbench.value.clockPeriodNs
    _workbench.update {
      it.copy(
        sdcText = sdc,
        clockPeriodNs = nextPeriod,
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else {
      runSynthesis()
    }
  }

  fun resetSdcToDefault() {
    val state = _workbench.value
    val modName = state.parsedDesign?.topModule?.name ?: state.designName
    val ports = state.parsedDesign?.topModule?.ports.orEmpty()
    val defaultSdc = SdcParser.generateDefaultSdc(modName, ports, state.clockPeriodNs)
    updateSdcConstraints(defaultSdc)
  }

  fun importFileContent(fileName: String, content: String) {
    val cleanName = fileName.trim()
    val lower = cleanName.lowercase(Locale.ROOT)
    if (lower.endsWith(".sdc")) {
      updateSdcConstraints(content)
      _workbench.update { it.copy(statusBanner = "Imported SDC constraints from $cleanName.") }
      return
    }

    val baseName = cleanName
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .substringBeforeLast('.')
      .ifBlank { "imported_module" }

    val parsed = VerilogHdlParser.parse(content)
    val resolvedName = (parsed as? VerilogParseResult.Success)?.design?.topModule?.name ?: baseName
    val resolvedSdc = (parsed as? VerilogParseResult.Success)?.let { p ->
      SdcParser.generateDefaultSdc(resolvedName, p.design.topModule.ports, _workbench.value.clockPeriodNs)
    } ?: _workbench.value.sdcText

    _workbench.update { state ->
      applyParseToState(
        state.copy(
          activeCloudDesignId = null,
          activeCloudCreatedAt = null,
          designName = resolvedName,
          description = "Imported from $cleanName",
          verilogCode = content,
          sdcText = resolvedSdc,
          selectedTopModule = null,
          hasUnsavedChanges = true,
          stimulusPatterns = emptyMap(),
          customSimValues = emptyMap(),
          statusBanner = "Imported $cleanName into editor.",
        )
      ).also(::persistDraft)
    }
    runSynthesis()
  }

  fun selectNode(nodeId: String?) {
    _workbench.update { it.copy(selectedNodeId = nodeId) }
  }

  fun startBlankSchematic() {
    val state = _workbench.value
    val blankReport = RtlSynthesisEngine.createBlankManualReport(
      moduleName = state.designName.ifBlank { "custom_schematic" },
      library = state.targetLibrary,
      goal = state.optimizationGoal,
      clockPeriodNs = state.clockPeriodNs,
    )
    _workbench.update {
      it.copy(
        synthesisResult = SynthesisResult.Success(blankReport),
        selectedNodeId = null,
        manualNodePositions = emptyMap(),
        isManualSchematic = true,
        statusBanner = "Started blank schematic canvas — drag logic gates to build.",
      )
    }
    runSimulation()
  }

  fun resetSchematicToRtl() {
    _workbench.update {
      it.copy(
        manualNodePositions = emptyMap(),
        isManualSchematic = false,
      )
    }
    runSynthesis()
  }

  fun addPrimitiveToSchematic(
    gateType: GateType,
    worldPosition: Offset? = null,
  ) {
    val state = _workbench.value
    val baseReport = (state.synthesisResult as? SynthesisResult.Success)?.report
      ?: RtlSynthesisEngine.createBlankManualReport(
        moduleName = state.designName.ifBlank { "custom_schematic" },
        library = state.targetLibrary,
        goal = state.optimizationGoal,
        clockPeriodNs = state.clockPeriodNs,
      )

    val existingNodes = baseReport.nodes
    val nextIdx = (existingNodes.size + 1).let { start ->
      var i = start
      while (existingNodes.any { it.id == "man_$i" || it.outputNet == "n_man_$i" }) i++
      i
    }

    val updatedPorts = baseReport.ports.toMutableList()
    val newNode = when (gateType) {
      GateType.INPUT_PORT -> {
        val portName = uniqueName("in", existingNodes.map { it.outputNet }.toSet(), nextIdx)
        updatedPorts += RtlPort(name = portName, direction = PortDirection.INPUT, width = 1, isReg = false)
        GateNode(
          id = "in_$portName",
          cellName = "INPUT",
          gateType = GateType.INPUT_PORT,
          instanceName = portName,
          inputs = emptyList(),
          outputNet = portName,
          bitWidth = 1,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
        )
      }
      GateType.OUTPUT_PORT -> {
        val portName = uniqueName("out", existingNodes.map { it.outputNet }.toSet(), nextIdx)
        updatedPorts += RtlPort(name = portName, direction = PortDirection.OUTPUT, width = 1, isReg = false)
        GateNode(
          id = "out_$portName",
          cellName = "OUTPUT",
          gateType = GateType.OUTPUT_PORT,
          instanceName = portName,
          inputs = listOf(portName),
          outputNet = portName,
          bitWidth = 1,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
        )
      }
      else -> {
        val netName = uniqueName("w", existingNodes.map { it.outputNet }.toSet(), nextIdx)
        val instName = uniqueName("u_${gateType.shortLabel.lowercase(Locale.ROOT)}", existingNodes.map { it.instanceName }.toSet(), nextIdx)
        val clkNet = existingNodes.firstOrNull {
          it.gateType == GateType.INPUT_PORT && it.outputNet.lowercase(Locale.ROOT).contains("clk")
        }?.outputNet ?: "clk"
        val defaultInputs = when {
          gateType.isSequential -> listOf("1'b0", clkNet)
          gateType == GateType.INV || gateType == GateType.BUF -> listOf("1'b0")
          gateType == GateType.MUX2 -> listOf("1'b0", "1'b0", "1'b0")
          else -> listOf("1'b0", "1'b0")
        }
        GateNode(
          id = "man_$nextIdx",
          cellName = gateType.shortLabel,
          gateType = gateType,
          instanceName = instName,
          inputs = defaultInputs,
          outputNet = netName,
          bitWidth = 1,
          delayNs = 0.0,
          areaUm2 = 0.0,
          powerUw = 0.0,
        )
      }
    }

    val clampedPos = worldPosition?.let {
      Offset(
        x = it.x.coerceAtLeast(16f),
        y = it.y.coerceAtLeast(16f),
      )
    } ?: Offset(
      x = NetlistLayoutEngine.WORLD_PADDING + (existingNodes.count { !it.gateType.isPort } % 3 + 1) * 180f,
      y = NetlistLayoutEngine.WORLD_PADDING + (existingNodes.size % 4) * 92f,
    )

    val updatedPositions = state.manualNodePositions.toMutableMap().apply {
      put(newNode.id, clampedPos)
    }

    recomputeManualReport(
      baseReport = baseReport.copy(ports = updatedPorts, nodes = existingNodes + newNode),
      positions = updatedPositions,
      selectedNodeId = newNode.id,
      banner = "Placed ${gateType.shortLabel} (${newNode.instanceName}).",
    )
  }

  fun connectGatesInSchematic(fromNodeId: String, toNodeId: String) {
    if (fromNodeId == toNodeId) return
    val state = _workbench.value
    val baseReport = (state.synthesisResult as? SynthesisResult.Success)?.report ?: return
    val fromNode = baseReport.nodes.firstOrNull { it.id == fromNodeId } ?: return
    val toNode = baseReport.nodes.firstOrNull { it.id == toNodeId } ?: return

    if (fromNode.gateType == GateType.OUTPUT_PORT) {
      _workbench.update { it.copy(statusBanner = "Output ports cannot drive other gates.") }
      return
    }
    if (toNode.gateType == GateType.INPUT_PORT) {
      _workbench.update { it.copy(statusBanner = "Input ports cannot be driven by gates.") }
      return
    }

    val srcNet = fromNode.outputNet
    val updatedNodes = baseReport.nodes.map { node ->
      if (node.id != toNode.id) {
        node
      } else {
        val nextInputs = when {
          node.gateType == GateType.OUTPUT_PORT -> listOf(srcNet)
          node.gateType == GateType.INV || node.gateType == GateType.BUF -> listOf(srcNet)
          node.gateType.isSequential -> {
            val clk = node.inputs.getOrNull(1) ?: "clk"
            val rst = node.inputs.getOrNull(2)
            listOfNotNull(srcNet, clk, rst)
          }
          else -> {
            val maxPins = if (node.gateType == GateType.MUX2) 3 else 2
            val current = node.inputs.toMutableList()
            val placeholderIdx = current.indexOfFirst { it == "1'b0" || it == "1'b1" || it.isBlank() }
            when {
              srcNet in current -> current
              placeholderIdx >= 0 -> current.apply { set(placeholderIdx, srcNet) }
              current.size < maxPins -> current + srcNet
              else -> current.drop(1) + srcNet
            }
          }
        }
        node.copy(inputs = nextInputs)
      }
    }

    recomputeManualReport(
      baseReport = baseReport.copy(nodes = updatedNodes),
      positions = state.manualNodePositions,
      selectedNodeId = toNode.id,
      banner = "Connected ${fromNode.instanceName} (${fromNode.outputNet}) → ${toNode.instanceName}.",
    )
  }

  fun removeGateFromSchematic(nodeId: String) {
    val state = _workbench.value
    val baseReport = (state.synthesisResult as? SynthesisResult.Success)?.report ?: return
    val target = baseReport.nodes.firstOrNull { it.id == nodeId } ?: return
    val removedNet = target.outputNet

    val remainingNodes = baseReport.nodes
      .filterNot { it.id == nodeId }
      .map { node ->
        if (removedNet in node.inputs) {
          node.copy(inputs = node.inputs.map { if (it == removedNet) "1'b0" else it })
        } else {
          node
        }
      }
    val remainingPorts = if (target.gateType.isPort) {
      baseReport.ports.filterNot { it.name == target.outputNet }
    } else {
      baseReport.ports
    }
    val updatedPositions = state.manualNodePositions - nodeId

    recomputeManualReport(
      baseReport = baseReport.copy(ports = remainingPorts, nodes = remainingNodes),
      positions = updatedPositions,
      selectedNodeId = null,
      banner = "Removed ${target.instanceName} from schematic.",
    )
  }

  private fun recomputeManualReport(
    baseReport: SynthesisReport,
    positions: Map<String, Offset>,
    selectedNodeId: String?,
    banner: String? = null,
  ) {
    val state = _workbench.value
    val updatedReport = RtlSynthesisEngine.recomputeReportFromNodes(
      moduleName = baseReport.moduleName,
      ports = baseReport.ports,
      rawNodes = baseReport.nodes,
      library = state.targetLibrary,
      goal = state.optimizationGoal,
      clockPeriodNs = state.clockPeriodNs,
      warnings = baseReport.warnings,
      sdcText = state.sdcText,
    )
    _workbench.update {
      it.copy(
        synthesisResult = SynthesisResult.Success(updatedReport),
        selectedNodeId = selectedNodeId,
        manualNodePositions = positions,
        isManualSchematic = true,
        statusBanner = banner ?: it.statusBanner,
      )
    }
    dirtyForCloudAutoSave = true
    runSimulation()
  }

  private fun uniqueName(prefix: String, taken: Set<String>, startSeed: Int): String {
    var idx = startSeed
    var candidate = "${prefix}_$idx"
    while (candidate in taken) {
      idx++
      candidate = "${prefix}_$idx"
    }
    return candidate
  }

  fun clearStatusBanner() {
    _workbench.update { it.copy(statusBanner = null) }
  }

  fun loadPreset(template: RtlPresetTemplate) {
    val parsed = (VerilogHdlParser.parse(template.verilogCode) as? VerilogParseResult.Success)?.design
    val defaultSdc = SdcParser.generateDefaultSdc(
      moduleName = template.name,
      ports = parsed?.topModule?.ports.orEmpty(),
      clockPeriodNs = template.defaultClockNs,
    )
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeCloudDesignId = null,
          activeCloudCreatedAt = null,
          designName = template.name,
          description = template.description,
          verilogCode = template.verilogCode,
          sdcText = defaultSdc,
          targetLibrary = template.defaultLibrary,
          optimizationGoal = template.defaultGoal,
          clockPeriodNs = template.defaultClockNs,
          hasUnsavedChanges = false,
          isOfflineMode = isOfflineMode,
        )
      ).also(::persistDraft)
    }
    runSynthesis()
  }

  fun startBlankDesign() {
    val code = """
module custom_rtl_module (
  input wire clk,
  input wire rst,
  input wire [3:0] a,
  input wire [3:0] b,
  output reg [3:0] y
);
  always @(posedge clk) begin
    if (rst) begin
      y <= 4'd0;
    end else begin
      y <= a + b;
    end
  end
endmodule
    """.trimIndent()
    val parsed = (VerilogHdlParser.parse(code) as? VerilogParseResult.Success)?.design
    val sdc = SdcParser.generateDefaultSdc("custom_rtl_module", parsed?.topModule?.ports.orEmpty(), 2.5)
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeCloudDesignId = null,
          activeCloudCreatedAt = null,
          designName = "custom_rtl_module",
          description = "Custom synthesizable Verilog RTL module",
          verilogCode = code,
          sdcText = sdc,
          targetLibrary = TargetLibrary.ASAP7_7NM,
          optimizationGoal = OptimizationGoal.BALANCED,
          clockPeriodNs = 2.5,
          hasUnsavedChanges = true,
          isOfflineMode = isOfflineMode,
        )
      ).also(::persistDraft)
    }
    runSynthesis()
  }

  fun loadSavedDesign(design: RtlDesign) {
    val parsed = (VerilogHdlParser.parse(design.verilogCode) as? VerilogParseResult.Success)?.design
    val sdc = SdcParser.generateDefaultSdc(
      moduleName = design.name,
      ports = parsed?.topModule?.ports.orEmpty(),
      clockPeriodNs = design.clockPeriodNs,
    )
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeCloudDesignId = design.id,
          activeCloudCreatedAt = design.createdAt,
          designName = design.name,
          description = design.description,
          verilogCode = design.verilogCode,
          sdcText = sdc,
          targetLibrary = TargetLibrary.fromId(design.targetLibrary),
          optimizationGoal = OptimizationGoal.fromId(design.optimizationGoal),
          clockPeriodNs = design.clockPeriodNs,
          hasUnsavedChanges = false,
          isOfflineMode = isOfflineMode,
        )
      ).also { persistDraft(it, markDirtyForAutoSave = false) }
    }
    dirtyForCloudAutoSave = false
    runSynthesis()
  }

  fun runSynthesis() {
    val snapshot = _workbench.value
    viewModelScope.launch {
      _workbench.update { it.copy(isSynthesizing = true) }
      val (parseResult, result, simResult) = withContext(Dispatchers.Default) {
        val parsed = VerilogHdlParser.parse(snapshot.verilogCode, snapshot.selectedTopModule)
        val synth = RtlSynthesisEngine.synthesize(
          verilogSource = snapshot.verilogCode,
          library = snapshot.targetLibrary,
          goal = snapshot.optimizationGoal,
          clockPeriodNs = snapshot.clockPeriodNs,
          topModuleName = snapshot.selectedTopModule,
          sdcText = snapshot.sdcText,
        )
        val sim = (parsed as? VerilogParseResult.Success)?.design?.let { design ->
          RtlSimulator.simulate(
            design = design,
            cycleCount = snapshot.simCycleCount,
            patternOverrides = snapshot.stimulusPatterns,
            customValueOverrides = snapshot.customSimValues,
          )
        }
        Triple(parsed, synth, sim)
      }
      _workbench.update { state ->
        val firstSelected = when (result) {
          is SynthesisResult.Success -> result.report.criticalPath.firstOrNull()?.nodeId
          is SynthesisResult.Error -> null
        }
        state.copy(
          parsedDesign = (parseResult as? VerilogParseResult.Success)?.design,
          parseError = parseResult as? VerilogParseResult.Error,
          synthesisResult = result,
          selectedNodeId = firstSelected,
          manualNodePositions = emptyMap(),
          isManualSchematic = false,
          isSynthesizing = false,
          simulationResult = simResult ?: state.simulationResult,
        )
      }
    }
  }

  // ==================== RTL SIMULATION ====================

  fun runSimulation() {
    val snapshot = _workbench.value
    viewModelScope.launch {
      val sim = withContext(Dispatchers.Default) {
        val manualReport = (snapshot.synthesisResult as? SynthesisResult.Success)?.report
        when {
          snapshot.isManualSchematic && manualReport != null -> RtlSimulator.simulateManualNetlist(
            report = manualReport,
            cycleCount = snapshot.simCycleCount,
            patternOverrides = snapshot.stimulusPatterns,
            customValueOverrides = snapshot.customSimValues,
          )
          snapshot.parsedDesign != null -> RtlSimulator.simulate(
            design = snapshot.parsedDesign,
            cycleCount = snapshot.simCycleCount,
            patternOverrides = snapshot.stimulusPatterns,
            customValueOverrides = snapshot.customSimValues,
          )
          else -> {
            val parsed = VerilogHdlParser.parse(snapshot.verilogCode, snapshot.selectedTopModule)
            (parsed as? VerilogParseResult.Success)?.design?.let { design ->
              RtlSimulator.simulate(
                design = design,
                cycleCount = snapshot.simCycleCount,
                patternOverrides = snapshot.stimulusPatterns,
                customValueOverrides = snapshot.customSimValues,
              )
            }
          }
        }
      }
      if (sim != null) {
        _workbench.update { it.copy(simulationResult = sim) }
      } else {
        _workbench.update { it.copy(statusBanner = "Fix Verilog syntax errors before running RTL simulation.") }
      }
    }
  }

  fun setSimCycleCount(count: Int) {
    val clamped = count.coerceIn(4, 32)
    _workbench.update { it.copy(simCycleCount = clamped) }
    runSimulation()
  }

  fun cycleStimulusPattern(portName: String) {
    _workbench.update { state ->
      val current = state.stimulusPatterns[portName] ?: StimulusPattern.AUTO
      val next = current.next()
      state.copy(stimulusPatterns = state.stimulusPatterns + (portName to next))
    }
    runSimulation()
  }

  fun stepCustomSimCell(portName: String, cycle: Int, nextValue: Long, width: Int) {
    val masked = RtlSimulator.maskValue(nextValue, width)
    _workbench.update { state ->
      state.copy(customSimValues = state.customSimValues + ((portName to cycle) to masked))
    }
    runSimulation()
  }

  fun resetSimulationStimulus() {
    _workbench.update {
      it.copy(
        stimulusPatterns = emptyMap(),
        customSimValues = emptyMap(),
        statusBanner = "Reset simulation stimulus to automatic testbench vectors.",
      )
    }
    runSimulation()
  }

  // ==================== CLOUD & LOCAL PERSISTENCE ====================

  fun saveDesignToCloud() {
    val state = _workbench.value
    if (state.designName.isBlank() || state.verilogCode.isBlank()) {
      _workbench.update { it.copy(statusBanner = "Module name and Verilog source cannot be empty.") }
      return
    }
    val report = (state.synthesisResult as? SynthesisResult.Success)?.report
    val designPayload = RtlDesign(
      id = state.activeCloudDesignId ?: "",
      userId = currentUserId,
      name = state.designName.trim(),
      description = state.description.trim(),
      verilogCode = state.verilogCode,
      targetLibrary = state.targetLibrary.id,
      optimizationGoal = state.optimizationGoal.id,
      clockPeriodNs = state.clockPeriodNs,
      lastWnsNs = report?.wnsNs ?: 0.0,
      lastAreaUm2 = report?.totalAreaUm2 ?: 0.0,
      lastCellCount = (report?.cellCount ?: 0).toLong(),
      lastPowerMw = report?.totalPowerMw ?: 0.0,
      createdAt = state.activeCloudCreatedAt,
    )

    if (isOfflineMode || repository == null) {
      val savedId = localStore?.upsertLocalDesign(designPayload) ?: ""
      if (report != null) {
        localStore?.recordLocalRun(buildRunModel(savedId, state.designName.trim(), report))
      }
      _workbench.update {
        it.copy(
          activeCloudDesignId = savedId.ifBlank { it.activeCloudDesignId },
          activeCloudCreatedAt = it.activeCloudCreatedAt ?: Timestamp.now(),
          hasUnsavedChanges = false,
          statusBanner = "Saved '${state.designName}' locally on device.",
        )
      }
      return
    }

    viewModelScope.launch {
      _workbench.update { it.copy(isSaving = true) }
      val existingId = state.activeCloudDesignId
      val isCloudDoc = existingId != null && !existingId.startsWith("local_") && state.activeCloudCreatedAt != null
      val saveOutcome = if (isCloudDoc) {
        repository.updateDesign(designPayload).map { existingId!! }
      } else {
        repository.createDesign(designPayload.copy(id = ""))
      }

      saveOutcome.fold(
        onSuccess = { savedId ->
          if (report != null) {
            repository.recordSynthesisRun(buildRunModel(savedId, state.designName.trim(), report))
          }
          _workbench.update {
            it.copy(
              activeCloudDesignId = savedId,
              activeCloudCreatedAt = it.activeCloudCreatedAt ?: Timestamp.now(),
              isSaving = false,
              hasUnsavedChanges = false,
              autoSaveStatus = AutoSaveStatus.SAVED,
              lastAutoSavedLabel = formatClockTime(clockMillisProvider()),
              statusBanner = "Saved '${state.designName}' & synthesis snapshot to cloud.",
            )
          }
          lastAutoSavedSignature = _workbench.value.toSaveSignature()
          dirtyForCloudAutoSave = false
        },
        onFailure = { err ->
          _workbench.update {
            it.copy(
              isSaving = false,
              statusBanner = "Cloud save failed: ${err.localizedMessage ?: "Unknown error"}",
            )
          }
        },
      )
    }
  }

  fun saveRunSnapshotOnly() {
    val state = _workbench.value
    val report = (state.synthesisResult as? SynthesisResult.Success)?.report ?: run {
      _workbench.update { it.copy(statusBanner = "Run a valid synthesis before saving a PPA snapshot.") }
      return
    }
    val runPayload = buildRunModel(
      designId = state.activeCloudDesignId ?: "preset_${state.designName}",
      designName = state.designName.trim().ifBlank { report.moduleName },
      report = report,
    )

    if (isOfflineMode || repository == null) {
      localStore?.recordLocalRun(runPayload)
      _workbench.update {
        it.copy(statusBanner = "Saved PPA snapshot locally (${report.targetLibrary.shortName}).")
      }
      return
    }

    viewModelScope.launch {
      _workbench.update { it.copy(isSaving = true) }
      val outcome = repository.recordSynthesisRun(runPayload)
      outcome.fold(
        onSuccess = {
          _workbench.update {
            it.copy(
              isSaving = false,
              statusBanner = "Recorded PPA snapshot (${report.targetLibrary.shortName}).",
            )
          }
        },
        onFailure = { err ->
          _workbench.update {
            it.copy(
              isSaving = false,
              statusBanner = "Snapshot failed: ${err.localizedMessage ?: "Unknown error"}",
            )
          }
        },
      )
    }
  }

  fun syncLocalDesignsToCloud() {
    if (isOfflineMode || repository == null || localStore == null) return
    val pending = localStore.localDesigns.value
    if (pending.isEmpty()) return
    viewModelScope.launch {
      _workbench.update { it.copy(isSaving = true) }
      var synced = 0
      pending.forEach { design ->
        repository.createDesign(design.copy(id = "", userId = currentUserId, createdAt = null, updatedAt = null))
          .onSuccess {
            localStore.deleteLocalDesign(design.id)
            synced++
          }
      }
      _workbench.update {
        it.copy(
          isSaving = false,
          statusBanner = if (synced > 0) "Synced $synced local module(s) to cloud." else "No local modules could be synced.",
        )
      }
    }
  }

  fun deleteCloudDesign(designId: String) {
    if (isOfflineMode || repository == null || designId.startsWith("local_")) {
      localStore?.deleteLocalDesign(designId)
      _workbench.update { state ->
        val clearActive = state.activeCloudDesignId == designId
        state.copy(
          activeCloudDesignId = if (clearActive) null else state.activeCloudDesignId,
          activeCloudCreatedAt = if (clearActive) null else state.activeCloudCreatedAt,
          statusBanner = "Removed design from local workspace.",
        )
      }
      return
    }
    viewModelScope.launch {
      repository.deleteDesign(designId).fold(
        onSuccess = {
          _workbench.update { state ->
            val clearActive = state.activeCloudDesignId == designId
            state.copy(
              activeCloudDesignId = if (clearActive) null else state.activeCloudDesignId,
              activeCloudCreatedAt = if (clearActive) null else state.activeCloudCreatedAt,
              statusBanner = "Deleted design from cloud workspace.",
            )
          }
        },
        onFailure = { err ->
          _workbench.update { it.copy(statusBanner = "Delete failed: ${err.localizedMessage}") }
        },
      )
    }
  }

  fun deleteSynthesisRun(runId: String) {
    if (isOfflineMode || repository == null || runId.startsWith("run_")) {
      localStore?.deleteLocalRun(runId)
      _workbench.update { it.copy(statusBanner = "Removed local synthesis snapshot.") }
      return
    }
    viewModelScope.launch {
      repository.deleteSynthesisRun(runId).fold(
        onSuccess = {
          _workbench.update { it.copy(statusBanner = "Removed synthesis run snapshot.") }
        },
        onFailure = { err ->
          _workbench.update { it.copy(statusBanner = "Remove failed: ${err.localizedMessage}") }
        },
      )
    }
  }

  private fun buildRunModel(designId: String, designName: String, report: SynthesisReport): SynthesisRun {
    return SynthesisRun(
      userId = currentUserId,
      designId = designId,
      designName = designName,
      targetLibrary = report.targetLibrary.id,
      optimizationGoal = report.optimizationGoal.id,
      clockPeriodNs = report.clockPeriodNs,
      fmaxMhz = report.fmaxMhz,
      wnsNs = report.wnsNs,
      tnsNs = report.tnsNs,
      areaUm2 = report.totalAreaUm2,
      cellCount = report.cellCount.toLong(),
      lutCount = report.combCellCount.toLong(),
      dffCount = report.seqCellCount.toLong(),
      powerMw = report.totalPowerMw,
      criticalPathSummary = report.criticalPathSummaryText(),
      netlistVerilog = report.netlistVerilog,
    )
  }

  private companion object {
    const val TAG = "RtlSynthVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
    const val DEFAULT_AUTOSAVE_INTERVAL_MS = 15_000L
    const val DEFAULT_AUTOSAVE_DEBOUNCE_MS = 2_500L
  }
}
