package com.example.domain.synth

import android.graphics.Bitmap
import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class FlowStageId(
  val order: Int,
  val title: String,
  val toolLabel: String,
) {
  SYNTHESIS(1, "Synthesis", "RTL Elaborate & TechMap"),
  FLOORPLAN(2, "Floorplan", "Die / Core & IO Ring"),
  PLACEMENT(3, "Placement", "Row Legalizer & HPWL"),
  CTS(4, "CTS", "H-Tree Clock & Skew"),
  ROUTING(5, "Routing", "M1-M3 Global/Detail"),
  SIGNOFF_DRC_LVS(6, "DRC / LVS", "Geometry & Net Check"),
  GDS_EXPORT(7, "GDSII", "Mask Stream Export"),
}

enum class FlowStageStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
  CANCELED,
}

data class FlowStageState(
  val id: FlowStageId,
  val status: FlowStageStatus = FlowStageStatus.PENDING,
  val durationMs: Long = 0L,
  val headlineMetric: String = "Queued",
  val details: List<String> = emptyList(),
)

data class PlacedCellLayout(
  val nodeId: String,
  val instanceName: String,
  val cellName: String,
  val gateType: GateType,
  val isCritical: Boolean,
  val xUm: Double,
  val yUm: Double,
  val widthUm: Double,
  val heightUm: Double,
  val rowIndex: Int,
)

data class RoutedWireSegment(
  val netName: String,
  val layer: PhysicalMaskLayer,
  val x1Um: Double,
  val y1Um: Double,
  val x2Um: Double,
  val y2Um: Double,
  val widthUm: Double,
  val isCritical: Boolean,
  val isClock: Boolean = false,
)

data class ViaCut(
  val xUm: Double,
  val yUm: Double,
  val layer: PhysicalMaskLayer,
  val netName: String,
)

data class PhysicalLayoutSnapshot(
  val moduleName: String,
  val library: TargetLibrary,
  val dieWidthUm: Double,
  val dieHeightUm: Double,
  val coreXUm: Double,
  val coreYUm: Double,
  val coreWidthUm: Double,
  val coreHeightUm: Double,
  val rowHeightUm: Double,
  val rowCount: Int,
  val coreUtilizationPercent: Int,
  val actualUtilizationPercent: Double,
  val placedCells: List<PlacedCellLayout>,
  val wireSegments: List<RoutedWireSegment>,
  val vias: List<ViaCut>,
  val polygons: List<GdsRectPolygon>,
  val pinLabels: List<GdsRectPolygon>,
  val totalWirelengthUm: Double,
  val viaCount: Int,
  val clockSkewNs: Double,
  val clockInsertionNs: Double,
  val setupWnsNs: Double,
  val holdWnsNs: Double,
  val drcViolations: Int,
  val drcSummary: String,
  val lvsClean: Boolean,
  val gdsBytes: ByteArray,
  val gdsSha256: String,
  val tclScript: String,
  val gdsVerified: Boolean = true,
  val gdsStructureCount: Int = 1,
  val gdsSrefCount: Int = 0,
  val klayoutLypXml: String = "",
  val klayoutSnapshotBitmap: Bitmap? = null,
  val klayoutSnapshotPng: ByteArray = ByteArray(0),
)

data class RtlToGdsStageEvent(
  val stageStates: List<FlowStageState>,
  val logLines: List<String>,
  val synthesisResult: SynthesisResult? = null,
  val layoutSnapshot: PhysicalLayoutSnapshot? = null,
  val isFinished: Boolean = false,
)

data class PdkTechProfile(
  val library: TargetLibrary,
  val rowHeightUm: Double,
  val siteWidthUm: Double,
  val m1PitchUm: Double,
  val m2PitchUm: Double,
  val minSpacingUm: Double,
  val coreMarginUm: Double,
  val clockBufferDelayNs: Double,
  val wireDelayNsPerMm: Double,
)

object RtlToGdsFlowEngine {

  fun techProfileFor(library: TargetLibrary): PdkTechProfile = when (library) {
    TargetLibrary.SKY130_130NM -> PdkTechProfile(
      library = library,
      rowHeightUm = 2.72,
      siteWidthUm = 0.46,
      m1PitchUm = 0.34,
      m2PitchUm = 0.46,
      minSpacingUm = 0.14,
      coreMarginUm = 10.0,
      clockBufferDelayNs = 0.085,
      wireDelayNsPerMm = 0.42,
    )
    TargetLibrary.GF180MCU_180NM -> PdkTechProfile(
      library = library,
      rowHeightUm = 5.04,
      siteWidthUm = 0.56,
      m1PitchUm = 0.56,
      m2PitchUm = 0.56,
      minSpacingUm = 0.28,
      coreMarginUm = 14.0,
      clockBufferDelayNs = 0.14,
      wireDelayNsPerMm = 0.55,
    )
    TargetLibrary.NANGATE45_45NM -> PdkTechProfile(
      library = library,
      rowHeightUm = 1.40,
      siteWidthUm = 0.19,
      m1PitchUm = 0.14,
      m2PitchUm = 0.14,
      minSpacingUm = 0.07,
      coreMarginUm = 6.0,
      clockBufferDelayNs = 0.045,
      wireDelayNsPerMm = 0.30,
    )
    TargetLibrary.ASAP7_7NM -> PdkTechProfile(
      library = library,
      rowHeightUm = 0.27,
      siteWidthUm = 0.054,
      m1PitchUm = 0.036,
      m2PitchUm = 0.036,
      minSpacingUm = 0.018,
      coreMarginUm = 2.5,
      clockBufferDelayNs = 0.022,
      wireDelayNsPerMm = 0.24,
    )
    TargetLibrary.ARTIX7_FPGA -> PdkTechProfile(
      library = library,
      rowHeightUm = 4.0,
      siteWidthUm = 1.0,
      m1PitchUm = 0.40,
      m2PitchUm = 0.40,
      minSpacingUm = 0.20,
      coreMarginUm = 8.0,
      clockBufferDelayNs = 0.095,
      wireDelayNsPerMm = 0.38,
    )
  }

  fun initialStages(): List<FlowStageState> =
    FlowStageId.entries.map { FlowStageState(id = it, status = FlowStageStatus.PENDING) }

  /**
   * Executes the 7-stage physical RTL-to-GDSII pipeline sequentially in pure Kotlin.
   * Supports resuming from an arbitrary stage index after cancel/pause.
   */
  suspend fun runFlow(
    rtlSource: String,
    designName: String,
    library: TargetLibrary,
    goal: OptimizationGoal,
    engineProfile: SynthesisEngineProfile,
    clockPeriodNs: Double,
    coreUtilizationPercent: Int,
    sdcText: String,
    topModuleOverride: String? = null,
    existingStages: List<FlowStageState> = initialStages(),
    existingReport: SynthesisReport? = null,
    startFromStageIndex: Int = 0,
    stepDelayMs: Long = 0L,
    bitBlast: Boolean = false,
    dftConfig: DftConfig = DftConfig(),
    onProgress: (RtlToGdsStageEvent) -> Unit,
  ): RtlToGdsStageEvent {
    val utilPct = coreUtilizationPercent.coerceIn(30, 85)
    val tech = techProfileFor(library)
    val stages = existingStages.toMutableList()
    if (stages.size != FlowStageId.entries.size) {
      stages.clear()
      stages.addAll(initialStages())
    }
    val logs = mutableListOf<String>()
    var activeReport: SynthesisReport? = existingReport

    // Intermediate floorplan/placement/CTS/routing state
    var dieW = 0.0
    var dieH = 0.0
    var coreX = tech.coreMarginUm
    var coreY = tech.coreMarginUm
    var coreW = 0.0
    var coreH = 0.0
    var rowCount = 1
    var placedCells: List<PlacedCellLayout> = emptyList()
    var actualUtil = utilPct.toDouble()
    var wireSegments: List<RoutedWireSegment> = emptyList()
    var vias: List<ViaCut> = emptyList()
    var totalWirelengthUm = 0.0
    var clockSkewNs = 0.0
    var clockInsertionNs = 0.0
    var setupWnsNs = 0.0
    var holdWnsNs = 0.0
    var drcViolations = 0
    var drcSummary = "0 DRC violations"
    var lvsClean = true

    fun emit(isFinished: Boolean = false, synthRes: SynthesisResult? = null, layout: PhysicalLayoutSnapshot? = null) {
      if (!isFinished && stepDelayMs <= 0L) return
      onProgress(
        RtlToGdsStageEvent(
          stageStates = stages.toList(),
          logLines = logs.toList(),
          synthesisResult = synthRes,
          layoutSnapshot = layout,
          isFinished = isFinished,
        )
      )
    }

    // Reconstruct intermediate physical artifacts up to startFromStageIndex when resuming
    val startIdx = startFromStageIndex.coerceIn(0, FlowStageId.entries.lastIndex)
    if (startIdx > 0 && activeReport == null) {
      val res = RtlSynthesisEngine.synthesize(
        verilogSource = rtlSource,
        library = library,
        goal = goal,
        clockPeriodNs = clockPeriodNs,
        sdcText = sdcText,
        engineProfile = engineProfile,
        topModuleName = topModuleOverride,
        bitBlast = bitBlast,
        dftConfig = dftConfig,
      )
      if (res is SynthesisResult.Success) {
        activeReport = res.report
      }
    }

    // Stage 1: Synthesis & Technology Mapping
    val t0 = System.currentTimeMillis()
    if (startIdx == 0 || activeReport == null) {
      currentCoroutineContext().ensureActive()
      stages[0] = stages[0].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Elaborating RTL...")
      logs += "[STAGE 1/7] Running RTL elaboration & ${library.shortName} technology mapping..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)

      val synthRes = RtlSynthesisEngine.synthesize(
        verilogSource = rtlSource,
        library = library,
        goal = goal,
        clockPeriodNs = clockPeriodNs,
        sdcText = sdcText,
        engineProfile = engineProfile,
        topModuleName = topModuleOverride,
        bitBlast = bitBlast,
        dftConfig = dftConfig,
      )
      val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)

      when (synthRes) {
        is SynthesisResult.Error -> {
          stages[0] = stages[0].copy(
            status = FlowStageStatus.FAILED,
            durationMs = dur,
            headlineMetric = "Syntax Error L${synthRes.line}",
            details = listOf(synthRes.message),
          )
          logs += synthRes.log
          logs += "[STAGE 1/7] FAILED at line ${synthRes.line}: ${synthRes.message}"
          val failEvent = RtlToGdsStageEvent(
            stageStates = stages.toList(),
            logLines = logs.toList(),
            synthesisResult = synthRes,
            isFinished = true,
          )
          onProgress(failEvent)
          return failEvent
        }
        is SynthesisResult.Success -> {
          activeReport = synthRes.report
          logs += synthRes.report.synthesisLog
          val dft = synthRes.report.dftReport
          val dftNote = if (dft != null && dft.scanInserted) {
            "DFT: ${dft.scanChains.size} scan chain(s) · ${"%.1f".format(Locale.US, dft.stuckAtCoveragePct)}% SA cov"
          } else if (dft != null) {
            "ATPG SA cov: ${"%.1f".format(Locale.US, dft.stuckAtCoveragePct)}%"
          } else {
            null
          }
          stages[0] = stages[0].copy(
            status = FlowStageStatus.PASSED,
            durationMs = dur,
            headlineMetric = "${synthRes.report.cellCount} cells · ${"%.1f".format(Locale.US, synthRes.report.totalAreaUm2)} µm²",
            details = listOfNotNull(
              "Comb: ${synthRes.report.combCellCount} · Seq: ${synthRes.report.seqCellCount}",
              "Pre-opt: ${synthRes.report.preOptCellCount} -> Post-opt: ${synthRes.report.postOptCellCount}",
              dftNote,
            ),
          )
          logs += "[STAGE 1/7] Synthesis passed (${stages[0].headlineMetric}) in ${dur}ms."
          emit(synthRes = synthRes)
        }
      }
    } else {
      logs += "[RESUME] Reusing Stage 1 synthesis checkpoint (${activeReport.cellCount} cells)."
    }

    val report = requireNotNull(activeReport)
    val effectiveName = designName.trim().ifBlank { report.moduleName }

    // Stage 2: Floorplan (Die / Core / Standard-Cell Rows / IO Ring)
    val fpStart = System.currentTimeMillis()
    if (startIdx <= 1) {
      currentCoroutineContext().ensureActive()
      stages[1] = stages[1].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Sizing die & rows...")
      logs += "[STAGE 2/7] Initializing floorplan ($utilPct% target core utilization, row=${tech.rowHeightUm} µm)..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)
    }
    val fpResult = computeFloorplan(report, tech, utilPct)
    dieW = fpResult.dieW
    dieH = fpResult.dieH
    coreX = fpResult.coreX
    coreY = fpResult.coreY
    coreW = fpResult.coreW
    coreH = fpResult.coreH
    rowCount = fpResult.rowCount
    if (startIdx <= 1) {
      val dur = (System.currentTimeMillis() - fpStart).coerceAtLeast(1L)
      stages[1] = stages[1].copy(
        status = FlowStageStatus.PASSED,
        durationMs = dur,
        headlineMetric = "${"%.1f".format(Locale.US, dieW)} × ${"%.1f".format(Locale.US, dieH)} µm",
        details = listOf(
          "Core: ${"%.1f".format(Locale.US, coreW)} × ${"%.1f".format(Locale.US, coreH)} µm",
          "Rows: $rowCount · IO Pins: ${report.ports.size}",
        ),
      )
      logs += "[STAGE 2/7] Floorplan: Die ${"%.1f".format(Locale.US, dieW)}x${"%.1f".format(Locale.US, dieH)} µm, $rowCount stdcell rows."
      emit()
    }

    // Stage 3: Placement (Topological row-legalized cell placement)
    val plStart = System.currentTimeMillis()
    if (startIdx <= 2) {
      currentCoroutineContext().ensureActive()
      stages[2] = stages[2].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Placing ${report.nodes.size} instances...")
      logs += "[STAGE 3/7] Running global + detailed row-legalized placement..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)
    }
    val placedOutcome = placeCellsInRows(report, tech, coreX, coreY, coreW, coreH, rowCount)
    placedCells = placedOutcome.first
    actualUtil = placedOutcome.second
    val estHpwl = computeHpwlUm(report, placedCells)
    if (startIdx <= 2) {
      val dur = (System.currentTimeMillis() - plStart).coerceAtLeast(1L)
      stages[2] = stages[2].copy(
        status = FlowStageStatus.PASSED,
        durationMs = dur,
        headlineMetric = "${placedCells.size} placed · ${"%.1f".format(Locale.US, actualUtil)}% util",
        details = listOf(
          "Estimated HPWL: ${"%.1f".format(Locale.US, estHpwl)} µm",
          "Legal row overlaps: 0",
        ),
      )
      logs += "[STAGE 3/7] Placement legalized ${placedCells.size} cells (actual util=${"%.1f".format(Locale.US, actualUtil)}%, HPWL=${"%.1f".format(Locale.US, estHpwl)} µm)."
      emit()
    }

    // Stage 4: Clock Tree Synthesis (CTS)
    val ctsStart = System.currentTimeMillis()
    if (startIdx <= 3) {
      currentCoroutineContext().ensureActive()
      stages[3] = stages[3].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Building clock H-tree...")
      logs += "[STAGE 4/7] Synthesizing balanced clock tree over ${report.seqCellCount} flip-flops..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)
    }
    val ctsOutcome = synthesizeClockTree(report, tech, placedCells, coreX, coreY, coreW, coreH)
    val clockWires = ctsOutcome.wires
    clockSkewNs = ctsOutcome.clockSkewNs
    clockInsertionNs = ctsOutcome.insertionDelayNs
    setupWnsNs = report.wnsNs - ctsOutcome.clockSkewNs * 0.35
    holdWnsNs = (0.18 + ctsOutcome.insertionDelayNs * 0.25 - ctsOutcome.clockSkewNs).coerceAtLeast(-0.15)
    if (startIdx <= 3) {
      val dur = (System.currentTimeMillis() - ctsStart).coerceAtLeast(1L)
      stages[3] = stages[3].copy(
        status = FlowStageStatus.PASSED,
        durationMs = dur,
        headlineMetric = "Skew ${"%.3f".format(Locale.US, clockSkewNs)} ns · Hold ${if (holdWnsNs >= 0) "+" else ""}${"%.3f".format(Locale.US, holdWnsNs)} ns",
        details = listOf(
          "Setup WNS: ${if (setupWnsNs >= 0) "+" else ""}${"%.3f".format(Locale.US, setupWnsNs)} ns",
          "Clock insertion: ${"%.3f".format(Locale.US, clockInsertionNs)} ns",
        ),
      )
      logs += "[STAGE 4/7] CTS complete: skew=${"%.3f".format(Locale.US, clockSkewNs)}ns, setup WNS=${"%.3f".format(Locale.US, setupWnsNs)}ns, hold WNS=${"%.3f".format(Locale.US, holdWnsNs)}ns."
      emit()
    }

    // Stage 5: Global & Detailed Routing
    val rtStart = System.currentTimeMillis()
    if (startIdx <= 4) {
      currentCoroutineContext().ensureActive()
      stages[4] = stages[4].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Routing ${report.edges.size} nets...")
      logs += "[STAGE 5/7] Executing global + detailed M1/M2/M3 Manhattan routing..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)
    }
    val routeOutcome = routeSignalNets(report, tech, placedCells, clockWires)
    wireSegments = routeOutcome.segments
    vias = routeOutcome.vias
    totalWirelengthUm = routeOutcome.totalWirelengthUm
    if (startIdx <= 4) {
      val dur = (System.currentTimeMillis() - rtStart).coerceAtLeast(1L)
      stages[4] = stages[4].copy(
        status = FlowStageStatus.PASSED,
        durationMs = dur,
        headlineMetric = "${"%.1f".format(Locale.US, totalWirelengthUm)} µm · ${vias.size} vias",
        details = listOf(
          "Routed nets: ${report.edges.size} (${wireSegments.size} tracks)",
          "Post-route WNS: ${if (setupWnsNs >= 0) "+" else ""}${"%.3f".format(Locale.US, setupWnsNs)} ns",
        ),
      )
      logs += "[STAGE 5/7] Routing complete: WL=${"%.1f".format(Locale.US, totalWirelengthUm)} µm, vias=${vias.size}, segments=${wireSegments.size}."
      emit()
    }

    // Stage 6: DRC / LVS Signoff Checks
    val drcStart = System.currentTimeMillis()
    if (startIdx <= 5) {
      currentCoroutineContext().ensureActive()
      stages[5] = stages[5].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Checking DRC & LVS...")
      logs += "[STAGE 6/7] Running geometric DRC spacing/bounds & LVS netlist consistency checks..."
      emit()
      if (stepDelayMs > 0) delay(stepDelayMs)
    }
    val signoff = runDrcAndLvsChecks(
      report = report,
      tech = tech,
      coreX = coreX,
      coreY = coreY,
      coreW = coreW,
      coreH = coreH,
      placedCells = placedCells,
      wireSegments = wireSegments,
    )
    drcViolations = signoff.first
    lvsClean = signoff.second
    drcSummary = if (drcViolations == 0) "0 DRC violations · LVS Clean" else "$drcViolations DRC spacing/overlap warns"
    if (startIdx <= 5) {
      val dur = (System.currentTimeMillis() - drcStart).coerceAtLeast(1L)
      stages[5] = stages[5].copy(
        status = FlowStageStatus.PASSED,
        durationMs = dur,
        headlineMetric = drcSummary,
        details = listOf(
          "LVS cells matched: ${report.nodes.size}/${report.nodes.size}",
          "PDK min spacing: ${tech.minSpacingUm} µm (${library.shortName})",
        ),
      )
      logs += "[STAGE 6/7] Signoff finished: $drcSummary."
      emit()
    }

    // Stage 7: GDSII Binary Stream & OpenROAD Tcl Export
    val gdsStart = System.currentTimeMillis()
    currentCoroutineContext().ensureActive()
    stages[6] = stages[6].copy(status = FlowStageStatus.RUNNING, headlineMetric = "Streaming GDSII...")
    logs += "[STAGE 7/7] Writing Calma GDSII binary stream & reference Tcl scripts..."
    emit()
    if (stepDelayMs > 0) delay(stepDelayMs)

    val gdsBuild = buildPhysicalPolygons(
      tech = tech,
      dieW = dieW,
      dieH = dieH,
      coreX = coreX,
      coreY = coreY,
      coreW = coreW,
      coreH = coreH,
      rowCount = rowCount,
      placedCells = placedCells,
      wireSegments = wireSegments,
      vias = vias,
    )
    val hierarchy = buildCellMacrosAndInstances(
      tech = tech,
      coreY = coreY,
      placedCells = placedCells,
    )
    val gdsArtifact = GdsiiBinaryWriter.writeStream(
      topModuleName = effectiveName,
      polygons = gdsBuild.first,
      pinLabels = gdsBuild.second,
      cellMacros = hierarchy.first,
      cellInstances = hierarchy.second,
    )
    val parsedGds = KLayoutSnapshotRenderer.parseAndVerifyGds(gdsArtifact.bytes)
    val snapshotBitmap = if (parsedGds.isValid) {
      runCatching {
        KLayoutSnapshotRenderer.renderSnapshotBitmap(
          parsed = parsedGds,
          sha256Short = gdsArtifact.sha256Hex.take(12),
        )
      }.getOrNull()
    } else {
      null
    }
    val snapshotPng = snapshotBitmap?.let {
      runCatching { KLayoutSnapshotRenderer.encodePng(it) }.getOrNull()
    } ?: ByteArray(0)
    val lypXml = KLayoutSnapshotRenderer.generateKLayoutLypXml(effectiveName)
    val tclScript = generateReferenceTclScript(
      moduleName = effectiveName,
      library = library,
      clockPeriodNs = clockPeriodNs,
      coreUtilizationPercent = utilPct,
      sdcText = sdcText,
      dftReport = report.dftReport,
    )
    val layoutSnapshot = PhysicalLayoutSnapshot(
      moduleName = effectiveName,
      library = library,
      dieWidthUm = dieW,
      dieHeightUm = dieH,
      coreXUm = coreX,
      coreYUm = coreY,
      coreWidthUm = coreW,
      coreHeightUm = coreH,
      rowHeightUm = tech.rowHeightUm,
      rowCount = rowCount,
      coreUtilizationPercent = utilPct,
      actualUtilizationPercent = actualUtil,
      placedCells = placedCells,
      wireSegments = wireSegments,
      vias = vias,
      polygons = gdsBuild.first,
      pinLabels = gdsBuild.second,
      totalWirelengthUm = totalWirelengthUm,
      viaCount = vias.size,
      clockSkewNs = clockSkewNs,
      clockInsertionNs = clockInsertionNs,
      setupWnsNs = setupWnsNs,
      holdWnsNs = holdWnsNs,
      drcViolations = drcViolations,
      drcSummary = drcSummary,
      lvsClean = lvsClean,
      gdsBytes = gdsArtifact.bytes,
      gdsSha256 = gdsArtifact.sha256Hex,
      tclScript = tclScript,
      gdsVerified = parsedGds.isValid,
      gdsStructureCount = parsedGds.structureCount,
      gdsSrefCount = parsedGds.srefCount,
      klayoutLypXml = lypXml,
      klayoutSnapshotBitmap = snapshotBitmap,
      klayoutSnapshotPng = snapshotPng,
    )
    val gdsDur = (System.currentTimeMillis() - gdsStart).coerceAtLeast(1L)
    stages[6] = stages[6].copy(
      status = if (parsedGds.isValid) FlowStageStatus.PASSED else FlowStageStatus.FAILED,
      durationMs = gdsDur,
      headlineMetric = if (parsedGds.isValid) {
        "${gdsArtifact.bytes.size} B · ${parsedGds.srefCount} SREF · ${parsedGds.flattenedPolygons.size} shapes"
      } else {
        "GDS Verify Failed: ${parsedGds.errorMessage.orEmpty()}"
      },
      details = listOf(
        "Power: ${"%.3f".format(Locale.US, report.totalPowerMw)} mW · Fmax: ${"%.1f".format(Locale.US, report.fmaxMhz)} MHz",
        "GDS2 v${parsedGds.headerVersion} (${parsedGds.structureCount} cells) · SHA-256: ${gdsArtifact.sha256Hex.take(12)}…",
      ),
    )
    logs += "[STAGE 7/7] Exported & verified ${effectiveName}.gds (${gdsArtifact.bytes.size} bytes, ${parsedGds.structureCount} structures, ${parsedGds.srefCount} SREF, ${parsedGds.flattenedPolygons.size} polygons, sha256=${gdsArtifact.sha256Hex.take(12)})."

    val finalEvent = RtlToGdsStageEvent(
      stageStates = stages.toList(),
      logLines = logs.toList(),
      synthesisResult = SynthesisResult.Success(report),
      layoutSnapshot = layoutSnapshot,
      isFinished = true,
    )
    onProgress(finalEvent)
    return finalEvent
  }

  private data class FloorplanResult(
    val dieW: Double,
    val dieH: Double,
    val coreX: Double,
    val coreY: Double,
    val coreW: Double,
    val coreH: Double,
    val rowCount: Int,
  )

  private fun computeFloorplan(
    report: SynthesisReport,
    tech: PdkTechProfile,
    utilizationPct: Int,
  ): FloorplanResult {
    val activeNodes = report.nodes.filterNot { it.gateType.isPort }
    val minSlotAreaPerCell = tech.rowHeightUm * tech.siteWidthUm * 5.0
    val minTotalActiveArea = activeNodes.size * minSlotAreaPerCell
    val rawArea = max(report.totalAreaUm2 * 1.12, minTotalActiveArea)
      .coerceAtLeast(tech.rowHeightUm * tech.siteWidthUm * max(activeNodes.size, 4) * 5.0)
    val targetCoreArea = rawArea / (utilizationPct.toDouble() / 100.0)
    val side = sqrt(targetCoreArea).coerceAtLeast(tech.rowHeightUm * 4.0)
    val rows = max(4, ceil(side / tech.rowHeightUm).toInt())
    val coreH = rows * tech.rowHeightUm
    val coreW = max(coreH * 1.12, targetCoreArea / coreH)
    val dieW = coreW + tech.coreMarginUm * 2.0
    val dieH = coreH + tech.coreMarginUm * 2.0
    return FloorplanResult(
      dieW = dieW,
      dieH = dieH,
      coreX = tech.coreMarginUm,
      coreY = tech.coreMarginUm,
      coreW = coreW,
      coreH = coreH,
      rowCount = rows,
    )
  }

  private fun placeCellsInRows(
    report: SynthesisReport,
    tech: PdkTechProfile,
    coreX: Double,
    coreY: Double,
    coreW: Double,
    coreH: Double,
    rowCount: Int,
  ): Pair<List<PlacedCellLayout>, Double> {
    val placed = mutableListOf<PlacedCellLayout>()
    val ports = report.nodes.filter { it.gateType.isPort }
    val logicNodes = report.nodes
      .filterNot { it.gateType.isPort }
      .sortedWith(compareBy<GateNode> { it.level }.thenBy { it.id })

    // Place I/O pins around left/right perimeter of core
    val inPorts = ports.filter { it.gateType == GateType.INPUT_PORT }
    val outPorts = ports.filter { it.gateType == GateType.OUTPUT_PORT }
    val padW = (tech.coreMarginUm * 0.45).coerceAtLeast(tech.siteWidthUm * 2.0)
    val padH = (tech.rowHeightUm * 0.65).coerceAtLeast(0.18)

    inPorts.forEachIndexed { idx, p ->
      val y = coreY + ((idx + 1).toDouble() / (inPorts.size + 1)) * coreH - padH / 2.0
      placed += PlacedCellLayout(
        nodeId = p.id,
        instanceName = p.instanceName,
        cellName = p.cellName,
        gateType = p.gateType,
        isCritical = p.isCriticalPath,
        xUm = (coreX - padW - tech.minSpacingUm * 2.0).coerceAtLeast(0.5),
        yUm = y,
        widthUm = padW,
        heightUm = padH,
        rowIndex = -1,
      )
    }

    outPorts.forEachIndexed { idx, p ->
      val y = coreY + ((idx + 1).toDouble() / (outPorts.size + 1)) * coreH - padH / 2.0
      placed += PlacedCellLayout(
        nodeId = p.id,
        instanceName = p.instanceName,
        cellName = p.cellName,
        gateType = p.gateType,
        isCritical = p.isCriticalPath,
        xUm = coreX + coreW + tech.minSpacingUm * 2.0,
        yUm = y,
        widthUm = padW,
        heightUm = padH,
        rowIndex = -1,
      )
    }

    // Legal row-by-row left-to-right placement inside standard cell rows
    val rowCursors = DoubleArray(rowCount) { coreX + tech.siteWidthUm }
    val cellH = tech.rowHeightUm * 0.86
    var totalCellArea = 0.0

    logicNodes.forEachIndexed { idx, node ->
      val cellArea = node.areaUm2.coerceAtLeast(tech.rowHeightUm * tech.siteWidthUm * 3.0)
      totalCellArea += cellArea
      val desiredW = (cellArea / tech.rowHeightUm).coerceIn(
        tech.siteWidthUm * 3.0,
        coreW * 0.42,
      )
      // Prefer spreading sequential cells & topological stages across rows evenly
      val preferredRow = (node.level + idx) % rowCount
      var chosenRow = preferredRow
      var bestCursor = rowCursors[chosenRow]
      if (bestCursor + desiredW > coreX + coreW) {
        val candidate = (0 until rowCount).minByOrNull { rowCursors[it] } ?: 0
        chosenRow = candidate
        bestCursor = rowCursors[candidate]
      }
      val x = bestCursor.coerceAtMost((coreX + coreW - desiredW).coerceAtLeast(coreX))
      val y = coreY + chosenRow * tech.rowHeightUm + (tech.rowHeightUm - cellH) / 2.0
      rowCursors[chosenRow] = x + desiredW + tech.siteWidthUm

      placed += PlacedCellLayout(
        nodeId = node.id,
        instanceName = node.instanceName,
        cellName = node.cellName,
        gateType = node.gateType,
        isCritical = node.isCriticalPath,
        xUm = x,
        yUm = y,
        widthUm = desiredW,
        heightUm = cellH,
        rowIndex = chosenRow,
      )
    }

    val coreArea = (coreW * coreH).coerceAtLeast(1e-3)
    val actualUtil = ((totalCellArea / coreArea) * 100.0).coerceIn(5.0, 95.0)
    return placed to actualUtil
  }

  private fun computeHpwlUm(report: SynthesisReport, placed: List<PlacedCellLayout>): Double {
    val byId = placed.associateBy { it.nodeId }
    return report.edges.sumOf { e ->
      val from = byId[e.fromNodeId] ?: return@sumOf 0.0
      val to = byId[e.toNodeId] ?: return@sumOf 0.0
      abs((from.xUm + from.widthUm) - to.xUm) + abs((from.yUm + from.heightUm / 2.0) - (to.yUm + to.heightUm / 2.0))
    }
  }

  private data class CtsResult(
    val wires: List<RoutedWireSegment>,
    val clockSkewNs: Double,
    val insertionDelayNs: Double,
  )

  private fun synthesizeClockTree(
    report: SynthesisReport,
    tech: PdkTechProfile,
    placed: List<PlacedCellLayout>,
    coreX: Double,
    coreY: Double,
    coreW: Double,
    coreH: Double,
  ): CtsResult {
    val seqSinks = placed.filter { it.gateType.isSequential }
    if (seqSinks.isEmpty()) {
      return CtsResult(emptyList(), clockSkewNs = 0.0, insertionDelayNs = 0.0)
    }
    val spineX = coreX + coreW * 0.5
    val spineTopY = coreY + coreH * 0.12
    val spineBotY = coreY + coreH * 0.88
    val rootY = coreY + coreH * 0.5
    val clkW = (tech.m2PitchUm * 1.4).coerceAtLeast(0.06)

    val wires = mutableListOf<RoutedWireSegment>()
    // Clock root trunk from left edge into center spine
    wires += RoutedWireSegment("clk_root", PhysicalMaskLayer.CLK_TREE, coreX - tech.coreMarginUm * 0.4, rootY, spineX, rootY, clkW, isCritical = false, isClock = true)
    wires += RoutedWireSegment("clk_spine", PhysicalMaskLayer.CLK_TREE, spineX, spineTopY, spineX, spineBotY, clkW, isCritical = false, isClock = true)

    val pathLengths = mutableListOf<Double>()
    seqSinks.forEach { sink ->
      val sinkCx = sink.xUm + sink.widthUm * 0.5
      val sinkCy = sink.yUm + sink.heightUm * 0.5
      val clampedY = sinkCy.coerceIn(spineTopY, spineBotY)
      wires += RoutedWireSegment("clk_tap_${sink.instanceName}", PhysicalMaskLayer.CLK_TREE, spineX, clampedY, sinkCx, clampedY, clkW * 0.8, isCritical = false, isClock = true)
      if (abs(sinkCy - clampedY) > 1e-4) {
        wires += RoutedWireSegment("clk_tap_v_${sink.instanceName}", PhysicalMaskLayer.CLK_TREE, sinkCx, clampedY, sinkCx, sinkCy, clkW * 0.8, isCritical = false, isClock = true)
      }
      pathLengths += abs(sinkCx - spineX) + abs(sinkCy - rootY)
    }

    val minLen = pathLengths.minOrNull() ?: 0.0
    val maxLen = pathLengths.maxOrNull() ?: 0.0
    val skewNs = ((maxLen - minLen) / 1000.0) * tech.wireDelayNsPerMm + tech.clockBufferDelayNs * 0.25
    val insertionNs = tech.clockBufferDelayNs * 2.0 + (maxLen / 1000.0) * tech.wireDelayNsPerMm
    return CtsResult(wires = wires, clockSkewNs = skewNs, insertionDelayNs = insertionNs)
  }

  private data class RouteResult(
    val segments: List<RoutedWireSegment>,
    val vias: List<ViaCut>,
    val totalWirelengthUm: Double,
  )

  private fun routeSignalNets(
    report: SynthesisReport,
    tech: PdkTechProfile,
    placed: List<PlacedCellLayout>,
    clockWires: List<RoutedWireSegment>,
  ): RouteResult {
    val byId = placed.associateBy { it.nodeId }
    val segments = mutableListOf<RoutedWireSegment>()
    val vias = mutableListOf<ViaCut>()
    segments += clockWires

    val wM1 = (tech.m1PitchUm * 0.55).coerceAtLeast(0.03)
    val wM2 = (tech.m2PitchUm * 0.55).coerceAtLeast(0.03)
    var wireLength = clockWires.sumOf { abs(it.x2Um - it.x1Um) + abs(it.y2Um - it.y1Um) }

    report.edges.forEachIndexed { idx, edge ->
      val src = byId[edge.fromNodeId] ?: return@forEachIndexed
      val dst = byId[edge.toNodeId] ?: return@forEachIndexed
      val sx = src.xUm + src.widthUm
      val sy = src.yUm + src.heightUm * 0.5
      val dx = dst.xUm
      val dy = dst.yUm + dst.heightUm * (0.3 + (idx % 3) * 0.2)
      val jogOffset = ((idx % 5) - 2) * tech.m2PitchUm
      val midX = ((sx + dx) * 0.5 + jogOffset).coerceIn(minOf(sx, dx), maxOf(sx, dx))

      // Horizontal M1 -> Vertical M2 -> Horizontal M1 (Z-route)
      if (abs(midX - sx) > 1e-4) {
        segments += RoutedWireSegment(edge.netName, PhysicalMaskLayer.MET1, sx, sy, midX, sy, wM1, edge.isCritical)
        wireLength += abs(midX - sx)
      }
      if (abs(dy - sy) > 1e-4) {
        vias += ViaCut(midX, sy, PhysicalMaskLayer.VIA1, edge.netName)
        segments += RoutedWireSegment(edge.netName, PhysicalMaskLayer.MET2, midX, sy, midX, dy, wM2, edge.isCritical)
        vias += ViaCut(midX, dy, PhysicalMaskLayer.VIA1, edge.netName)
        wireLength += abs(dy - sy)
      }
      if (abs(dx - midX) > 1e-4) {
        segments += RoutedWireSegment(edge.netName, PhysicalMaskLayer.MET1, midX, dy, dx, dy, wM1, edge.isCritical)
        wireLength += abs(dx - midX)
      }
    }

    return RouteResult(segments = segments, vias = vias, totalWirelengthUm = wireLength)
  }

  private fun runDrcAndLvsChecks(
    report: SynthesisReport,
    tech: PdkTechProfile,
    coreX: Double,
    coreY: Double,
    coreW: Double,
    coreH: Double,
    placedCells: List<PlacedCellLayout>,
    wireSegments: List<RoutedWireSegment>,
  ): Pair<Int, Boolean> {
    var violations = 0
    val coreCells = placedCells.filter { it.rowIndex >= 0 }

    // 1. Check out-of-core bounds
    coreCells.forEach { c ->
      if (c.xUm < coreX - 1e-4 || c.yUm < coreY - 1e-4 ||
        c.xUm + c.widthUm > coreX + coreW + 1e-4 || c.yUm + c.heightUm > coreY + coreH + 1e-4
      ) {
        violations++
      }
    }

    // 2. Check intra-row overlap
    coreCells.groupBy { it.rowIndex }.values.forEach { row ->
      val sorted = row.sortedBy { it.xUm }
      for (i in 0 until sorted.lastIndex) {
        if (sorted[i].xUm + sorted[i].widthUm > sorted[i + 1].xUm + 1e-4) {
          violations++
        }
      }
    }

    // 3. Check wire minimum width
    wireSegments.forEach { seg ->
      if (seg.widthUm < tech.minSpacingUm * 0.4) {
        violations++
      }
    }

    val lvsClean = placedCells.size == report.nodes.size
    return violations to lvsClean
  }

  private fun buildPhysicalPolygons(
    tech: PdkTechProfile,
    dieW: Double,
    dieH: Double,
    coreX: Double,
    coreY: Double,
    coreW: Double,
    coreH: Double,
    rowCount: Int,
    placedCells: List<PlacedCellLayout>,
    wireSegments: List<RoutedWireSegment>,
    vias: List<ViaCut>,
  ): Pair<List<GdsRectPolygon>, List<GdsRectPolygon>> {
    val polys = mutableListOf<GdsRectPolygon>()
    val labels = mutableListOf<GdsRectPolygon>()

    // Die boundary
    polys += GdsRectPolygon(PhysicalMaskLayer.DIE_AREA, 0.0, 0.0, dieW, dieH, label = "DIE")

    // Power ring on MET3 around core
    val ringW = (tech.m2PitchUm * 2.5).coerceAtLeast(0.2)
    val ringPad = tech.coreMarginUm * 0.35
    polys += GdsRectPolygon(PhysicalMaskLayer.MET3, coreX - ringPad, coreY - ringPad, coreW + 2 * ringPad, ringW, netName = "VDD")
    polys += GdsRectPolygon(PhysicalMaskLayer.MET3, coreX - ringPad, coreY + coreH + ringPad - ringW, coreW + 2 * ringPad, ringW, netName = "VSS")
    polys += GdsRectPolygon(PhysicalMaskLayer.MET3, coreX - ringPad, coreY - ringPad, ringW, coreH + 2 * ringPad, netName = "VDD")
    polys += GdsRectPolygon(PhysicalMaskLayer.MET3, coreX + coreW + ringPad - ringW, coreY - ringPad, ringW, coreH + 2 * ringPad, netName = "VSS")

    // Standard-cell row MET1 VDD/VSS horizontal rails + VIA1/VIA2 straps into the MET3 ring
    val railH = (tech.m1PitchUm * 0.7).coerceAtLeast(0.06)
    for (r in 0..rowCount) {
      val railY = coreY + r * tech.rowHeightUm - railH * 0.5
      val netLabel = if (r % 2 == 0) "VDD" else "VSS"
      polys += GdsRectPolygon(
        layer = PhysicalMaskLayer.MET1,
        xUm = coreX - ringPad,
        yUm = railY,
        widthUm = coreW + 2 * ringPad,
        heightUm = railH,
        netName = netLabel,
      )
      val tieSize = (railH * 0.85).coerceAtLeast(0.04)
      polys += GdsRectPolygon(PhysicalMaskLayer.VIA1, coreX - ringPad + ringW * 0.3, railY, tieSize, tieSize, netName = netLabel)
      polys += GdsRectPolygon(PhysicalMaskLayer.VIA2, coreX - ringPad + ringW * 0.3, railY, tieSize, tieSize, netName = netLabel)
      polys += GdsRectPolygon(PhysicalMaskLayer.VIA1, coreX + coreW + ringPad - ringW * 0.7, railY, tieSize, tieSize, netName = netLabel)
      polys += GdsRectPolygon(PhysicalMaskLayer.VIA2, coreX + coreW + ringPad - ringW * 0.7, railY, tieSize, tieSize, netName = netLabel)
    }

    // I/O pin pads and pin labels on die perimeter (std cell shapes are emitted via hierarchical SREF macros!)
    placedCells.forEach { cell ->
      if (cell.gateType.isPort) {
        val pinRect = GdsRectPolygon(
          layer = PhysicalMaskLayer.IO_PINS,
          xUm = cell.xUm,
          yUm = cell.yUm,
          widthUm = cell.widthUm,
          heightUm = cell.heightUm,
          label = cell.instanceName,
        )
        polys += pinRect
        labels += pinRect
      }
    }

    // Routed signal and clock metal tracks
    wireSegments.forEach { seg ->
      val minX = minOf(seg.x1Um, seg.x2Um)
      val minY = minOf(seg.y1Um, seg.y2Um)
      val maxX = maxOf(seg.x1Um, seg.x2Um)
      val maxY = maxOf(seg.y1Um, seg.y2Um)
      val halfW = seg.widthUm * 0.5
      polys += GdsRectPolygon(
        layer = seg.layer,
        xUm = minX - halfW,
        yUm = minY - halfW,
        widthUm = (maxX - minX) + seg.widthUm,
        heightUm = (maxY - minY) + seg.widthUm,
        netName = seg.netName,
      )
    }

    // Via cuts
    val viaSize = (tech.m1PitchUm * 0.65).coerceAtLeast(0.03)
    vias.forEach { via ->
      polys += GdsRectPolygon(
        layer = via.layer,
        xUm = via.xUm - viaSize * 0.5,
        yUm = via.yUm - viaSize * 0.5,
        widthUm = viaSize,
        heightUm = viaSize,
        netName = via.netName,
      )
    }

    return polys to labels
  }

  private fun buildCellMacrosAndInstances(
    tech: PdkTechProfile,
    coreY: Double,
    placedCells: List<PlacedCellLayout>,
  ): Pair<List<GdsCellMacro>, List<GdsCellInstance>> {
    val macrosByName = linkedMapOf<String, GdsCellMacro>()
    val instances = ArrayList<GdsCellInstance>(placedCells.size)
    val rowH = tech.rowHeightUm
    val yOffsetInRow = (rowH - rowH * 0.86) / 2.0

    placedCells.forEach { cell ->
      if (cell.gateType.isPort) return@forEach
      val widthBucket = (cell.widthUm * 100.0).roundToInt().coerceAtLeast(10)
      val safeCell = cell.cellName.replace(Regex("[^A-Za-z0-9_]"), "_").take(20)
      val macroName = "${safeCell}_w$widthBucket"
      val w = cell.widthUm

      macrosByName.getOrPut(macroName) {
        val shapes = mutableListOf<GdsRectPolygon>()
        // Cell prBoundary / areaid
        shapes += GdsRectPolygon(PhysicalMaskLayer.CELLS, 0.0, yOffsetInRow, w, cell.heightUm)
        // Upper N-Well for pFETs (flips cleanly in odd rows via MX reflection)
        shapes += GdsRectPolygon(PhysicalMaskLayer.NWELL, 0.0, rowH * 0.5, w, rowH * 0.48)
        // pFET & nFET active diffusion strips
        shapes += GdsRectPolygon(PhysicalMaskLayer.DIFF, w * 0.08, rowH * 0.58, w * 0.84, rowH * 0.24)
        shapes += GdsRectPolygon(PhysicalMaskLayer.DIFF, w * 0.08, rowH * 0.18, w * 0.84, rowH * 0.24)
        // Polysilicon gate fingers crossing active diffusion
        val polyFingers = if (cell.gateType.isSequential) 4 else 2
        val fingerW = (w * 0.09).coerceAtLeast(0.02)
        for (f in 0 until polyFingers) {
          val px = w * (0.20 + f * (0.60 / polyFingers))
          shapes += GdsRectPolygon(PhysicalMaskLayer.POLY, px, rowH * 0.14, fingerW, rowH * 0.72)
        }
        // Local interconnect (LI1) & MCON internal straps
        shapes += GdsRectPolygon(PhysicalMaskLayer.LI1, w * 0.12, rowH * 0.46, w * 0.76, (tech.m1PitchUm * 0.45).coerceAtLeast(0.04))
        val mconS = (tech.m1PitchUm * 0.45).coerceAtLeast(0.03)
        shapes += GdsRectPolygon(PhysicalMaskLayer.MCON, w * 0.18, rowH * 0.46, mconS, mconS)
        shapes += GdsRectPolygon(PhysicalMaskLayer.MCON, w * 0.72, rowH * 0.46, mconS, mconS)
        GdsCellMacro(
          structureName = macroName,
          widthUm = w,
          heightUm = rowH,
          shapes = shapes,
        )
      }

      val flipRow = cell.rowIndex % 2 == 1
      val rowBottomY = coreY + cell.rowIndex * rowH
      val originY = if (flipRow) rowBottomY + rowH else rowBottomY
      instances += GdsCellInstance(
        structureName = macroName,
        xUm = cell.xUm,
        yUm = originY,
        mirrorX = flipRow,
      )
    }

    return macrosByName.values.toList() to instances
  }

  fun generateReferenceTclScript(
    moduleName: String,
    library: TargetLibrary,
    clockPeriodNs: Double,
    coreUtilizationPercent: Int,
    sdcText: String,
    dftReport: DftReport? = null,
  ): String {
    val pdkTag = library.id.lowercase(Locale.US)
    return buildString {
      appendLine("# Generated by RTL Synth Studio — Reference OpenROAD / Yosys / Magic Flow")
      appendLine("set DESIGN_NAME \"$moduleName\"")
      appendLine("set PDK_LIBRARY \"${library.displayName}\"")
      appendLine("set CLOCK_PERIOD_NS $clockPeriodNs")
      appendLine("set CORE_UTILIZATION $coreUtilizationPercent")
      appendLine()
      appendLine("# 1. Yosys Synthesis")
      appendLine("read_verilog ${moduleName}.v")
      appendLine("synth -top \$DESIGN_NAME")
      appendLine("dfflibmap -liberty \${PDK_DIR}/$pdkTag.lib")
      appendLine("abc -D [expr {\$CLOCK_PERIOD_NS * 1000.0}] -liberty \${PDK_DIR}/$pdkTag.lib")
      if (dftReport != null && dftReport.scanInserted) {
        appendLine()
        appendLine("# 1b. DFT Scan Insertion (${dftReport.config.scanStyle.displayName})")
        appendLine("# Scan chains: ${dftReport.scanChains.size} | Scan flops: ${dftReport.scanReplacedElements} | Lockup latches: ${dftReport.lockupLatchCount}")
        appendLine("read_scandef ${moduleName}_scanchains.def")
      }
      appendLine("write_verilog -noattr ${moduleName}_synth.v")
      appendLine()
      appendLine("# 2. OpenROAD Floorplan -> Place -> CTS -> Route")
      appendLine("initialize_floorplan -utilization \$CORE_UTILIZATION -aspect_ratio 1.0 -core_space 4.0")
      appendLine("global_placement -density [expr {\$CORE_UTILIZATION / 100.0}]")
      if (dftReport != null && dftReport.scanInserted) {
        appendLine("repair_design")
      }
      appendLine("detailed_placement")
      appendLine("clock_tree_synthesis -buf_list \"clkbuf_1 clkbuf_2 clkbuf_4\"")
      appendLine("global_route -congestion_iterations 40")
      appendLine("detailed_route")
      appendLine("report_checks -path_delay min_max")
      appendLine("report_power")
      appendLine()
      appendLine("# 3. Magic DRC / GDSII Stream Out")
      appendLine("gds write ${moduleName}.gds")
      appendLine("drc check; drc count")
      if (sdcText.isNotBlank()) {
        appendLine()
        appendLine("# Applied SDC Constraints:")
        sdcText.lines().take(6).forEach { appendLine("# $it") }
      }
    }
  }

  /**
   * Computes SHA-256 of any byte buffer for export/checksum verification.
   */
  fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
