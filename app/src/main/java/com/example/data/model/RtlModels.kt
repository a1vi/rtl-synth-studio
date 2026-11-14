package com.example.data.model

enum class TargetLibrary(
  val id: String,
  val displayName: String,
  val shortName: String,
  val processNode: String,
  val isFpga: Boolean,
  val nominalVoltage: String = "0.70V",
  val summary: String = "",
) {
  ASAP7_7NM(
    id = "ASAP7_7NM",
    displayName = "ASAP7 7nm FinFET",
    shortName = "ASAP7 7nm",
    processNode = "7nm FinFET",
    isFpga = false,
    nominalVoltage = "0.70V",
    summary = "Predictive 7nm FinFET standard cell PDK for high-Fmax academic & ASIC research",
  ),
  SKY130_130NM(
    id = "SKY130_130NM",
    displayName = "SkyWater SKY130 HD",
    shortName = "SKY130 130nm",
    processNode = "130nm CMOS",
    isFpga = false,
    nominalVoltage = "1.80V",
    summary = "Open-source SkyWater 130nm high-density mixed-signal & ASIC standard cells",
  ),
  GF180MCU_180NM(
    id = "GF180MCU_180NM",
    displayName = "GlobalFoundries GF180MCU",
    shortName = "GF180MCU",
    processNode = "180nm CMOS",
    isFpga = false,
    nominalVoltage = "3.30V",
    summary = "Open-source GF180MCU 5V/3.3V microcontroller & analog-mixed-signal PDK",
  ),
  NANGATE45_45NM(
    id = "NANGATE45_45NM",
    displayName = "Nangate45 OpenCell",
    shortName = "Nangate45",
    processNode = "45nm CMOS",
    isFpga = false,
    nominalVoltage = "1.10V",
    summary = "Industry-standard 45nm open cell library for timing & power benchmarking",
  ),
  ARTIX7_FPGA(
    id = "ARTIX7_FPGA",
    displayName = "Artix-7 FPGA (LUT6)",
    shortName = "Artix-7 LUT6",
    processNode = "28nm 6-LUT",
    isFpga = true,
    nominalVoltage = "1.00V",
    summary = "6-input LUT + CARRY4 + FDRE/DSP48E1 fabric mapping for FPGA prototyping",
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

enum class SynthesisEngineProfile(
  val id: String,
  val displayName: String,
  val shortName: String,
  val summary: String,
) {
  SILICON_FLOW(
    id = "SILICON_FLOW",
    displayName = "SiliconFlow FastMapper v2.5",
    shortName = "SiliconFlow",
    summary = "Low-latency deterministic RTL elaboration & DAG technology mapper",
  ),
  YOSYS_ABC(
    id = "YOSYS_ABC",
    displayName = "ABC Timing-Driven Mapper",
    shortName = "ABC Timing",
    summary = "Aggressive AIG restructuring & delay-oriented gate sizing",
  ),
  AREA_COMPACTOR(
    id = "AREA_COMPACTOR",
    displayName = "Area & Power Compactor",
    shortName = "Compactor",
    summary = "Maximizes common-subexpression sharing and low-leakage cell selection",
  );

  companion object {
    fun fromId(id: String): SynthesisEngineProfile =
      entries.firstOrNull { it.id == id } ?: SILICON_FLOW
  }
}

enum class RtlLanguage(
  val displayName: String,
  val extension: String,
  val badge: String,
) {
  VERILOG("Verilog", "v", "VERILOG"),
  SYSTEM_VERILOG("SystemVerilog", "sv", "SV"),
  VHDL("VHDL", "vhd", "VHDL"),
  SDC("SDC Constraints", "sdc", "SDC"),
  TESTBENCH("Testbench", "v", "TB");

  companion object {
    fun fromFileName(fileName: String): RtlLanguage {
      val lower = fileName.trim().lowercase()
      return when {
        lower.endsWith(".sv") || lower.endsWith(".svh") -> SYSTEM_VERILOG
        lower.endsWith(".vhd") || lower.endsWith(".vhdl") -> VHDL
        lower.endsWith(".sdc") -> SDC
        lower.startsWith("tb_") || lower.contains("_tb.") -> TESTBENCH
        else -> VERILOG
      }
    }

    fun fromId(id: String): RtlLanguage =
      entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: VERILOG
  }
}

data class RtlFileEntry(
  val id: String,
  val name: String,
  val language: RtlLanguage,
  val content: String,
)

enum class ExportFormat(
  val id: String,
  val label: String,
  val extension: String,
) {
  VERILOG_NETLIST("NETLIST_V", "Gate-Level Netlist (.v)", "v"),
  JSON_REPORT("REPORT_JSON", "Engineering Report (.json)", "json"),
  CSV_STA("STA_CSV", "STA Timing Table (.csv)", "csv"),
  TEXT_SUMMARY("SUMMARY_TXT", "Executive Summary (.txt)", "txt");

  companion object {
    fun fromId(id: String): ExportFormat = entries.firstOrNull { it.id == id } ?: VERILOG_NETLIST
  }
}

data class AppSettings(
  val darkTheme: Boolean = true,
  val compactDensity: Boolean = false,
  val editorFontSizeSp: Int = 13,
  val tabWidth: Int = 2,
  val autoIndent: Boolean = true,
  val showLineNumbers: Boolean = true,
  val wordWrap: Boolean = false,
  val autoSaveIntervalSec: Int = 15,
  val defaultEngine: SynthesisEngineProfile = SynthesisEngineProfile.SILICON_FLOW,
  val aggressiveOptimization: Boolean = false,
  val retainHierarchy: Boolean = true,
  val defaultLibrary: TargetLibrary = TargetLibrary.ASAP7_7NM,
  val localBridgeEnabled: Boolean = false,
  val localBridgeEndpoint: String = "http://127.0.0.1:8765",
  val defaultExportFormat: ExportFormat = ExportFormat.VERILOG_NETLIST,
  val includeTimestampsInExport: Boolean = true,
)

data class RtlDesign(
  val id: String = "",
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
  val createdAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L,
  val language: String = RtlLanguage.VERILOG.name,
  val files: List<RtlFileEntry> = emptyList(),
)

data class SynthesisRun(
  val id: String = "",
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
  val createdAtMillis: Long = 0L,
  val durationMs: Long = 0L,
  val engineId: String = SynthesisEngineProfile.SILICON_FLOW.id,
)
