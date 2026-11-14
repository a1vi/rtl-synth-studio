package com.example.ui.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalDraftSnapshot
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.AppSettings
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import android.content.Context
import com.example.domain.synth.DeviceResourceSnapshot
import com.example.domain.synth.FlowStageId
import com.example.domain.synth.FlowStageState
import com.example.domain.synth.FlowStageStatus
import com.example.domain.synth.GateNode
import com.example.domain.synth.GateType
import com.example.domain.synth.NetlistLayoutEngine
import com.example.domain.synth.ParsedVerilogDesign
import com.example.domain.synth.PdkCatalogManager
import com.example.domain.synth.PdkPackageStatus
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.PortDirection
import com.example.domain.synth.PresetRtlDesigns
import com.example.domain.synth.RtlPort
import com.example.domain.synth.RtlPresetTemplate
import com.example.domain.synth.RtlSimulator
import com.example.domain.synth.RtlSynthesisEngine
import com.example.domain.synth.RtlToGdsFlowEngine
import com.example.domain.synth.SdcParser
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.StimulusPattern
import com.example.domain.synth.SynthesisReport
import com.example.domain.synth.SynthesisResult
import com.example.domain.synth.VerilogParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.UUID

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

enum class AutoSaveStatus {
  IDLE,
  SAVING,
  SAVED,
}

data class WorkbenchUiState(
  val activeDesignId: String? = null,
  val designName: String = PresetRtlDesigns.templates.first().name,
  val description: String = PresetRtlDesigns.templates.first().description,
  val verilogCode: String = PresetRtlDesigns.templates.first().verilogCode,
  val targetLibrary: TargetLibrary = PresetRtlDesigns.templates.first().defaultLibrary,
  val optimizationGoal: OptimizationGoal = PresetRtlDesigns.templates.first().defaultGoal,
  val synthesisEngine: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
  val clockPeriodNs: Double = PresetRtlDesigns.templates.first().defaultClockNs,
  val coreUtilizationPercent: Int = 60,
  val sdcText: String = "",
  val files: List<RtlFileEntry> = emptyList(),
  val openTabIds: List<String> = emptyList(),
  val activeFileId: String = "file_rtl_top",
  val primaryLanguage: RtlLanguage = PresetRtlDesigns.templates.first().language,
  val selectedTopModule: String? = null,
  val parsedDesign: ParsedVerilogDesign? = null,
  val parseError: VerilogParseResult.Error? = null,
  val synthesisResult: SynthesisResult? = null,
  val synthesisProgress: Float = 0f,
  val synthesisPhaseLabel: String = "Ready — press Run to synthesize",
  val lastRunDurationMs: Long = 0L,
  val flowStages: List<FlowStageState> = RtlToGdsFlowEngine.initialStages(),
  val flowLogLines: List<String> = emptyList(),
  val physicalLayout: PhysicalLayoutSnapshot? = null,
  val pdkPackageStatus: PdkPackageStatus? = null,
  val deviceResources: DeviceResourceSnapshot? = null,
  val canResumeFlow: Boolean = false,
  val selectedNodeId: String? = null,
  val manualNodePositions: Map<String, Offset> = emptyMap(),
  val isManualSchematic: Boolean = false,
  val isSynthesizing: Boolean = false,
  val isSaving: Boolean = false,
  val statusBanner: String? = null,
  val hasUnsavedChanges: Boolean = false,
  val autoSaveStatus: AutoSaveStatus = AutoSaveStatus.IDLE,
  val lastAutoSavedLabel: String? = null,
  val simulationResult: SimulationResult? = null,
  val simCycleCount: Int = 16,
  val stimulusPatterns: Map<String, StimulusPattern> = emptyMap(),
  val customSimValues: Map<Pair<String, Int>, Long> = emptyMap(),
  val selectedWaveformSignals: Set<String> = emptySet(),
) {
  val activeFile: RtlFileEntry?
    get() = files.firstOrNull { it.id == activeFileId } ?: files.firstOrNull()
}

private data class ProjectSaveSignature(
  val activeDesignId: String,
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
  private val localStore: LocalWorkspaceStore? = null,
  private val autoSaveIntervalMs: Long = DEFAULT_AUTOSAVE_INTERVAL_MS,
  private val autoSaveDebounceMs: Long = DEFAULT_AUTOSAVE_DEBOUNCE_MS,
  private val clockMillisProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

  val savedDesigns: StateFlow<UiState<List<RtlDesign>>> =
    (localStore?.localDesigns ?: flowOf(emptyList()))
      .map<List<RtlDesign>, UiState<List<RtlDesign>>> { UiState.Success(it) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Success(localStore?.localDesigns?.value.orEmpty()),
      )

  val synthesisRuns: StateFlow<UiState<List<SynthesisRun>>> =
    (localStore?.localRuns ?: flowOf(emptyList()))
      .map<List<SynthesisRun>, UiState<List<SynthesisRun>>> { UiState.Success(it) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Success(localStore?.localRuns?.value.orEmpty()),
      )

  private val _settings = MutableStateFlow(localStore?.loadSettings() ?: AppSettings())
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()

  private val autoSaveMutex = Mutex()
  private var dirtyForAutoSave: Boolean = false
  private var lastAutoSavedSignature: ProjectSaveSignature? = null
  private var activeFlowJob: Job? = null

  private val _workbench = MutableStateFlow(createInitialState())
  val workbench: StateFlow<WorkbenchUiState> = _workbench.asStateFlow()

  init {
    refreshLiveParse()
    startLocalAutoSaveLoops()
  }

  private fun createInitialState(): WorkbenchUiState {
    val loadedSettings = _settings.value
    val draft = localStore?.loadActiveDraft()
    return if (draft != null) {
      val restoredLabel = if (draft.savedAtEpochMs > 0L) formatClockTime(draft.savedAtEpochMs) else null
      val lang = RtlProjectHelpers.detectPrimaryRtlLanguage(draft.verilogCode)
      val files = if (draft.files.isNotEmpty()) {
        draft.files
      } else {
        RtlProjectHelpers.buildDefaultFiles(
          designName = draft.designName,
          rtlCode = draft.verilogCode,
          language = lang,
          sdcText = draft.sdcText,
        )
      }
      val activeId = draft.activeFileId?.takeIf { id -> files.any { it.id == id } } ?: files.first().id
      WorkbenchUiState(
        activeDesignId = draft.activeDesignId,
        designName = draft.designName,
        description = draft.description,
        verilogCode = draft.verilogCode,
        sdcText = draft.sdcText,
        files = files,
        openTabIds = files.map { it.id },
        activeFileId = activeId,
        primaryLanguage = lang,
        targetLibrary = draft.targetLibrary,
        optimizationGoal = draft.optimizationGoal,
        synthesisEngine = loadedSettings.defaultEngine,
        clockPeriodNs = draft.clockPeriodNs,
        coreUtilizationPercent = draft.coreUtilizationPercent.coerceIn(30, 85),
        autoSaveStatus = AutoSaveStatus.SAVED,
        lastAutoSavedLabel = restoredLabel,
      )
    } else {
      val first = PresetRtlDesigns.templates.first()
      val parsed = (RtlProjectHelpers.parseLanguageAware(first.verilogCode, null) as? VerilogParseResult.Success)?.design
      val initialSdc = SdcParser.generateDefaultSdc(
        moduleName = first.name,
        ports = parsed?.topModule?.ports.orEmpty(),
        clockPeriodNs = first.defaultClockNs,
      )
      val files = RtlProjectHelpers.buildFilesFromPreset(first, initialSdc)
      WorkbenchUiState(
        designName = first.name,
        description = first.description,
        verilogCode = first.verilogCode,
        sdcText = initialSdc,
        files = files,
        openTabIds = files.map { it.id },
        activeFileId = files.first().id,
        primaryLanguage = first.language,
        targetLibrary = first.defaultLibrary,
        optimizationGoal = first.defaultGoal,
        synthesisEngine = loadedSettings.defaultEngine,
        clockPeriodNs = first.defaultClockNs,
        coreUtilizationPercent = 60,
        autoSaveStatus = AutoSaveStatus.IDLE,
      )
    }
  }

  private fun persistDraft(state: WorkbenchUiState, markDirty: Boolean = true) {
    if (markDirty) {
      dirtyForAutoSave = true
    }
    localStore?.saveActiveDraft(
      LocalDraftSnapshot(
        activeDesignId = state.activeDesignId,
        designName = state.designName,
        description = state.description,
        verilogCode = state.verilogCode,
        sdcText = state.sdcText,
        targetLibrary = state.targetLibrary,
        optimizationGoal = state.optimizationGoal,
        clockPeriodNs = state.clockPeriodNs,
        savedAtEpochMs = clockMillisProvider(),
        files = state.files,
        activeFileId = state.activeFileId,
        coreUtilizationPercent = state.coreUtilizationPercent,
      )
    )
  }

  private fun WorkbenchUiState.toSaveSignature(): ProjectSaveSignature {
    val report = (synthesisResult as? SynthesisResult.Success)?.report
    return ProjectSaveSignature(
      activeDesignId = activeDesignId.orEmpty(),
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

  private fun startLocalAutoSaveLoops() {
    if (localStore == null) return

    viewModelScope.launch {
      while (isActive) {
        val intervalMs = if (autoSaveIntervalMs != DEFAULT_AUTOSAVE_INTERVAL_MS) {
          autoSaveIntervalMs
        } else {
          (_settings.value.autoSaveIntervalSec * 1000L).coerceAtLeast(5_000L)
        }
        delay(intervalMs)
        if (_settings.value.autoSaveIntervalSec > 0 || autoSaveIntervalMs != DEFAULT_AUTOSAVE_INTERVAL_MS) {
          performAutoSaveIfDirty()
        }
      }
    }

    viewModelScope.launch {
      _workbench
        .map { it.toSaveSignature() }
        .distinctUntilChanged()
        .drop(1)
        .debounce(autoSaveDebounceMs)
        .collect {
          if (_settings.value.autoSaveIntervalSec > 0 || autoSaveIntervalMs != DEFAULT_AUTOSAVE_INTERVAL_MS) {
            performAutoSaveIfDirty()
          }
        }
    }
  }

  fun flushAutoSave() {
    val state = _workbench.value
    persistDraft(state, markDirty = false)
    if (localStore == null) return
    performAutoSaveSync(state)
  }

  suspend fun performAutoSaveIfDirty() {
    if (localStore == null) return
    autoSaveMutex.withLock {
      val state = _workbench.value
      val sig = state.toSaveSignature()
      if (!dirtyForAutoSave && sig == lastAutoSavedSignature) return
      if (!dirtyForAutoSave) return
      performAutoSaveSync(state)
    }
  }

  private fun performAutoSaveSync(state: WorkbenchUiState) {
    val store = localStore ?: return
    val sig = state.toSaveSignature()
    if (!dirtyForAutoSave && sig == lastAutoSavedSignature) return

    _workbench.update { it.copy(autoSaveStatus = AutoSaveStatus.SAVING) }
    val nowMs = clockMillisProvider()
    store.saveActiveDraft(
      LocalDraftSnapshot(
        activeDesignId = state.activeDesignId,
        designName = state.designName,
        description = state.description,
        verilogCode = state.verilogCode,
        sdcText = state.sdcText,
        targetLibrary = state.targetLibrary,
        optimizationGoal = state.optimizationGoal,
        clockPeriodNs = state.clockPeriodNs,
        savedAtEpochMs = nowMs,
        files = state.files,
        activeFileId = state.activeFileId,
        coreUtilizationPercent = state.coreUtilizationPercent,
      )
    )

    val report = (state.synthesisResult as? SynthesisResult.Success)?.report
    val activeId = state.activeDesignId
    var syncedActive = false
    if (!activeId.isNullOrBlank() && state.designName.isNotBlank() && state.verilogCode.isNotBlank()) {
      store.upsertLocalDesign(
        RtlDesign(
          id = activeId,
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
          language = state.primaryLanguage.name,
          files = state.files,
        )
      )
      syncedActive = true
    }

    val currentSigAfter = _workbench.value.toSaveSignature()
    if (currentSigAfter == sig) {
      dirtyForAutoSave = false
    }
    lastAutoSavedSignature = sig
    val savedTime = formatClockTime(nowMs)
    _workbench.update {
      it.copy(
        autoSaveStatus = AutoSaveStatus.SAVED,
        lastAutoSavedLabel = savedTime,
        hasUnsavedChanges = if (syncedActive && currentSigAfter == sig) false else it.hasUnsavedChanges,
      )
    }
  }

  private fun formatClockTime(epochMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMs))
  }

  override fun onCleared() {
    flushAutoSave()
    super.onCleared()
  }

  private fun applyParseToState(state: WorkbenchUiState): WorkbenchUiState {
    val combinedRtl = RtlProjectHelpers.combineProjectRtlSources(state.files, state.verilogCode)
    return when (val parsed = RtlProjectHelpers.parseLanguageAware(combinedRtl, state.selectedTopModule)) {
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

  // ==================== MULTI-FILE & EDITOR ACTIONS ====================

  fun updateActiveFileContent(content: String) {
    val current = _workbench.value
    val active = current.activeFile
    if (active == null) {
      updateVerilogCode(content)
      return
    }
    when (active.language) {
      RtlLanguage.SDC -> {
        val updatedFiles = current.files.map { if (it.id == active.id) it.copy(content = content) else it }
        val parsedSdc = SdcParser.parse(content, current.clockPeriodNs)
        val nextPeriod = parsedSdc.primaryClockPeriodNs?.coerceIn(0.4, 50.0) ?: current.clockPeriodNs
        _workbench.update {
          it.copy(
            files = updatedFiles,
            sdcText = content,
            clockPeriodNs = nextPeriod,
            hasUnsavedChanges = true,
          ).also(::persistDraft)
        }
      }
      RtlLanguage.TESTBENCH -> {
        val updatedFiles = current.files.map { if (it.id == active.id) it.copy(content = content) else it }
        _workbench.update {
          it.copy(
            files = updatedFiles,
            hasUnsavedChanges = true,
          ).also(::persistDraft)
        }
      }
      RtlLanguage.VERILOG, RtlLanguage.SYSTEM_VERILOG, RtlLanguage.VHDL -> {
        val updatedFiles = current.files.map { if (it.id == active.id) it.copy(content = content) else it }
        val isPrimary = active.id == "file_rtl_top" || current.files.none { it.id == "file_rtl_top" }
        val primaryCode = if (isPrimary) content else current.verilogCode
        val detectedLang = if (isPrimary) {
          RtlProjectHelpers.detectPrimaryRtlLanguage(content, active.name)
        } else {
          current.primaryLanguage
        }
        _workbench.update { state ->
          applyParseToState(
            state.copy(
              files = updatedFiles,
              verilogCode = primaryCode,
              primaryLanguage = detectedLang,
              hasUnsavedChanges = true,
            )
          ).also(::persistDraft)
        }
      }
    }
  }

  fun updateVerilogCode(code: String) {
    _workbench.update { state ->
      val updatedFiles = if (state.files.isEmpty()) {
        state.files
      } else {
        val targetId = state.files.firstOrNull { it.id == "file_rtl_top" }?.id
          ?: state.files.firstOrNull {
            it.language == RtlLanguage.VERILOG ||
              it.language == RtlLanguage.SYSTEM_VERILOG ||
              it.language == RtlLanguage.VHDL
          }?.id
        if (targetId != null) {
          state.files.map { if (it.id == targetId) it.copy(content = code) else it }
        } else {
          state.files
        }
      }
      val lang = RtlProjectHelpers.detectPrimaryRtlLanguage(code)
      applyParseToState(
        state.copy(
          verilogCode = code,
          files = updatedFiles,
          primaryLanguage = lang,
          hasUnsavedChanges = true,
        )
      ).also(::persistDraft)
    }
  }

  fun selectFileTab(fileId: String) {
    _workbench.update { state ->
      if (state.files.none { it.id == fileId }) return@update state
      val nextTabs = if (fileId in state.openTabIds) state.openTabIds else state.openTabIds + fileId
      state.copy(activeFileId = fileId, openTabIds = nextTabs)
    }
  }

  fun closeFileTab(fileId: String) {
    _workbench.update { state ->
      if (state.openTabIds.size <= 1) return@update state
      val remaining = state.openTabIds.filterNot { it == fileId }
      val nextActive = if (state.activeFileId == fileId) remaining.last() else state.activeFileId
      state.copy(openTabIds = remaining, activeFileId = nextActive)
    }
  }

  fun addProjectFile(fileName: String, language: RtlLanguage) {
    val cleanName = fileName.trim().ifBlank { "module_${_workbench.value.files.size + 1}.${language.extension}" }
    val normalizedName = if ('.' in cleanName) cleanName else "$cleanName.${language.extension}"
    val modBase = normalizedName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
    val templateContent = when (language) {
      RtlLanguage.SYSTEM_VERILOG -> "module $modBase (\n  input logic clk,\n  input logic a,\n  output logic y\n);\n  assign y = a;\nendmodule"
      RtlLanguage.VHDL -> "library ieee;\nuse ieee.std_logic_1164.all;\n\nentity $modBase is\n  port (\n    a : in  std_logic;\n    y : out std_logic\n  );\nend entity $modBase;\n\narchitecture rtl of $modBase is\nbegin\n  y <= a;\nend architecture rtl;"
      RtlLanguage.SDC -> "create_clock -name clk -period ${_workbench.value.clockPeriodNs} [get_ports clk]\n"
      RtlLanguage.TESTBENCH -> RtlProjectHelpers.defaultTestbenchFor(_workbench.value.designName)
      RtlLanguage.VERILOG -> "module $modBase (\n  input wire a,\n  output wire y\n);\n  assign y = a;\nendmodule"
    }
    val entry = RtlFileEntry(
      id = "file_${UUID.randomUUID().toString().take(8)}",
      name = normalizedName,
      language = language,
      content = templateContent,
    )
    _workbench.update { state ->
      val updatedFiles = state.files + entry
      state.copy(
        files = updatedFiles,
        openTabIds = state.openTabIds + entry.id,
        activeFileId = entry.id,
        hasUnsavedChanges = true,
        statusBanner = "Added $normalizedName to project.",
      ).also(::persistDraft)
    }
  }

  fun deleteProjectFile(fileId: String) {
    _workbench.update { state ->
      if (state.files.size <= 1 || fileId == "file_rtl_top") return@update state
      val updatedFiles = state.files.filterNot { it.id == fileId }
      val updatedTabs = state.openTabIds.filterNot { it == fileId }.ifEmpty { listOf(updatedFiles.first().id) }
      val nextActive = if (state.activeFileId == fileId) updatedTabs.first() else state.activeFileId
      applyParseToState(
        state.copy(
          files = updatedFiles,
          openTabIds = updatedTabs,
          activeFileId = nextActive,
          hasUnsavedChanges = true,
        )
      ).also(::persistDraft)
    }
  }

  fun selectTopModule(moduleName: String) {
    _workbench.update { state ->
      applyParseToState(state.copy(selectedTopModule = moduleName)).also {
        persistDraft(it)
      }
    }
    if (_workbench.value.synthesisResult != null) {
      runSynthesis()
    }
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

  fun updateDesignName(name: String) {
    _workbench.update {
      it.copy(
        designName = name.take(80),
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
  }

  fun updateCoreUtilizationPercent(percent: Int) {
    val clamped = percent.coerceIn(30, 85)
    _workbench.update {
      it.copy(
        coreUtilizationPercent = clamped,
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
  }

  fun refreshDeviceAndPdkTelemetry(context: Context) {
    val targetLib = _workbench.value.targetLibrary
    viewModelScope.launch(Dispatchers.IO) {
      val pdk = runCatching { PdkCatalogManager.ensureAndVerifyPdk(context, targetLib) }.getOrNull()
      val res = runCatching { PdkCatalogManager.queryDeviceResources(context) }.getOrNull()
      _workbench.update {
        it.copy(
          pdkPackageStatus = pdk ?: it.pdkPackageStatus,
          deviceResources = res ?: it.deviceResources,
        )
      }
    }
  }

  fun selectTargetLibrary(library: TargetLibrary) {
    _workbench.update { it.copy(targetLibrary = library, hasUnsavedChanges = true).also(::persistDraft) }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else if (state.synthesisResult != null) {
      runSynthesis()
    }
  }

  fun selectOptimizationGoal(goal: OptimizationGoal) {
    _workbench.update { it.copy(optimizationGoal = goal, hasUnsavedChanges = true).also(::persistDraft) }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else if (state.synthesisResult != null) {
      runSynthesis()
    }
  }

  fun selectSynthesisEngine(engine: SynthesisEngineProfile) {
    _workbench.update { it.copy(synthesisEngine = engine, hasUnsavedChanges = true).also(::persistDraft) }
    if (_workbench.value.synthesisResult != null) {
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
      val updatedFiles = state.files.map { f ->
        if (f.language == RtlLanguage.SDC) f.copy(content = syncedSdc) else f
      }
      state.copy(
        clockPeriodNs = clamped,
        sdcText = syncedSdc,
        files = updatedFiles,
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else if (state.synthesisResult != null) {
      runSynthesis()
    }
  }

  fun updateSdcConstraints(sdc: String) {
    val parsedSdc = SdcParser.parse(sdc, _workbench.value.clockPeriodNs)
    val nextPeriod = parsedSdc.primaryClockPeriodNs?.coerceIn(0.4, 50.0) ?: _workbench.value.clockPeriodNs
    _workbench.update { state ->
      val updatedFiles = state.files.map { f ->
        if (f.language == RtlLanguage.SDC) f.copy(content = sdc) else f
      }
      state.copy(
        sdcText = sdc,
        files = updatedFiles,
        clockPeriodNs = nextPeriod,
        hasUnsavedChanges = true,
      ).also(::persistDraft)
    }
    val state = _workbench.value
    val manualReport = (state.synthesisResult as? SynthesisResult.Success)?.report
    if (state.isManualSchematic && manualReport != null) {
      recomputeManualReport(manualReport, state.manualNodePositions, state.selectedNodeId)
    } else if (state.synthesisResult != null) {
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
    val lang = RtlLanguage.fromFileName(cleanName)
    if (lang == RtlLanguage.SDC) {
      updateSdcConstraints(content)
      _workbench.update { it.copy(statusBanner = "Imported SDC constraints from $cleanName.") }
      return
    }
    if (lang == RtlLanguage.TESTBENCH) {
      val tbEntry = RtlFileEntry(
        id = "file_tb",
        name = cleanName,
        language = RtlLanguage.TESTBENCH,
        content = content,
      )
      _workbench.update { state ->
        val filtered = state.files.filterNot { it.id == "file_tb" } + tbEntry
        state.copy(
          files = filtered,
          openTabIds = (state.openTabIds + tbEntry.id).distinct(),
          activeFileId = tbEntry.id,
          statusBanner = "Imported testbench $cleanName.",
        ).also(::persistDraft)
      }
      return
    }

    val baseName = cleanName
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .substringBeforeLast('.')
      .ifBlank { "imported_module" }

    val parsed = RtlProjectHelpers.parseLanguageAware(content, null)
    val resolvedName = (parsed as? VerilogParseResult.Success)?.design?.topModule?.name ?: baseName
    val resolvedSdc = (parsed as? VerilogParseResult.Success)?.let { p ->
      SdcParser.generateDefaultSdc(resolvedName, p.design.topModule.ports, _workbench.value.clockPeriodNs)
    } ?: _workbench.value.sdcText
    val detectedLang = RtlProjectHelpers.detectPrimaryRtlLanguage(content, cleanName)
    val files = RtlProjectHelpers.buildDefaultFiles(
      designName = resolvedName,
      rtlCode = content,
      language = detectedLang,
      sdcText = resolvedSdc,
    )

    _workbench.update { state ->
      applyParseToState(
        state.copy(
          activeDesignId = null,
          designName = resolvedName,
          description = "Imported from $cleanName",
          verilogCode = content,
          sdcText = resolvedSdc,
          files = files,
          openTabIds = files.map { it.id },
          activeFileId = files.first().id,
          primaryLanguage = detectedLang,
          selectedTopModule = null,
          synthesisResult = null,
          synthesisProgress = 0f,
          synthesisPhaseLabel = "Ready — press Run to synthesize",
          flowStages = RtlToGdsFlowEngine.initialStages(),
          flowLogLines = emptyList(),
          physicalLayout = null,
          simulationResult = null,
          canResumeFlow = false,
          hasUnsavedChanges = true,
          stimulusPatterns = emptyMap(),
          customSimValues = emptyMap(),
          selectedWaveformSignals = emptySet(),
          statusBanner = "Imported $cleanName into IDE. Press Synthesize to run flow.",
        )
      ).also(::persistDraft)
    }
  }

  // ==================== SCHEMATIC ACTIONS ====================

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

  fun addPrimitiveToSchematic(gateType: GateType, worldPosition: Offset? = null) {
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
      Offset(x = it.x.coerceAtLeast(16f), y = it.y.coerceAtLeast(16f))
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
    dirtyForAutoSave = true
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

  // ==================== PROJECT & PRESET LOADING ====================

  fun loadPreset(template: RtlPresetTemplate) {
    val parsed = (RtlProjectHelpers.parseLanguageAware(template.verilogCode, null) as? VerilogParseResult.Success)?.design
    val defaultSdc = SdcParser.generateDefaultSdc(
      moduleName = template.name,
      ports = parsed?.topModule?.ports.orEmpty(),
      clockPeriodNs = template.defaultClockNs,
    )
    val files = RtlProjectHelpers.buildFilesFromPreset(template, defaultSdc)
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeDesignId = null,
          designName = template.name,
          description = template.description,
          verilogCode = template.verilogCode,
          sdcText = defaultSdc,
          files = files,
          openTabIds = files.map { f -> f.id },
          activeFileId = files.first().id,
          primaryLanguage = template.language,
          targetLibrary = template.defaultLibrary,
          optimizationGoal = template.defaultGoal,
          synthesisEngine = _settings.value.defaultEngine,
          clockPeriodNs = template.defaultClockNs,
          hasUnsavedChanges = false,
        )
      ).also(::persistDraft)
    }
  }

  fun startBlankDesign(
    projectName: String = "custom_rtl_module",
    language: RtlLanguage = RtlLanguage.VERILOG,
    library: TargetLibrary = _settings.value.defaultLibrary,
  ) {
    val cleanMod = projectName.trim().ifBlank { "custom_rtl_module" }
      .replace(Regex("[^a-zA-Z0-9_]"), "_")
    val code = when (language) {
      RtlLanguage.VHDL -> """
        library ieee;
        use ieee.std_logic_1164.all;

        entity $cleanMod is
          port (
            clk : in  std_logic;
            rst : in  std_logic;
            a   : in  std_logic_vector(3 downto 0);
            b   : in  std_logic_vector(3 downto 0);
            y   : out std_logic_vector(3 downto 0)
          );
        end entity $cleanMod;

        architecture rtl of $cleanMod is
        begin
          process(clk)
          begin
            if rising_edge(clk) then
              if rst = '1' then
                y <= "0000";
              else
                y <= a + b;
              end if;
            end if;
          end process;
        end architecture rtl;
      """.trimIndent()
      RtlLanguage.SYSTEM_VERILOG -> """
        module $cleanMod (
          input  logic       clk,
          input  logic       rst,
          input  logic [3:0] a,
          input  logic [3:0] b,
          output logic [3:0] y
        );
          always_ff @(posedge clk) begin
            if (rst) begin
              y <= 4'd0;
            end else begin
              y <= a + b;
            end
          end
        endmodule
      """.trimIndent()
      else -> """
        module $cleanMod (
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
    }
    val parsed = (RtlProjectHelpers.parseLanguageAware(code, null) as? VerilogParseResult.Success)?.design
    val sdc = SdcParser.generateDefaultSdc(cleanMod, parsed?.topModule?.ports.orEmpty(), 2.5)
    val files = RtlProjectHelpers.buildDefaultFiles(cleanMod, code, language, sdc)
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeDesignId = null,
          designName = cleanMod,
          description = "Custom synthesizable ${language.displayName} RTL project",
          verilogCode = code,
          sdcText = sdc,
          files = files,
          openTabIds = files.map { f -> f.id },
          activeFileId = files.first().id,
          primaryLanguage = language,
          targetLibrary = library,
          optimizationGoal = OptimizationGoal.BALANCED,
          synthesisEngine = _settings.value.defaultEngine,
          clockPeriodNs = 2.5,
          hasUnsavedChanges = true,
        )
      ).also(::persistDraft)
    }
  }

  fun loadSavedDesign(design: RtlDesign) {
    val parsed = (RtlProjectHelpers.parseLanguageAware(design.verilogCode, null) as? VerilogParseResult.Success)?.design
    val lang = RtlLanguage.fromId(design.language)
    val sdc = RtlProjectHelpers.findSdcContent(
      files = design.files,
      fallbackSdc = "",
      moduleName = design.name,
      clockPeriodNs = design.clockPeriodNs,
    ).ifBlank {
      SdcParser.generateDefaultSdc(
        moduleName = design.name,
        ports = parsed?.topModule?.ports.orEmpty(),
        clockPeriodNs = design.clockPeriodNs,
      )
    }
    val files = if (design.files.isNotEmpty()) {
      design.files
    } else {
      RtlProjectHelpers.buildDefaultFiles(design.name, design.verilogCode, lang, sdc)
    }
    _workbench.update {
      applyParseToState(
        WorkbenchUiState(
          activeDesignId = design.id,
          designName = design.name,
          description = design.description,
          verilogCode = design.verilogCode,
          sdcText = sdc,
          files = files,
          openTabIds = files.map { f -> f.id },
          activeFileId = files.first().id,
          primaryLanguage = lang,
          targetLibrary = TargetLibrary.fromId(design.targetLibrary),
          optimizationGoal = OptimizationGoal.fromId(design.optimizationGoal),
          synthesisEngine = _settings.value.defaultEngine,
          clockPeriodNs = design.clockPeriodNs,
          hasUnsavedChanges = false,
        )
      ).also { updated -> persistDraft(updated, markDirty = false) }
    }
    dirtyForAutoSave = false
  }

  // ==================== SYNTHESIS & RTL-TO-GDSII EXECUTION ====================

  fun cancelRtlToGdsFlow() {
    activeFlowJob?.cancel()
    activeFlowJob = null
    _workbench.update { state ->
      val updatedStages = state.flowStages.map { st ->
        if (st.status == FlowStageStatus.RUNNING) {
          st.copy(status = FlowStageStatus.CANCELED, headlineMetric = "Paused by user")
        } else {
          st
        }
      }
      state.copy(
        isSynthesizing = false,
        canResumeFlow = updatedStages.any { it.status != FlowStageStatus.PASSED },
        flowStages = updatedStages,
        flowLogLines = state.flowLogLines + "[WARN] RTL-to-GDSII flow canceled by user. Press Resume to continue.",
        synthesisPhaseLabel = "Flow Canceled (Resumable)",
      )
    }
  }

  fun resumeRtlToGdsFlow() {
    val stages = _workbench.value.flowStages
    val resumeIdx = stages.indexOfFirst { it.status != FlowStageStatus.PASSED }
      .takeIf { it >= 0 } ?: 0
    executeRtlToGdsFlow(startFromStageIndex = resumeIdx)
  }

  fun runSynthesis() {
    executeRtlToGdsFlow(startFromStageIndex = 0)
  }

  private fun executeRtlToGdsFlow(startFromStageIndex: Int) {
    activeFlowJob?.cancel()
    val snapshot = _workbench.value
    val existingStages = if (startFromStageIndex > 0) snapshot.flowStages else RtlToGdsFlowEngine.initialStages()
    val existingReport = if (startFromStageIndex > 0) {
      (snapshot.synthesisResult as? SynthesisResult.Success)?.report
    } else {
      null
    }

    activeFlowJob = viewModelScope.launch {
      _workbench.update {
        it.copy(
          isSynthesizing = true,
          canResumeFlow = false,
          synthesisProgress = (startFromStageIndex.toFloat() / FlowStageId.entries.size).coerceIn(0.05f, 0.95f),
          synthesisPhaseLabel = if (startFromStageIndex == 0) {
            "Elaborating RTL & Starting Physical Flow..."
          } else {
            "Resuming from Stage ${startFromStageIndex + 1}/7..."
          },
          flowStages = existingStages,
        )
      }
      val combinedRtl = RtlProjectHelpers.combineProjectRtlSources(snapshot.files, snapshot.verilogCode)
      try {
        val (parseResult, flowEvent, simResult) = withContext(Dispatchers.Default) {
          val parsed = RtlProjectHelpers.parseLanguageAware(combinedRtl, snapshot.selectedTopModule)
          val finalEvent = RtlToGdsFlowEngine.runFlow(
            rtlSource = combinedRtl,
            designName = snapshot.designName.ifBlank { snapshot.selectedTopModule ?: "rtl_top" },
            library = snapshot.targetLibrary,
            goal = snapshot.optimizationGoal,
            engineProfile = snapshot.synthesisEngine,
            clockPeriodNs = snapshot.clockPeriodNs,
            coreUtilizationPercent = snapshot.coreUtilizationPercent,
            sdcText = snapshot.sdcText,
            topModuleOverride = snapshot.selectedTopModule,
            existingStages = existingStages,
            existingReport = existingReport,
            startFromStageIndex = startFromStageIndex,
            stepDelayMs = 0L,
            onProgress = { progressEvent ->
              val passedCount = progressEvent.stageStates.count { it.status == FlowStageStatus.PASSED }
              val runningStage = progressEvent.stageStates.firstOrNull { it.status == FlowStageStatus.RUNNING }
              _workbench.update { current ->
                current.copy(
                  flowStages = progressEvent.stageStates,
                  flowLogLines = progressEvent.logLines,
                  synthesisProgress = (passedCount.toFloat() / FlowStageId.entries.size).coerceIn(0.08f, 0.96f),
                  synthesisPhaseLabel = runningStage?.let {
                    "Stage ${it.id.order}/7: ${it.id.title} (${it.headlineMetric})"
                  } ?: current.synthesisPhaseLabel,
                )
              }
            },
          )
          val tbSource = snapshot.files.firstOrNull { it.language == RtlLanguage.TESTBENCH }?.content.orEmpty()
          val tbOverrides = if (snapshot.customSimValues.isEmpty() && tbSource.isNotBlank()) {
            RtlSimulator.parseTestbenchOverrides(tbSource, snapshot.simCycleCount)
          } else {
            snapshot.customSimValues
          }
          val sim = (parsed as? VerilogParseResult.Success)?.design?.let { design ->
            RtlSimulator.simulate(
              design = design,
              cycleCount = snapshot.simCycleCount,
              patternOverrides = snapshot.stimulusPatterns,
              customValueOverrides = tbOverrides,
            )
          }
          Triple(parsed, finalEvent, sim)
        }

        val result = flowEvent.synthesisResult ?: snapshot.synthesisResult
        val duration = (result as? SynthesisResult.Success)?.report?.synthesisDurationMs
          ?: flowEvent.stageStates.sumOf { it.durationMs }
        val phaseSummary = when (result) {
          is SynthesisResult.Success -> if (flowEvent.layoutSnapshot != null) {
            "RTL-to-GDSII complete (${duration} ms · DRC ${flowEvent.layoutSnapshot.drcViolations})"
          } else {
            "Synthesis complete (${duration} ms)"
          }
          is SynthesisResult.Error -> "Syntax / Elaboration Error (Line ${result.line})"
          null -> "Idle"
        }
        val firstSelected = when (result) {
          is SynthesisResult.Success -> result.report.criticalPath.firstOrNull()?.nodeId
          else -> null
        }

        _workbench.update { state ->
          state.copy(
            parsedDesign = (parseResult as? VerilogParseResult.Success)?.design,
            parseError = parseResult as? VerilogParseResult.Error,
            synthesisResult = result,
            flowStages = flowEvent.stageStates,
            flowLogLines = flowEvent.logLines,
            physicalLayout = flowEvent.layoutSnapshot ?: state.physicalLayout,
            synthesisProgress = 1f,
            synthesisPhaseLabel = phaseSummary,
            lastRunDurationMs = duration,
            selectedNodeId = firstSelected,
            manualNodePositions = emptyMap(),
            isManualSchematic = false,
            isSynthesizing = false,
            canResumeFlow = result is SynthesisResult.Error,
            simulationResult = simResult ?: state.simulationResult,
          )
        }

        val report = (result as? SynthesisResult.Success)?.report
        if (report != null && localStore != null && !snapshot.activeDesignId.isNullOrBlank()) {
          localStore.recordLocalRun(
            buildRunModel(
              designId = snapshot.activeDesignId,
              designName = snapshot.designName,
              report = report,
              layout = flowEvent.layoutSnapshot,
              coreUtilizationPercent = snapshot.coreUtilizationPercent,
            )
          )
        }
      } catch (_: CancellationException) {
        // Handled cleanly by cancelRtlToGdsFlow() or superseded by a newer run
      }
    }
  }

  // ==================== RTL SIMULATION ====================

  fun runSimulation(useTestbenchFile: Boolean = false) {
    val snapshot = _workbench.value
    viewModelScope.launch {
      val combinedRtl = RtlProjectHelpers.combineProjectRtlSources(snapshot.files, snapshot.verilogCode)
      val tbSource = snapshot.files.firstOrNull { it.language == RtlLanguage.TESTBENCH }?.content.orEmpty()
      val overrides = if (useTestbenchFile && tbSource.isNotBlank()) {
        RtlSimulator.parseTestbenchOverrides(tbSource, snapshot.simCycleCount)
      } else {
        snapshot.customSimValues
      }
      val sim = withContext(Dispatchers.Default) {
        val manualReport = (snapshot.synthesisResult as? SynthesisResult.Success)?.report
        when {
          snapshot.isManualSchematic && manualReport != null -> RtlSimulator.simulateManualNetlist(
            report = manualReport,
            cycleCount = snapshot.simCycleCount,
            patternOverrides = snapshot.stimulusPatterns,
            customValueOverrides = overrides,
          )
          snapshot.parsedDesign != null -> RtlSimulator.simulate(
            design = snapshot.parsedDesign,
            cycleCount = snapshot.simCycleCount,
            patternOverrides = snapshot.stimulusPatterns,
            customValueOverrides = overrides,
          )
          else -> {
            val parsed = RtlProjectHelpers.parseLanguageAware(combinedRtl, snapshot.selectedTopModule)
            (parsed as? VerilogParseResult.Success)?.design?.let { design ->
              RtlSimulator.simulate(
                design = design,
                cycleCount = snapshot.simCycleCount,
                patternOverrides = snapshot.stimulusPatterns,
                customValueOverrides = overrides,
              )
            }
          }
        }
      }
      if (sim != null) {
        _workbench.update {
          it.copy(
            simulationResult = sim,
            customSimValues = if (useTestbenchFile) overrides else it.customSimValues,
            statusBanner = if (useTestbenchFile) "Applied testbench stimulus and simulated ${sim.cycleCount} cycles." else it.statusBanner,
          )
        }
      } else {
        _workbench.update { it.copy(statusBanner = "Fix HDL syntax errors before running RTL simulation.") }
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

  fun toggleWaveformSignal(signalName: String) {
    _workbench.update { state ->
      val allNames = state.simulationResult?.traces?.map { it.name }?.toSet().orEmpty()
      val current = state.selectedWaveformSignals.ifEmpty { allNames }
      val updated = if (signalName in current) current - signalName else current + signalName
      state.copy(selectedWaveformSignals = updated.ifEmpty { allNames })
    }
  }

  fun resetSimulationStimulus() {
    _workbench.update {
      it.copy(
        stimulusPatterns = emptyMap(),
        customSimValues = emptyMap(),
        selectedWaveformSignals = emptySet(),
        statusBanner = "Reset simulation stimulus to automatic testbench vectors.",
      )
    }
    runSimulation()
  }

  // ==================== LOCAL WORKSPACE PERSISTENCE ====================

  fun saveDesign() {
    val state = _workbench.value
    if (state.designName.isBlank() || state.verilogCode.isBlank()) {
      _workbench.update { it.copy(statusBanner = "Project name and RTL source cannot be empty.") }
      return
    }
    val report = (state.synthesisResult as? SynthesisResult.Success)?.report
    val designPayload = RtlDesign(
      id = state.activeDesignId.orEmpty(),
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
      language = state.primaryLanguage.name,
      files = state.files,
    )

    val savedId = localStore?.upsertLocalDesign(designPayload) ?: ""
    if (report != null) {
      localStore?.recordLocalRun(
        buildRunModel(
          designId = savedId,
          designName = state.designName.trim(),
          report = report,
          layout = state.physicalLayout,
          coreUtilizationPercent = state.coreUtilizationPercent,
        )
      )
    }
    val savedTime = formatClockTime(clockMillisProvider())
    _workbench.update {
      it.copy(
        activeDesignId = savedId.ifBlank { it.activeDesignId },
        hasUnsavedChanges = false,
        autoSaveStatus = AutoSaveStatus.SAVED,
        lastAutoSavedLabel = savedTime,
        statusBanner = "Saved project '${state.designName}' on device.",
      ).also { updated -> persistDraft(updated, markDirty = false) }
    }
    lastAutoSavedSignature = _workbench.value.toSaveSignature()
    dirtyForAutoSave = false
  }

  fun duplicateDesign(design: RtlDesign) {
    val copyPayload = design.copy(
      id = "",
      name = "${design.name}_copy",
      createdAtMillis = 0L,
      updatedAtMillis = 0L,
    )
    localStore?.upsertLocalDesign(copyPayload)
    _workbench.update { it.copy(statusBanner = "Duplicated '${design.name}' as '${copyPayload.name}'.") }
  }

  fun saveRunSnapshotOnly() {
    val state = _workbench.value
    val report = (state.synthesisResult as? SynthesisResult.Success)?.report ?: run {
      _workbench.update { it.copy(statusBanner = "Run a valid synthesis before saving a report snapshot.") }
      return
    }
    val runPayload = buildRunModel(
      designId = state.activeDesignId ?: "preset_${state.designName}",
      designName = state.designName.trim().ifBlank { report.moduleName },
      report = report,
      layout = state.physicalLayout,
      coreUtilizationPercent = state.coreUtilizationPercent,
    )
    localStore?.recordLocalRun(runPayload)
    _workbench.update {
      it.copy(statusBanner = "Saved engineering report snapshot (${report.targetLibrary.shortName}).")
    }
  }

  fun deleteSavedDesign(designId: String) {
    localStore?.deleteLocalDesign(designId)
    _workbench.update { state ->
      val clearActive = state.activeDesignId == designId
      state.copy(
        activeDesignId = if (clearActive) null else state.activeDesignId,
        statusBanner = "Removed project from device storage.",
      )
    }
  }

  fun deleteSynthesisRun(runId: String) {
    localStore?.deleteLocalRun(runId)
    _workbench.update { it.copy(statusBanner = "Removed synthesis report snapshot.") }
  }

  // ==================== IDE SETTINGS & STORAGE ====================

  fun updateSettings(transform: (AppSettings) -> AppSettings) {
    val next = transform(_settings.value)
    _settings.value = next
    localStore?.saveSettings(next)
  }

  fun toggleDarkTheme() {
    updateSettings { it.copy(darkTheme = !it.darkTheme) }
  }

  fun clearSynthesisHistory() {
    localStore?.clearSynthesisRuns()
    _workbench.update { it.copy(statusBanner = "Cleared cached synthesis run reports.") }
  }

  fun resetWorkspaceToStarter() {
    localStore?.clearActiveDraft()
    loadPreset(PresetRtlDesigns.templates.first())
    _workbench.update { it.copy(statusBanner = "Restored starter RTL project.") }
  }

  fun estimatedStorageKb(): Double {
    val bytes = localStore?.estimatedStorageBytes() ?: 0L
    return (bytes / 1024.0).coerceAtLeast(0.1)
  }

  private fun buildRunModel(
    designId: String,
    designName: String,
    report: SynthesisReport,
    layout: PhysicalLayoutSnapshot? = null,
    coreUtilizationPercent: Int = 60,
  ): SynthesisRun {
    return SynthesisRun(
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
      durationMs = report.synthesisDurationMs,
      engineId = report.engineProfile.id,
      coreUtilizationPercent = layout?.coreUtilizationPercent ?: coreUtilizationPercent,
      wirelengthUm = layout?.totalWirelengthUm ?: 0.0,
      clockSkewNs = layout?.clockSkewNs ?: 0.0,
      holdSlackNs = layout?.holdWnsNs ?: 0.0,
      drcViolations = layout?.drcViolations ?: 0,
      gdsSizeBytes = layout?.gdsBytes?.size?.toLong() ?: 0L,
    )
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5000L
    const val DEFAULT_AUTOSAVE_INTERVAL_MS = 15_000L
    const val DEFAULT_AUTOSAVE_DEBOUNCE_MS = 2_500L
  }
}
