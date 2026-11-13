package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class TargetLibrary(
  val id: String,
  val displayName: String,
  val shortName: String,
  val processNode: String,
  val isFpga: Boolean,
) {
  ASAP7_7NM(
    id = "ASAP7_7NM",
    displayName = "ASAP7 7nm FinFET",
    shortName = "ASAP7 7nm",
    processNode = "7nm FinFET",
    isFpga = false,
  ),
  SKY130_130NM(
    id = "SKY130_130NM",
    displayName = "Sky130 130nm HD",
    shortName = "Sky130 130nm",
    processNode = "130nm CMOS",
    isFpga = false,
  ),
  ARTIX7_FPGA(
    id = "ARTIX7_FPGA",
    displayName = "Artix-7 FPGA (LUT6)",
    shortName = "Artix-7 LUT6",
    processNode = "28nm 6-LUT",
    isFpga = true,
  );

  companion object {
    fun fromId(id: String): TargetLibrary = entries.firstOrNull { it.id == id } ?: ASAP7_7NM
  }
}

enum class OptimizationGoal(
  val id: String,
  val displayName: String,
  val summary: String,
) {
  BALANCED("BALANCED", "Balanced", "Co-optimizes critical path and silicon area"),
  MIN_AREA("MIN_AREA", "Min Area", "Shares common subexpressions & packs cells tightly"),
  MIN_DELAY("MIN_DELAY", "Min Delay", "Restructures logic cones for minimum timing depth");

  companion object {
    fun fromId(id: String): OptimizationGoal = entries.firstOrNull { it.id == id } ?: BALANCED
  }
}

@IgnoreExtraProperties
data class RtlDesign(
  @DocumentId val id: String = "",
  val userId: String = "",
  val name: String = "",
  val description: String = "",
  val verilogCode: String = "",
  val targetLibrary: String = TargetLibrary.ASAP7_7NM.id,
  val optimizationGoal: String = OptimizationGoal.BALANCED.id,
  val clockPeriodNs: Double = 5.0,
  val lastWnsNs: Double = 0.0,
  val lastAreaUm2: Double = 0.0,
  val lastCellCount: Long = 0L,
  val lastPowerMw: Double = 0.0,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class SynthesisRun(
  @DocumentId val id: String = "",
  val userId: String = "",
  val designId: String = "",
  val designName: String = "",
  val targetLibrary: String = TargetLibrary.ASAP7_7NM.id,
  val optimizationGoal: String = OptimizationGoal.BALANCED.id,
  val clockPeriodNs: Double = 5.0,
  val fmaxMhz: Double = 0.0,
  val wnsNs: Double = 0.0,
  val tnsNs: Double = 0.0,
  val areaUm2: Double = 0.0,
  val cellCount: Long = 0L,
  val lutCount: Long = 0L,
  val dffCount: Long = 0L,
  val powerMw: Double = 0.0,
  val criticalPathSummary: String = "",
  val netlistVerilog: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class RtlWorkspaceDraft(
  @DocumentId val id: String = "autosave",
  val userId: String = "",
  val activeCloudDesignId: String = "",
  val designName: String = "",
  val description: String = "",
  val verilogCode: String = "",
  val sdcText: String = "",
  val targetLibrary: String = TargetLibrary.ASAP7_7NM.id,
  val optimizationGoal: String = OptimizationGoal.BALANCED.id,
  val clockPeriodNs: Double = 5.0,
  val lastWnsNs: Double = 0.0,
  val lastAreaUm2: Double = 0.0,
  val lastCellCount: Long = 0L,
  val lastPowerMw: Double = 0.0,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)
