package com.example.data.local

import android.content.Context
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalDraftSnapshot(
  val designName: String,
  val description: String,
  val verilogCode: String,
  val sdcText: String,
  val targetLibrary: TargetLibrary,
  val optimizationGoal: OptimizationGoal,
  val clockPeriodNs: Double,
  val savedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Offline-first local persistence store using JSON files in app-private storage (`filesDir`).
 * Enables full RTL authoring, synthesis, simulation, and PPA run tracking without network or sign-in,
 * and allows syncing locally saved modules to Firestore once signed in with Google.
 */
class LocalWorkspaceStore(private val rootDir: File) {

  constructor(context: Context) : this(File(context.filesDir, "rtl_synth_local"))

  private val designsFile: File get() = File(rootDir, "local_designs.json")
  private val runsFile: File get() = File(rootDir, "local_runs.json")
  private val draftFile: File get() = File(rootDir, "active_draft.json")

  private val _localDesigns = MutableStateFlow<List<RtlDesign>>(emptyList())
  val localDesigns: StateFlow<List<RtlDesign>> = _localDesigns.asStateFlow()

  private val _localRuns = MutableStateFlow<List<SynthesisRun>>(emptyList())
  val localRuns: StateFlow<List<SynthesisRun>> = _localRuns.asStateFlow()

  init {
    rootDir.mkdirs()
    _localDesigns.value = readDesignsFromDisk()
    _localRuns.value = readRunsFromDisk()
  }

  fun saveActiveDraft(draft: LocalDraftSnapshot) {
    runCatching {
      rootDir.mkdirs()
      val obj = JSONObject().apply {
        put("designName", draft.designName)
        put("description", draft.description)
        put("verilogCode", draft.verilogCode)
        put("sdcText", draft.sdcText)
        put("targetLibrary", draft.targetLibrary.id)
        put("optimizationGoal", draft.optimizationGoal.id)
        put("clockPeriodNs", draft.clockPeriodNs)
        put("savedAtEpochMs", draft.savedAtEpochMs)
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
        designName = obj.optString("designName", "rtl_module"),
        description = obj.optString("description", ""),
        verilogCode = code,
        sdcText = obj.optString("sdcText", ""),
        targetLibrary = TargetLibrary.fromId(obj.optString("targetLibrary", TargetLibrary.ASAP7_7NM.id)),
        optimizationGoal = OptimizationGoal.fromId(obj.optString("optimizationGoal", OptimizationGoal.BALANCED.id)),
        clockPeriodNs = obj.optDouble("clockPeriodNs", 2.0),
        savedAtEpochMs = obj.optLong("savedAtEpochMs", 0L),
      )
    }.getOrNull()
  }

  fun upsertLocalDesign(design: RtlDesign): String {
    val id = design.id.ifBlank { "local_${UUID.randomUUID().toString().take(8)}" }
    val nowSec = System.currentTimeMillis() / 1000L
    val normalized = design.copy(
      id = id,
      userId = "local_offline",
      createdAt = design.createdAt ?: Timestamp(nowSec, 0),
      updatedAt = Timestamp(nowSec, 0),
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
    val nowSec = System.currentTimeMillis() / 1000L
    val normalized = run.copy(
      id = id,
      userId = "local_offline",
      createdAt = run.createdAt ?: Timestamp(nowSec, 0),
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

  private fun readDesignsFromDisk(): List<RtlDesign> {
    return runCatching {
      if (!designsFile.exists()) return emptyList()
      val arr = JSONArray(designsFile.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          val createdSec = obj.optLong("createdAtSec", 0L)
          val updatedSec = obj.optLong("updatedAtSec", createdSec)
          add(
            RtlDesign(
              id = obj.optString("id"),
              userId = "local_offline",
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
              createdAt = if (createdSec > 0) Timestamp(createdSec, 0) else null,
              updatedAt = if (updatedSec > 0) Timestamp(updatedSec, 0) else null,
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
            put("createdAtSec", d.createdAt?.seconds ?: 0L)
            put("updatedAtSec", d.updatedAt?.seconds ?: 0L)
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
          val createdSec = obj.optLong("createdAtSec", 0L)
          add(
            SynthesisRun(
              id = obj.optString("id"),
              userId = "local_offline",
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
              createdAt = if (createdSec > 0) Timestamp(createdSec, 0) else null,
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
            put("createdAtSec", r.createdAt?.seconds ?: 0L)
          }
        )
      }
      runsFile.writeText(arr.toString())
    }
  }
}
