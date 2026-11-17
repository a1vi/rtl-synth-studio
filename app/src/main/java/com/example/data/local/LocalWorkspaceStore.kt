package com.example.data.local

import android.content.Context
import com.example.data.model.AppSettings
import com.example.data.model.ExportFormat
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlFileEntry
import com.example.data.model.RtlLanguage
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.example.domain.synth.DftConfig
import com.example.domain.synth.DftScanStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalDraftSnapshot(
  val activeDesignId: String? = null,
  val designName: String,
  val description: String,
  val verilogCode: String,
  val sdcText: String,
  val targetLibrary: TargetLibrary,
  val optimizationGoal: OptimizationGoal,
  val clockPeriodNs: Double,
  val savedAtEpochMs: Long = System.currentTimeMillis(),
  val files: List<RtlFileEntry> = emptyList(),
  val activeFileId: String? = null,
  val coreUtilizationPercent: Int = 60,
  val dftConfig: DftConfig = DftConfig(),
)

/**
 * On-device persistence store using JSON files in app-private storage (`filesDir`).
 * Preserves active drafts, multi-file RTL projects, historical PPA runs, and IDE settings locally.
 */
class LocalWorkspaceStore(private val rootDir: File) {

  constructor(context: Context) : this(File(context.filesDir, "rtl_synth_local"))

  private val designsFile: File get() = File(rootDir, "local_designs.json")
  private val runsFile: File get() = File(rootDir, "local_runs.json")
  private val draftFile: File get() = File(rootDir, "active_draft.json")
  private val settingsFile: File get() = File(rootDir, "app_settings.json")

  private val _localDesigns = MutableStateFlow<List<RtlDesign>>(emptyList())
  val localDesigns: StateFlow<List<RtlDesign>> = _localDesigns.asStateFlow()

  private val _localRuns = MutableStateFlow<List<SynthesisRun>>(emptyList())
  val localRuns: StateFlow<List<SynthesisRun>> = _localRuns.asStateFlow()

  init {
    rootDir.mkdirs()
    _localDesigns.value = readDesignsFromDisk()
    _localRuns.value = readRunsFromDisk()
  }

  fun saveSettings(settings: AppSettings) {
    runCatching {
      rootDir.mkdirs()
      val obj = JSONObject().apply {
        put("darkTheme", settings.darkTheme)
        put("compactDensity", settings.compactDensity)
        put("editorFontSizeSp", settings.editorFontSizeSp)
        put("tabWidth", settings.tabWidth)
        put("autoIndent", settings.autoIndent)
        put("showLineNumbers", settings.showLineNumbers)
        put("wordWrap", settings.wordWrap)
        put("autoSaveIntervalSec", settings.autoSaveIntervalSec)
        put("defaultEngine", settings.defaultEngine.id)
        put("aggressiveOptimization", settings.aggressiveOptimization)
        put("retainHierarchy", settings.retainHierarchy)
        put("bitBlastGates", settings.bitBlastGates)
        put("defaultLibrary", settings.defaultLibrary.id)
        put("localBridgeEnabled", settings.localBridgeEnabled)
        put("localBridgeEndpoint", settings.localBridgeEndpoint)
        put("defaultExportFormat", settings.defaultExportFormat.id)
        put("includeTimestampsInExport", settings.includeTimestampsInExport)
      }
      settingsFile.writeText(obj.toString())
    }
  }

  fun loadSettings(): AppSettings {
    return runCatching {
      if (!settingsFile.exists()) return AppSettings()
      val obj = JSONObject(settingsFile.readText())
      AppSettings(
        darkTheme = obj.optBoolean("darkTheme", true),
        compactDensity = obj.optBoolean("compactDensity", false),
        editorFontSizeSp = obj.optInt("editorFontSizeSp", 13).coerceIn(11, 18),
        tabWidth = obj.optInt("tabWidth", 2).coerceIn(2, 4),
        autoIndent = obj.optBoolean("autoIndent", true),
        showLineNumbers = obj.optBoolean("showLineNumbers", true),
        wordWrap = obj.optBoolean("wordWrap", false),
        autoSaveIntervalSec = obj.optInt("autoSaveIntervalSec", 15).coerceIn(0, 60),
        defaultEngine = SynthesisEngineProfile.fromId(obj.optString("defaultEngine", SynthesisEngineProfile.SILICON_FLOW.id)),
        aggressiveOptimization = obj.optBoolean("aggressiveOptimization", false),
        retainHierarchy = obj.optBoolean("retainHierarchy", true),
        bitBlastGates = obj.optBoolean("bitBlastGates", false),
        defaultLibrary = TargetLibrary.fromId(obj.optString("defaultLibrary", TargetLibrary.ASAP7_7NM.id)),
        localBridgeEnabled = obj.optBoolean("localBridgeEnabled", false),
        localBridgeEndpoint = obj.optString("localBridgeEndpoint", "http://127.0.0.1:8765"),
        defaultExportFormat = ExportFormat.fromId(obj.optString("defaultExportFormat", ExportFormat.VERILOG_NETLIST.id)),
        includeTimestampsInExport = obj.optBoolean("includeTimestampsInExport", true),
      )
    }.getOrDefault(AppSettings())
  }

  fun saveActiveDraft(draft: LocalDraftSnapshot) {
    runCatching {
      rootDir.mkdirs()
      val obj = JSONObject().apply {
        put("activeDesignId", draft.activeDesignId.orEmpty())
        put("designName", draft.designName)
        put("description", draft.description)
        put("verilogCode", draft.verilogCode)
        put("sdcText", draft.sdcText)
        put("targetLibrary", draft.targetLibrary.id)
        put("optimizationGoal", draft.optimizationGoal.id)
        put("clockPeriodNs", draft.clockPeriodNs)
        put("savedAtEpochMs", draft.savedAtEpochMs)
        put("activeFileId", draft.activeFileId.orEmpty())
        put("files", serializeFiles(draft.files))
        put("coreUtilizationPercent", draft.coreUtilizationPercent)
        put("dftScanEnabled", draft.dftConfig.scanInsertionEnabled)
        put("dftScanStyle", draft.dftConfig.scanStyle.id)
        put("dftChainCount", draft.dftConfig.targetChainCount)
        put("dftMaxChainLength", draft.dftConfig.maxChainLength)
        put("dftLockupLatches", draft.dftConfig.insertLockupLatches)
        put("dftJtagTap", draft.dftConfig.insertJtagBoundaryTap)
        put("dftMbistCollar", draft.dftConfig.insertMbistCollar)
        put("dftAtpgMaxPatterns", draft.dftConfig.atpgMaxPatterns)
      }
      draftFile.writeText(obj.toString())
    }
  }

  fun loadActiveDraft(): LocalDraftSnapshot? {
    return runCatching {
      if (!draftFile.exists()) return null
      val obj = JSONObject(draftFile.readText())
      val code = obj.optString("verilogCode", "")
      if (code.isBlank()) return null
      LocalDraftSnapshot(
        activeDesignId = obj.optString("activeDesignId", "").ifBlank { null },
        designName = obj.optString("designName", "rtl_module"),
        description = obj.optString("description", ""),
        verilogCode = code,
        sdcText = obj.optString("sdcText", ""),
        targetLibrary = TargetLibrary.fromId(obj.optString("targetLibrary", TargetLibrary.ASAP7_7NM.id)),
        optimizationGoal = OptimizationGoal.fromId(obj.optString("optimizationGoal", OptimizationGoal.BALANCED.id)),
        clockPeriodNs = obj.optDouble("clockPeriodNs", 2.0),
        savedAtEpochMs = obj.optLong("savedAtEpochMs", 0L),
        files = deserializeFiles(obj.optJSONArray("files")),
        activeFileId = obj.optString("activeFileId", "").ifBlank { null },
        coreUtilizationPercent = obj.optInt("coreUtilizationPercent", 60).coerceIn(30, 85),
        dftConfig = DftConfig(
          scanInsertionEnabled = obj.optBoolean("dftScanEnabled", false),
          scanStyle = DftScanStyle.fromId(obj.optString("dftScanStyle", DftScanStyle.MUXED_D.id)),
          targetChainCount = obj.optInt("dftChainCount", 1).coerceIn(1, 8),
          maxChainLength = obj.optInt("dftMaxChainLength", 16).coerceIn(4, 64),
          insertLockupLatches = obj.optBoolean("dftLockupLatches", true),
          insertJtagBoundaryTap = obj.optBoolean("dftJtagTap", false),
          insertMbistCollar = obj.optBoolean("dftMbistCollar", false),
          atpgMaxPatterns = obj.optInt("dftAtpgMaxPatterns", 32).coerceIn(8, 128),
        ),
      )
    }.getOrNull()
  }

  fun upsertLocalDesign(design: RtlDesign): String {
    val id = design.id.ifBlank { "local_${UUID.randomUUID().toString().take(8)}" }
    val nowMs = System.currentTimeMillis()
    val existing = _localDesigns.value.firstOrNull { it.id == id }
    val normalized = design.copy(
      id = id,
      createdAtMillis = existing?.createdAtMillis?.takeIf { it > 0L }
        ?: design.createdAtMillis.takeIf { it > 0L }
        ?: nowMs,
      updatedAtMillis = nowMs,
    )
    val current = _localDesigns.value.filterNot { it.id == id }.toMutableList()
    current.add(0, normalized)
    _localDesigns.value = current
    writeDesignsToDisk(current)
    return id
  }

  fun deleteLocalDesign(designId: String) {
    val updated = _localDesigns.value.filterNot { it.id == designId }
    _localDesigns.value = updated
    writeDesignsToDisk(updated)
  }

  fun recordLocalRun(run: SynthesisRun): String {
    val id = run.id.ifBlank { "run_${UUID.randomUUID().toString().take(8)}" }
    val nowMs = System.currentTimeMillis()
    val normalized = run.copy(
      id = id,
      createdAtMillis = run.createdAtMillis.takeIf { it > 0L } ?: nowMs,
    )
    val updated = (listOf(normalized) + _localRuns.value.filterNot { it.id == id }).take(60)
    _localRuns.value = updated
    writeRunsToDisk(updated)
    return id
  }

  fun deleteLocalRun(runId: String) {
    val updated = _localRuns.value.filterNot { it.id == runId }
    _localRuns.value = updated
    writeRunsToDisk(updated)
  }

  fun clearSynthesisRuns() {
    _localRuns.value = emptyList()
    runCatching { if (runsFile.exists()) runsFile.delete() }
  }

  fun clearActiveDraft() {
    runCatching { if (draftFile.exists()) draftFile.delete() }
  }

  fun estimatedStorageBytes(): Long {
    val files = listOf(designsFile, runsFile, draftFile, settingsFile)
    return files.sumOf { if (it.exists()) it.length() else 0L }
  }

  private fun serializeFiles(files: List<RtlFileEntry>): JSONArray {
    val arr = JSONArray()
    files.forEach { f ->
      arr.put(
        JSONObject().apply {
          put("id", f.id)
          put("name", f.name)
          put("language", f.language.name)
          put("content", f.content)
        }
      )
    }
    return arr
  }

  private fun deserializeFiles(arr: JSONArray?): List<RtlFileEntry> {
    if (arr == null) return emptyList()
    return buildList {
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val id = obj.optString("id")
        val name = obj.optString("name")
        if (id.isNotBlank() && name.isNotBlank()) {
          add(
            RtlFileEntry(
              id = id,
              name = name,
              language = RtlLanguage.fromId(obj.optString("language", RtlLanguage.VERILOG.name)),
              content = obj.optString("content", ""),
            )
          )
        }
      }
    }
  }

  private fun readDesignsFromDisk(): List<RtlDesign> {
    return runCatching {
      if (!designsFile.exists()) return emptyList()
      val arr = JSONArray(designsFile.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          val createdMs = obj.optLong("createdAtMillis", obj.optLong("createdAtSec", 0L) * 1000L)
          val updatedMs = obj.optLong("updatedAtMillis", obj.optLong("updatedAtSec", 0L) * 1000L)
            .let { if (it > 0L) it else createdMs }
          add(
            RtlDesign(
              id = obj.optString("id"),
              name = obj.optString("name"),
              description = obj.optString("description"),
              verilogCode = obj.optString("verilogCode"),
              targetLibrary = obj.optString("targetLibrary", TargetLibrary.ASAP7_7NM.id),
              optimizationGoal = obj.optString("optimizationGoal", OptimizationGoal.BALANCED.id),
              clockPeriodNs = obj.optDouble("clockPeriodNs", 2.0),
              lastWnsNs = obj.optDouble("lastWnsNs", 0.0),
              lastAreaUm2 = obj.optDouble("lastAreaUm2", 0.0),
              lastCellCount = obj.optLong("lastCellCount", 0L),
              lastPowerMw = obj.optDouble("lastPowerMw", 0.0),
              createdAtMillis = createdMs,
              updatedAtMillis = updatedMs,
              language = obj.optString("language", RtlLanguage.VERILOG.name),
              files = deserializeFiles(obj.optJSONArray("files")),
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun writeDesignsToDisk(list: List<RtlDesign>) {
    runCatching {
      rootDir.mkdirs()
      val arr = JSONArray()
      list.forEach { d ->
        arr.put(
          JSONObject().apply {
            put("id", d.id)
            put("name", d.name)
            put("description", d.description)
            put("verilogCode", d.verilogCode)
            put("targetLibrary", d.targetLibrary)
            put("optimizationGoal", d.optimizationGoal)
            put("clockPeriodNs", d.clockPeriodNs)
            put("lastWnsNs", d.lastWnsNs)
            put("lastAreaUm2", d.lastAreaUm2)
            put("lastCellCount", d.lastCellCount)
            put("lastPowerMw", d.lastPowerMw)
            put("createdAtMillis", d.createdAtMillis)
            put("updatedAtMillis", d.updatedAtMillis)
            put("language", d.language)
            put("files", serializeFiles(d.files))
          }
        )
      }
      designsFile.writeText(arr.toString())
    }
  }

  private fun readRunsFromDisk(): List<SynthesisRun> {
    return runCatching {
      if (!runsFile.exists()) return emptyList()
      val arr = JSONArray(runsFile.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          val createdMs = obj.optLong("createdAtMillis", obj.optLong("createdAtSec", 0L) * 1000L)
          add(
            SynthesisRun(
              id = obj.optString("id"),
              designId = obj.optString("designId"),
              designName = obj.optString("designName"),
              targetLibrary = obj.optString("targetLibrary", TargetLibrary.ASAP7_7NM.id),
              optimizationGoal = obj.optString("optimizationGoal", OptimizationGoal.BALANCED.id),
              clockPeriodNs = obj.optDouble("clockPeriodNs", 2.0),
              fmaxMhz = obj.optDouble("fmaxMhz", 0.0),
              wnsNs = obj.optDouble("wnsNs", 0.0),
              tnsNs = obj.optDouble("tnsNs", 0.0),
              areaUm2 = obj.optDouble("areaUm2", 0.0),
              cellCount = obj.optLong("cellCount", 0L),
              lutCount = obj.optLong("lutCount", 0L),
              dffCount = obj.optLong("dffCount", 0L),
              powerMw = obj.optDouble("powerMw", 0.0),
              criticalPathSummary = obj.optString("criticalPathSummary"),
              netlistVerilog = obj.optString("netlistVerilog"),
              createdAtMillis = createdMs,
              durationMs = obj.optLong("durationMs", 0L),
              engineId = obj.optString("engineId", SynthesisEngineProfile.SILICON_FLOW.id),
              coreUtilizationPercent = obj.optInt("coreUtilizationPercent", 60),
              wirelengthUm = obj.optDouble("wirelengthUm", 0.0),
              clockSkewNs = obj.optDouble("clockSkewNs", 0.0),
              holdSlackNs = obj.optDouble("holdSlackNs", 0.0),
              drcViolations = obj.optInt("drcViolations", 0),
              gdsSizeBytes = obj.optLong("gdsSizeBytes", 0L),
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun writeRunsToDisk(list: List<SynthesisRun>) {
    runCatching {
      rootDir.mkdirs()
      val arr = JSONArray()
      list.forEach { r ->
        arr.put(
          JSONObject().apply {
            put("id", r.id)
            put("designId", r.designId)
            put("designName", r.designName)
            put("targetLibrary", r.targetLibrary)
            put("optimizationGoal", r.optimizationGoal)
            put("clockPeriodNs", r.clockPeriodNs)
            put("fmaxMhz", r.fmaxMhz)
            put("wnsNs", r.wnsNs)
            put("tnsNs", r.tnsNs)
            put("areaUm2", r.areaUm2)
            put("cellCount", r.cellCount)
            put("lutCount", r.lutCount)
            put("dffCount", r.dffCount)
            put("powerMw", r.powerMw)
            put("criticalPathSummary", r.criticalPathSummary)
            put("netlistVerilog", r.netlistVerilog)
            put("createdAtMillis", r.createdAtMillis)
            put("durationMs", r.durationMs)
            put("engineId", r.engineId)
            put("coreUtilizationPercent", r.coreUtilizationPercent)
            put("wirelengthUm", r.wirelengthUm)
            put("clockSkewNs", r.clockSkewNs)
            put("holdSlackNs", r.holdSlackNs)
            put("drcViolations", r.drcViolations)
            put("gdsSizeBytes", r.gdsSizeBytes)
          }
        )
      }
      runsFile.writeText(arr.toString())
    }
  }
}
