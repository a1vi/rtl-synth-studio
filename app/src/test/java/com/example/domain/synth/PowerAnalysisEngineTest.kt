package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.TargetLibrary
import com.example.ui.components.ExportShareHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class PowerAnalysisEngineTest {

  private fun counterReport(
    library: TargetLibrary = TargetLibrary.ASAP7_7NM,
    clockPeriodNs: Double = 4.0,
  ): SynthesisReport {
    val preset = TestRtlFixtures.counter8Bit
    val res = RtlSynthesisEngine.synthesize(
      verilogSource = preset.verilogCode,
      library = library,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = clockPeriodNs,
    )
    assertTrue(res is SynthesisResult.Success)
    return (res as SynthesisResult.Success).report
  }

  @Test
  fun synthesisReportIncludesVectorlessDefaultPowerAnalysis() {
    val report = counterReport()
    val pwr = report.powerAnalysis
    assertNotNull("Synthesis should attach default vectorless powerAnalysis", pwr)
    pwr!!
    assertEquals(PowerActivitySource.VECTORLESS_DEFAULT, pwr.activitySource)
    assertEquals(0.20, pwr.averageActivityFactor, 0.05)
    assertTrue("Switching power should be positive", pwr.switchingPowerMw > 0.0)
    assertTrue("Internal power should be positive", pwr.internalPowerMw > 0.0)
    assertTrue("Leakage power should be positive", pwr.leakagePowerMw > 0.0)
    assertEquals(
      pwr.totalPowerMw,
      pwr.switchingPowerMw + pwr.internalPowerMw + pwr.leakagePowerMw,
      1e-6,
    )
    assertEquals(
      pwr.totalPowerMw,
      pwr.combinationalPowerMw + pwr.sequentialPowerMw + pwr.clockTreePowerMw,
      1e-6,
    )
    assertEquals(pwr.totalPowerMw * report.clockPeriodNs, pwr.energyPerCyclePj, 1e-6)
  }

  @Test
  fun simulationActivitySwitchesSourceAndScalesSwitchingPowerWhileLeakageStaysInvariant() {
    val report = counterReport(library = TargetLibrary.SKY130_130NM, clockPeriodNs = 10.0)
    val design = (VerilogHdlParser.parse(
      TestRtlFixtures.counter8Bit.verilogCode
    ) as VerilogParseResult.Success).design

    // 1) Low-activity simulation: active-high rst asserted & en held LOW (count frozen at 0)
    val zeroActivitySim = RtlSimulator.simulate(
      design = design,
      cycleCount = 16,
      patternOverrides = mapOf("en" to StimulusPattern.LOW, "rst" to StimulusPattern.HIGH),
    )
    val lowPower = PowerAnalysisEngine.analyze(report, zeroActivitySim)

    // 2) Active counting simulation: en held HIGH, rst LOW -> counter toggles every cycle
    val activeSim = RtlSimulator.simulate(
      design = design,
      cycleCount = 16,
      patternOverrides = mapOf("en" to StimulusPattern.HIGH, "rst" to StimulusPattern.LOW),
    )
    val activePower = PowerAnalysisEngine.analyze(report, activeSim)

    assertEquals(PowerActivitySource.SIMULATION_VCD, lowPower.activitySource)
    assertEquals(PowerActivitySource.SIMULATION_VCD, activePower.activitySource)

    assertTrue(
      "Active counting activity (${activePower.averageActivityFactor}) should exceed low-activity (${lowPower.averageActivityFactor})",
      activePower.averageActivityFactor > lowPower.averageActivityFactor,
    )
    assertTrue(
      "Active switching power (${activePower.switchingPowerMw}) should exceed zero-activity (${lowPower.switchingPowerMw})",
      activePower.switchingPowerMw > lowPower.switchingPowerMw,
    )
    assertTrue(
      "Active total power (${activePower.totalPowerMw}) should exceed zero-activity (${lowPower.totalPowerMw})",
      activePower.totalPowerMw > lowPower.totalPowerMw,
    )
    // Static leakage depends only on PDK process, Vdd, and gate area — invariant to activity factor!
    assertEquals(
      "Static leakage power must remain invariant to switching activity",
      lowPower.leakagePowerMw,
      activePower.leakagePowerMw,
      1e-6,
    )
  }

  @Test
  fun multiBitHammingDistanceActivityFactorExactForAlternatingBus() {
    val rtl = """
      module bus_buf(
        input wire [7:0] a,
        output wire [7:0] y
      );
        assign y = ~a;
      endmodule
    """.trimIndent()
    val report = (RtlSynthesisEngine.synthesize(
      verilogSource = rtl,
      library = TargetLibrary.NANGATE45_45NM,
      goal = OptimizationGoal.BALANCED,
      clockPeriodNs = 5.0,
    ) as SynthesisResult.Success).report

    // Synthetic trace: 8-bit bus flips all 8 bits every single cycle (0x00 <-> 0xFF) -> alpha = 1.0
    val fullToggleSim = SimulationResult(
      moduleName = "bus_buf",
      cycleCount = 5,
      clockPort = null,
      resetPort = null,
      traces = listOf(
        SignalTrace("a", PortDirection.INPUT, 8, false, false, false, listOf(0x00, 0xFF, 0x00, 0xFF, 0x00)),
        SignalTrace("y", PortDirection.OUTPUT, 8, false, false, false, listOf(0xFF, 0x00, 0xFF, 0x00, 0xFF)),
      ),
    )
    val zeroToggleSim = SimulationResult(
      moduleName = "bus_buf",
      cycleCount = 5,
      clockPort = null,
      resetPort = null,
      traces = listOf(
        SignalTrace("a", PortDirection.INPUT, 8, false, false, false, listOf(0x55, 0x55, 0x55, 0x55, 0x55)),
        SignalTrace("y", PortDirection.OUTPUT, 8, false, false, false, listOf(0xAA, 0xAA, 0xAA, 0xAA, 0xAA)),
      ),
    )

    val fullPwr = PowerAnalysisEngine.analyze(report, fullToggleSim)
    val zeroPwr = PowerAnalysisEngine.analyze(report, zeroToggleSim)

    assertEquals(1.0, fullPwr.averageActivityFactor, 1e-6)
    assertEquals(0.0, zeroPwr.averageActivityFactor, 1e-6)
    assertEquals(0.0, zeroPwr.switchingPowerMw, 1e-6)
    assertEquals(0.0, zeroPwr.internalPowerMw, 1e-6)
    assertTrue("Full-toggle switching power should be > 0", fullPwr.switchingPowerMw > 0.0)
    assertTrue("Full-toggle internal power should be > 0", fullPwr.internalPowerMw > 0.0)
    assertEquals(fullPwr.leakagePowerMw, zeroPwr.leakagePowerMw, 1e-6)
    // Peak cycle toggles = 16 (8 bits on a + 8 bits on y) on cycles 1..4
    assertEquals(16, fullPwr.peakCycleToggles)
    assertEquals(64, fullPwr.totalToggles)
  }

  @Test
  fun voltageAndFrequencyScalingReflectPdkPhysics() {
    val r7nm = counterReport(library = TargetLibrary.ASAP7_7NM, clockPeriodNs = 2.0)
    val r7nmSlow = counterReport(library = TargetLibrary.ASAP7_7NM, clockPeriodNs = 8.0)

    val pFast = r7nm.powerAnalysis!!
    val pSlow = r7nmSlow.powerAnalysis!!

    assertEquals(4.0, pFast.switchingPowerMw / pSlow.switchingPowerMw, 0.05)
    assertEquals(pFast.leakagePowerMw, pSlow.leakagePowerMw, 1e-6)
  }

  @Test
  fun tapeoutZipIncludesSwitchingInternalLeakageAndTopCellsInReport() {
    val report = counterReport()
    val zipBytes = ExportShareHelper.buildTapeoutZipBytes(
      projectFiles = emptyList(),
      fallbackRtl = "module counter_8bit; endmodule",
      report = report,
      layout = null,
      sdcText = "",
    )
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
      var e = zis.nextEntry
      while (e != null) {
        entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
        e = zis.nextEntry
      }
    }
    val staPowerRpt = entries["reports/counter_8bit_sta_power.rpt"].orEmpty()
    val manifest = entries["manifest.json"].orEmpty()

    assertTrue(staPowerRpt.contains("Switching="))
    assertTrue(staPowerRpt.contains("Internal="))
    assertTrue(staPowerRpt.contains("Leakage="))
    assertTrue(staPowerRpt.contains("Top Power Cells"))
    assertTrue(manifest.contains("\"switchingPowerMw\""))
    assertTrue(manifest.contains("\"internalPowerMw\""))
    assertTrue(manifest.contains("\"leakagePowerMw\""))
  }
}
