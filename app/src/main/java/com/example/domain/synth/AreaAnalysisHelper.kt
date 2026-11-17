package com.example.domain.synth

import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary

import java.util.Locale

enum class CellCategory(val label: String) {
  LOGIC_GATES("Logic Gates"),
  FLIP_FLOPS("Flip-Flops"),
  LATCHES("Latches"),
  MULTIPLEXERS("Multiplexers"),
  BUFFERS("Buffers"),
  INVERTERS("Inverters"),
  ARITHMETIC("Arithmetic / LUT"),
  OTHER("Other Std Cells"),
}

enum class ResourceAreaBucket(val label: String, val shortLabel: String) {
  LUTS("LUTs", "LUT"),
  FLIP_FLOPS("Flip-Flops", "FF"),
  DSPS("DSPs", "DSP"),
}

data class ResourceAreaSlice(
  val bucket: ResourceAreaBucket,
  val label: String,
  val areaUm2: Double,
  val cellCount: Int,
  val areaPct: Double,
  val cellTypesSummary: String,
  val instanceNodeIds: List<String>,
)

data class CellAreaStat(
  val cellName: String,
  val gateType: GateType,
  val category: CellCategory,
  val count: Int,
  val singleCellAreaUm2: Double,
  val totalAreaUm2: Double,
  val areaPercentage: Double,
  val instanceNodeIds: List<String>,
)

data class CategoryAreaSummary(
  val category: CellCategory,
  val cellCount: Int,
  val areaUm2: Double,
  val areaPct: Double,
)

data class AreaAnalysisSummary(
  val totalAreaUm2: Double,
  val totalCellCount: Int,
  val combinationalAreaUm2: Double,
  val combinationalCount: Int,
  val sequentialAreaUm2: Double,
  val sequentialCount: Int,
  val bufferAreaUm2: Double,
  val bufferCount: Int,
  val inverterAreaUm2: Double,
  val inverterCount: Int,
  val cellStats: List<CellAreaStat>,
  val categorySummaries: List<CategoryAreaSummary>,
  val resourceSlices: List<ResourceAreaSlice>,
  val hasPhysicalPnR: Boolean,
  val dieAreaUm2: Double?,
  val coreAreaUm2: Double?,
  val actualUtilizationPct: Double?,
)

data class RunAreaDelta(
  val baselineRun: SynthesisRun,
  val currentAreaUm2: Double,
  val deltaAreaUm2: Double,
  val deltaAreaPct: Double,
  val deltaCellCount: Int,
  val sameLibrary: Boolean,
)

object AreaAnalysisHelper {

  fun classifyGate(gateType: GateType): CellCategory = when (gateType) {
    GateType.AND2, GateType.OR2, GateType.NAND2, GateType.NOR2, GateType.XOR2, GateType.XNOR2 -> CellCategory.LOGIC_GATES
    GateType.DFF, GateType.DFFR, GateType.SDFF, GateType.SDFFR -> CellCategory.FLIP_FLOPS
    GateType.LOCKUP_LATCH -> CellCategory.LATCHES
    GateType.MUX2 -> CellCategory.MULTIPLEXERS
    GateType.BUF -> CellCategory.BUFFERS
    GateType.INV -> CellCategory.INVERTERS
    GateType.ADD_CELL, GateType.SUB_CELL, GateType.CMP_CELL, GateType.MUL_CELL,
    GateType.LUT4, GateType.LUT6, GateType.CARRY4,
    -> CellCategory.ARITHMETIC
    GateType.INPUT_PORT, GateType.OUTPUT_PORT, GateType.CONST -> CellCategory.OTHER
  }

  fun classifyResourceBucket(
    gateType: GateType,
    cellName: String = "",
  ): ResourceAreaBucket = when (gateType) {
    GateType.DFF, GateType.DFFR, GateType.SDFF, GateType.SDFFR, GateType.LOCKUP_LATCH -> ResourceAreaBucket.FLIP_FLOPS
    GateType.MUL_CELL -> ResourceAreaBucket.DSPS
    else -> {
      val upper = cellName.uppercase(Locale.ROOT)
      if (upper.contains("DSP") || upper.contains("MUL")) {
        ResourceAreaBucket.DSPS
      } else {
        ResourceAreaBucket.LUTS
      }
    }
  }

  fun analyze(
    report: SynthesisReport,
    layout: PhysicalLayoutSnapshot? = null,
  ): AreaAnalysisSummary {
    val nonPortNodes = report.nodes.filter { !it.gateType.isPort }
    val totalArea = nonPortNodes.sumOf { it.areaUm2 }.coerceAtLeast(report.totalAreaUm2)
    val denom = totalArea.coerceAtLeast(1e-9)

    val combNodes = nonPortNodes.filter { !it.gateType.isSequential }
    val seqNodes = nonPortNodes.filter { it.gateType.isSequential }
    val bufNodes = nonPortNodes.filter { it.gateType == GateType.BUF }
    val invNodes = nonPortNodes.filter { it.gateType == GateType.INV }

    val stats = nonPortNodes
      .groupBy { it.cellName to it.gateType }
      .entries
      .map { (key, list) ->
        val sumArea = list.sumOf { it.areaUm2 }
        CellAreaStat(
          cellName = key.first,
          gateType = key.second,
          category = classifyGate(key.second),
          count = list.size,
          singleCellAreaUm2 = if (list.isNotEmpty()) sumArea / list.size else 0.0,
          totalAreaUm2 = sumArea,
          areaPercentage = if (totalArea > 0.0) (sumArea / denom) * 100.0 else 0.0,
          instanceNodeIds = list.map { it.id },
        )
      }
      .sortedByDescending { it.totalAreaUm2 }

    val catSummaries = CellCategory.entries.mapNotNull { category ->
      val matching = stats.filter { it.category == category }
      if (matching.isEmpty()) null
      else {
        val catArea = matching.sumOf { it.totalAreaUm2 }
        CategoryAreaSummary(
          category = category,
          cellCount = matching.sumOf { it.count },
          areaUm2 = catArea,
          areaPct = if (totalArea > 0.0) (catArea / denom) * 100.0 else 0.0,
        )
      }
    }.sortedByDescending { it.areaUm2 }

    val groupedByBucket = nonPortNodes.groupBy { classifyResourceBucket(it.gateType, it.cellName) }
    val sliceTotalArea = nonPortNodes.sumOf { it.areaUm2 }
    val sliceDenom = sliceTotalArea.coerceAtLeast(1e-9)
    val resourceSlices = ResourceAreaBucket.entries.map { bucket ->
      val nodes = groupedByBucket[bucket].orEmpty()
      val bucketArea = nodes.sumOf { it.areaUm2 }
      val typeSummary = if (nodes.isEmpty()) {
        "0 mapped instances"
      } else {
        nodes.groupingBy { it.cellName }
          .eachCount()
          .entries
          .sortedByDescending { it.value }
          .take(4)
          .joinToString(" · ") { "${it.key} ×${it.value}" }
      }
      ResourceAreaSlice(
        bucket = bucket,
        label = bucket.label,
        areaUm2 = bucketArea,
        cellCount = nodes.size,
        areaPct = if (sliceTotalArea > 0.0) (bucketArea / sliceDenom) * 100.0 else 0.0,
        cellTypesSummary = typeSummary,
        instanceNodeIds = nodes.map { it.id },
      )
    }

    return AreaAnalysisSummary(
      totalAreaUm2 = totalArea,
      totalCellCount = nonPortNodes.size,
      combinationalAreaUm2 = combNodes.sumOf { it.areaUm2 },
      combinationalCount = combNodes.size,
      sequentialAreaUm2 = seqNodes.sumOf { it.areaUm2 },
      sequentialCount = seqNodes.size,
      bufferAreaUm2 = bufNodes.sumOf { it.areaUm2 },
      bufferCount = bufNodes.size,
      inverterAreaUm2 = invNodes.sumOf { it.areaUm2 },
      inverterCount = invNodes.size,
      cellStats = stats,
      categorySummaries = catSummaries,
      resourceSlices = resourceSlices,
      hasPhysicalPnR = layout != null,
      dieAreaUm2 = layout?.let { it.dieWidthUm * it.dieHeightUm },
      coreAreaUm2 = layout?.let { it.coreWidthUm * it.coreHeightUm },
      actualUtilizationPct = layout?.actualUtilizationPercent,
    )
  }

  fun compareRuns(
    current: SynthesisReport,
    baseline: SynthesisRun,
  ): RunAreaDelta {
    val deltaArea = current.totalAreaUm2 - baseline.areaUm2
    val deltaPct = if (baseline.areaUm2 > 1e-6) {
      (deltaArea / baseline.areaUm2) * 100.0
    } else {
      0.0
    }
    return RunAreaDelta(
      baselineRun = baseline,
      currentAreaUm2 = current.totalAreaUm2,
      deltaAreaUm2 = deltaArea,
      deltaAreaPct = deltaPct,
      deltaCellCount = current.cellCount - baseline.cellCount.toInt(),
      sameLibrary = TargetLibrary.fromId(baseline.targetLibrary) == current.targetLibrary,
    )
  }
}
