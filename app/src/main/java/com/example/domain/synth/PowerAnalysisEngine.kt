package com.example.domain.synth

import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.pow

/**
 * Activity-factor-driven CMOS power estimation engine.
 *
 * Computes:
 *  - Switching power (dynamic net/load charging: 0.5 * alpha * C_load * Vdd^2 * f)
 *  - Internal power (cell short-circuit + internal pin capacitances + clock-pin toggles)
 *  - Leakage power (static subthreshold & gate leakage scaled by PDK process & Vdd)
 *
 * When a [SimulationResult] is available, exact multi-bit Hamming transition densities
 * are extracted per trace across simulated cycles and propagated topologically through
 * the synthesized netlist DAG. When no simulation exists yet, falls back cleanly to a
 * vectorless default switching activity (alpha = 0.20 for data, 0.05 for reset).
 */
object PowerAnalysisEngine {

  private const val DEFAULT_DATA_ALPHA = 0.20
  private const val DEFAULT_RESET_ALPHA = 0.05

  private data class TechPowerParams(
    val baseCellCapFf: Double,
    val capPerFanoutFf: Double,
    val leakageScale: Double,
  )

  fun analyze(
    report: SynthesisReport,
    simulation: SimulationResult? = null,
    config: PowerEstimationConfig = PowerEstimationConfig(),
  ): PowerAnalysisReport = analyze(
    moduleName = report.moduleName,
    ports = report.ports,
    nodes = report.nodes,
    edges = report.edges,
    library = report.targetLibrary,
    clockPeriodNs = report.clockPeriodNs,
    simulation = simulation,
    config = config,
  )

  fun analyze(
    moduleName: String,
    ports: List<RtlPort>,
    nodes: List<GateNode>,
    edges: List<NetEdge>,
    library: TargetLibrary,
    clockPeriodNs: Double,
    simulation: SimulationResult? = null,
    config: PowerEstimationConfig = PowerEstimationConfig(),
  ): PowerAnalysisReport {
    val freqMhz = config.clockFreqOverrideMhz?.coerceIn(1.0, 10_000.0)
      ?: (1000.0 / clockPeriodNs.coerceAtLeast(0.1)).coerceIn(1.0, 10_000.0)
    val effectiveClockNs = (1000.0 / freqMhz).coerceAtLeast(0.1)
    val vddVolts = config.vddOverrideVolts?.coerceIn(0.4, 5.5) ?: parseVoltage(library.nominalVoltage)
    val tech = techParamsFor(library)

    val defaultDataAlpha = config.defaultActivityFactor.coerceIn(0.0, 1.0)
    val staticProbScale = (0.75 + 0.50 * config.signalProbability.coerceIn(0.05, 0.95))
    // Subthreshold leakage roughly doubles every ~35°C above 25°C
    val tempDeltaC = (config.temperatureC.coerceIn(-40.0, 150.0) - 25.0)
    val thermalLeakageMult = 2.0.pow(tempDeltaC / 38.0).coerceIn(0.25, 8.0) *
      config.operatingCorner.leakageMult

    val hasSimTraces = simulation != null && simulation.cycleCount >= 2 && simulation.traces.isNotEmpty()
    val activitySource = if (hasSimTraces) {
      PowerActivitySource.SIMULATION_VCD
    } else {
      PowerActivitySource.VECTORLESS_DEFAULT
    }

    val cycleCount = simulation?.cycleCount ?: 0
    val cycleToggleProfile = if (hasSimTraces) IntArray(cycleCount) else IntArray(0)
    val observedAlphaByNet = mutableMapOf<String, Double>()
    val signalActivitiesRaw = mutableListOf<SignalActivityMetric>()

    var hasActiveClockInSim = false
    var totalToggles = 0

    if (hasSimTraces) {
      val sim = simulation!!
      val transitionDenominatorCycles = (sim.cycleCount - 1).coerceAtLeast(1)
      sim.traces.forEach { trace ->
        val width = trace.width.coerceAtLeast(1)
        var traceToggles = 0
        for (c in 1 until simulation.cycleCount) {
          val prev = RtlSimulator.maskValue(trace.values.getOrElse(c - 1) { 0L }, width)
          val curr = RtlSimulator.maskValue(trace.values.getOrElse(c) { 0L }, width)
          val diff = java.lang.Long.bitCount(prev xor curr)
          traceToggles += diff
          if (!trace.isClock) {
            cycleToggleProfile[c] += diff
          }
        }
        val possible = transitionDenominatorCycles * width
        val rawAlpha = if (possible > 0) (traceToggles.toDouble() / possible).coerceIn(0.0, 1.0) else 0.0
        val effectiveAlpha = if (trace.isClock) {
          if (traceToggles > 0) 1.0 else 0.0
        } else {
          totalToggles += traceToggles
          rawAlpha
        }

        val normalizedName = normalizeNetName(trace.name)
        observedAlphaByNet[normalizedName] = effectiveAlpha
        observedAlphaByNet[trace.name] = effectiveAlpha

        val matchingEdges = edges.count { normalizeNetName(it.netName) == normalizedName }
        val fanout = matchingEdges.coerceAtLeast(1)
        val netCapFf = (tech.baseCellCapFf * 0.35 + fanout * tech.capPerFanoutFf) * width.coerceIn(1, 32)
        val transitionsPerCycle = if (trace.isClock) (if (effectiveAlpha > 0.0) 2.0 else 0.0) else effectiveAlpha
        val netSwUw = 0.5 * transitionsPerCycle * netCapFf * vddVolts * vddVolts * freqMhz * 1e-3

        signalActivitiesRaw += SignalActivityMetric(
          signalName = trace.name,
          bitWidth = width,
          toggleCount = if (trace.isClock) (if (traceToggles > 0) (simulation.cycleCount - 1) * 2 else 0) else traceToggles,
          possibleTransitions = possible.coerceAtLeast(1),
          activityFactor = effectiveAlpha,
          isClock = trace.isClock,
          isReset = trace.isReset,
          isSequentialReg = trace.isSequentialReg,
          netCapacitanceFf = netCapFf,
          switchingPowerUw = netSwUw,
        )
      }
      val clkTrace = simulation.traces.firstOrNull { it.isClock }
      hasActiveClockInSim = if (clkTrace != null) {
        var clkEdges = 0
        for (c in 1 until simulation.cycleCount) {
          if ((clkTrace.values.getOrElse(c - 1) { 0L } and 1L) != (clkTrace.values.getOrElse(c) { 0L } and 1L)) {
            clkEdges++
          }
        }
        clkEdges > 0
      } else {
        simulation.clockPort != null
      }
    } else {
      hasActiveClockInSim = ports.any { it.name.contains("clk", ignoreCase = true) || it.name.contains("clock", ignoreCase = true) }
      ports.forEach { port ->
        val isClk = port.direction == PortDirection.INPUT &&
          (port.name.equals("clk", true) || port.name.equals("clock", true) || port.name.lowercase(Locale.ROOT).startsWith("clk"))
        val isRst = port.direction == PortDirection.INPUT &&
          (port.name.contains("rst", ignoreCase = true) || port.name.contains("reset", ignoreCase = true))
        val alpha = when {
          isClk -> 1.0
          isRst -> DEFAULT_RESET_ALPHA
          else -> defaultDataAlpha
        }
        val norm = normalizeNetName(port.name)
        observedAlphaByNet[norm] = alpha
        observedAlphaByNet[port.name] = alpha
        val fanout = edges.count { normalizeNetName(it.netName) == norm }.coerceAtLeast(1)
        val netCapFf = (tech.baseCellCapFf * 0.35 + fanout * tech.capPerFanoutFf) * port.width.coerceIn(1, 32)
        val trans = if (isClk) 2.0 else alpha
        val netSwUw = 0.5 * trans * netCapFf * vddVolts * vddVolts * freqMhz * 1e-3

        signalActivitiesRaw += SignalActivityMetric(
          signalName = port.name,
          bitWidth = port.width.coerceAtLeast(1),
          toggleCount = 0,
          possibleTransitions = 0,
          activityFactor = alpha,
          isClock = isClk,
          isReset = isRst,
          isSequentialReg = false,
          netCapacitanceFf = netCapFf,
          switchingPowerUw = netSwUw,
        )
      }
    }

    val outgoingCount = HashMap<String, Int>()
    val incomingEdges = HashMap<String, MutableList<NetEdge>>()
    edges.forEach { edge ->
      outgoingCount[edge.fromNodeId] = (outgoingCount[edge.fromNodeId] ?: 0) + 1
      incomingEdges.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge)
    }

    val nonDataClockResetNames = buildSet {
      simulation?.clockPort?.let { add(normalizeNetName(it)) }
      simulation?.resetPort?.let { add(normalizeNetName(it)) }
      signalActivitiesRaw.filter { it.isClock || it.isReset }.forEach { add(normalizeNetName(it.signalName)) }
    }

    val netAlphaMap = HashMap<String, Double>(observedAlphaByNet)
    val sortedNodes = nodes.sortedWith(compareBy<GateNode> { !it.gateType.isPort }.thenBy { it.level })

    val nonPortBreakdowns = ArrayList<NodePowerBreakdown>(nodes.size)
    var combTotalUw = 0.0
    var seqDataAndLeakUw = 0.0
    var seqClkPinIntUw = 0.0
    var bufferTotalUw = 0.0
    var sumSwUw = 0.0
    var sumIntUw = 0.0
    var sumLeakUw = 0.0

    val clkPinTransPerCycle = if (hasActiveClockInSim) 2.0 else 0.0

    sortedNodes.forEach { node ->
      val outNorm = normalizeNetName(node.outputNet)
      if (node.gateType == GateType.INPUT_PORT) {
        if (outNorm !in netAlphaMap) {
          val isClk = isLikelyClockName(node.outputNet)
          val isRst = isLikelyResetName(node.outputNet)
          netAlphaMap[outNorm] = when {
            isClk -> if (hasActiveClockInSim) 1.0 else 0.0
            isRst -> if (hasSimTraces) 0.0 else DEFAULT_RESET_ALPHA
            else -> if (hasSimTraces) 0.0 else defaultDataAlpha
          }
        }
        return@forEach
      }
      if (node.gateType == GateType.OUTPUT_PORT) {
        return@forEach
      }
      if (node.gateType == GateType.CONST) {
        netAlphaMap[outNorm] = 0.0
        return@forEach
      }

      val inputAlphas = node.inputs.mapNotNull { inp ->
        val inpNorm = normalizeNetName(inp)
        val isCtrl = inpNorm in nonDataClockResetNames || isLikelyClockName(inp) || isLikelyResetName(inp)
        if (node.gateType.isSequential && isCtrl) {
          null
        } else {
          netAlphaMap[inpNorm] ?: netAlphaMap[inp]
        }
      }
      val edgeInputAlphas = incomingEdges[node.id].orEmpty().mapNotNull { e ->
        val eNorm = normalizeNetName(e.netName)
        if (node.gateType.isSequential && eNorm in nonDataClockResetNames) null else netAlphaMap[eNorm]
      }
      val allUpstreamAlphas = (inputAlphas + edgeInputAlphas)

      val fallbackAlpha = if (hasSimTraces) {
        val dataTraces = signalActivitiesRaw.filter { !it.isClock }
        if (dataTraces.isEmpty()) defaultDataAlpha else dataTraces.map { it.activityFactor }.average()
      } else {
        defaultDataAlpha
      }

      val propagatedAlpha = propagateGateActivity(
        gateType = node.gateType,
        inputAlphas = allUpstreamAlphas,
        fallback = fallbackAlpha,
      )

      val observedOutAlpha = observedAlphaByNet[outNorm] ?: observedAlphaByNet[node.outputNet]
      val outputAlpha = (observedOutAlpha ?: propagatedAlpha).coerceIn(0.0, 1.0)
      netAlphaMap[outNorm] = outputAlpha

      val inEffectiveAlpha = if (allUpstreamAlphas.isNotEmpty()) {
        allUpstreamAlphas.average().coerceIn(0.0, 1.0)
      } else {
        outputAlpha
      }

      val fanout = (outgoingCount[node.id] ?: 1).coerceAtLeast(1)
      val widthFactor = node.bitWidth.coerceIn(1, 64)
      val powerWeight = (node.powerUw / 14.0).coerceIn(0.25, 4.5)
      val sizeScale = (1.0 + (widthFactor - 1) * 0.55) * (0.65 + 0.35 * powerWeight)

      val cLoadFf = (tech.baseCellCapFf * 0.45 + fanout * tech.capPerFanoutFf) * sizeScale
      val cIntFf = tech.baseCellCapFf * 0.55 * sizeScale

      val swUw = 0.5 * outputAlpha * cLoadFf * vddVolts * vddVolts * freqMhz * 1e-3

      val dataIntAlpha = if (node.gateType.isSequential) {
        (0.55 * outputAlpha + 0.45 * inEffectiveAlpha).coerceIn(0.0, 1.0)
      } else {
        (0.50 * outputAlpha + 0.50 * inEffectiveAlpha).coerceIn(0.0, 1.0)
      }
      val dataIntUw = 0.5 * dataIntAlpha * cIntFf * vddVolts * vddVolts * freqMhz * 1e-3
      val clkPinUw = if (node.gateType.isSequential && !node.isFalsePath) {
        val cClkPinFf = tech.baseCellCapFf * 0.72 * (1.0 + (widthFactor - 1) * 0.35)
        0.5 * clkPinTransPerCycle * cClkPinFf * vddVolts * vddVolts * freqMhz * 1e-3
      } else {
        0.0
      }
      val intUw = dataIntUw + clkPinUw

      val leakUw = (node.areaUm2 * 0.036 * vddVolts * tech.leakageScale +
        node.powerUw * 0.042 * tech.leakageScale) * staticProbScale * thermalLeakageMult

      sumSwUw += swUw
      sumIntUw += intUw
      sumLeakUw += leakUw

      if (node.gateType.isSequential) {
        seqDataAndLeakUw += (swUw + dataIntUw + leakUw)
        seqClkPinIntUw += clkPinUw
      } else {
        combTotalUw += (swUw + intUw + leakUw)
        if (node.gateType == GateType.BUF || node.gateType == GateType.INV) {
          bufferTotalUw += (swUw + intUw + leakUw)
        }
      }

      nonPortBreakdowns += NodePowerBreakdown(
        nodeId = node.id,
        instanceName = node.instanceName,
        cellName = node.cellName,
        gateType = node.gateType,
        outputNet = node.outputNet,
        activityFactor = outputAlpha,
        switchingPowerUw = swUw,
        internalPowerUw = intUw,
        leakagePowerUw = leakUw,
      )
    }

    val seqNodesCount = nonPortBreakdowns.count { it.gateType.isSequential }
    val clkTreeBuffers = if (seqNodesCount > 0) ((seqNodesCount + 5) / 6).coerceAtLeast(1) else 0
    val clkWireCapFf = clkTreeBuffers * tech.capPerFanoutFf * 2.1
    val clkBufIntCapFf = clkTreeBuffers * tech.baseCellCapFf * 1.4
    val clkWireSwUw = 0.5 * clkPinTransPerCycle * clkWireCapFf * vddVolts * vddVolts * freqMhz * 1e-3
    val clkBufIntUw = 0.5 * clkPinTransPerCycle * clkBufIntCapFf * vddVolts * vddVolts * freqMhz * 1e-3
    val clkTreeLeakUw = clkTreeBuffers * (1.8 * vddVolts * tech.leakageScale) * thermalLeakageMult

    val switchingPowerMw = (sumSwUw + clkWireSwUw) / 1000.0
    val internalPowerMw = (sumIntUw + clkBufIntUw) / 1000.0
    val leakagePowerMw = (sumLeakUw + clkTreeLeakUw) / 1000.0
    val totalPowerMw = switchingPowerMw + internalPowerMw + leakagePowerMw

    val clockTreePowerMw = (clkWireSwUw + clkBufIntUw + clkTreeLeakUw + seqClkPinIntUw) / 1000.0
    val combinationalPowerMw = combTotalUw / 1000.0
    val sequentialPowerMw = seqDataAndLeakUw / 1000.0
    val bufferPowerMw = bufferTotalUw / 1000.0
    val energyPerCyclePj = totalPowerMw * effectiveClockNs

    val dataSignalActivities = signalActivitiesRaw.filter { !it.isClock }
    val averageAlpha = if (dataSignalActivities.isNotEmpty()) {
      dataSignalActivities.map { it.activityFactor }.average()
    } else {
      defaultDataAlpha
    }

    var peakCycleIdx = 0
    var peakCycleToggles = 0
    for (i in cycleToggleProfile.indices) {
      if (cycleToggleProfile[i] > peakCycleToggles) {
        peakCycleToggles = cycleToggleProfile[i]
        peakCycleIdx = i
      }
    }

    val meanCycleToggles = if (hasSimTraces && cycleToggleProfile.size > 1) {
      cycleToggleProfile.drop(1).average().coerceAtLeast(1.0)
    } else {
      0.0
    }
    val peakCyclePowerMw = if (hasSimTraces && cycleToggleProfile.size > 1) {
      val dynMw = switchingPowerMw + internalPowerMw
      val toggleRatio = if (meanCycleToggles > 0.0) (peakCycleToggles / meanCycleToggles).coerceIn(1.0, 4.0) else 1.0
      leakagePowerMw + dynMw * toggleRatio
    } else {
      null
    }

    val sortedSignals = signalActivitiesRaw.sortedWith(
      compareBy<SignalActivityMetric> { it.isClock }
        .thenByDescending { it.activityFactor }
        .thenByDescending { it.switchingPowerUw }
    )

    val sortedPowerNodes = nonPortBreakdowns.sortedByDescending { it.totalPowerUw }
    val topPowerNodes = sortedPowerNodes.take(12)

    return PowerAnalysisReport(
      moduleName = moduleName,
      library = library,
      activitySource = activitySource,
      simulatedCycles = cycleCount,
      clockFreqMhz = freqMhz,
      vddVolts = vddVolts,
      averageActivityFactor = averageAlpha,
      totalToggles = totalToggles,
      peakCycleIndex = peakCycleIdx,
      peakCycleToggles = peakCycleToggles,
      switchingPowerMw = switchingPowerMw,
      internalPowerMw = internalPowerMw,
      leakagePowerMw = leakagePowerMw,
      clockTreePowerMw = clockTreePowerMw,
      combinationalPowerMw = combinationalPowerMw,
      sequentialPowerMw = sequentialPowerMw,
      totalPowerMw = totalPowerMw,
      energyPerCyclePj = energyPerCyclePj,
      signalActivities = sortedSignals,
      topPowerNodes = topPowerNodes,
      cycleToggleProfile = cycleToggleProfile.toList(),
      bufferPowerMw = bufferPowerMw,
      averageCyclePowerMw = totalPowerMw,
      peakCyclePowerMw = peakCyclePowerMw,
      temperatureC = config.temperatureC,
      operatingCorner = config.operatingCorner,
      allPowerNodes = sortedPowerNodes,
    )
  }

  private fun propagateGateActivity(
    gateType: GateType,
    inputAlphas: List<Double>,
    fallback: Double,
  ): Double {
    if (inputAlphas.isEmpty()) return fallback.coerceIn(0.0, 1.0)
    val avg = inputAlphas.average()
    val max = inputAlphas.maxOrNull() ?: avg
    return when (gateType) {
      GateType.INV, GateType.BUF -> avg
      GateType.AND2, GateType.NAND2, GateType.OR2, GateType.NOR2 ->
        0.78 * avg + 0.12 * max
      GateType.XOR2 ->
        (inputAlphas.sum().coerceAtMost(1.0) * 0.92)
      GateType.MUX2 ->
        0.65 * avg + 0.35 * max
      GateType.ADD_CELL, GateType.SUB_CELL, GateType.CMP_CELL, GateType.MUL_CELL ->
        0.82 * avg + 0.18 * max
      GateType.LUT4, GateType.LUT6, GateType.CARRY4 ->
        0.78 * avg + 0.14 * max
      GateType.DFF, GateType.DFFR, GateType.SDFF, GateType.SDFFR, GateType.LOCKUP_LATCH ->
        max * 0.95
      else -> avg
    }.coerceIn(0.0, 1.0)
  }

  private fun normalizeNetName(raw: String): String =
    raw.trim()
      .substringBefore('[')
      .substringAfterLast('.')
      .lowercase(Locale.ROOT)

  private fun isLikelyClockName(name: String): Boolean {
    val l = normalizeNetName(name)
    return l == "clk" || l == "clock" || l.startsWith("clk_") || l.endsWith("_clk")
  }

  private fun isLikelyResetName(name: String): Boolean {
    val l = normalizeNetName(name)
    return l == "rst" || l == "rst_n" || l == "reset" || l.contains("reset") || l.startsWith("rst_")
  }

  private fun parseVoltage(raw: String): Double {
    val match = Regex("""([0-9]+(?:\.[0-9]+)?)""").find(raw)
    return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.coerceIn(0.4, 5.5) ?: 1.0
  }

  private fun techParamsFor(library: TargetLibrary): TechPowerParams = when (library) {
    TargetLibrary.ASAP7_7NM -> TechPowerParams(baseCellCapFf = 0.9, capPerFanoutFf = 1.4, leakageScale = 1.35)
    TargetLibrary.NANGATE45_45NM -> TechPowerParams(baseCellCapFf = 2.8, capPerFanoutFf = 4.2, leakageScale = 0.85)
    TargetLibrary.ARTIX7_FPGA -> TechPowerParams(baseCellCapFf = 6.0, capPerFanoutFf = 8.5, leakageScale = 1.15)
    TargetLibrary.SKY130_130NM -> TechPowerParams(baseCellCapFf = 9.5, capPerFanoutFf = 14.0, leakageScale = 0.45)
    TargetLibrary.GF180MCU_180NM -> TechPowerParams(baseCellCapFf = 14.0, capPerFanoutFf = 22.0, leakageScale = 0.30)
  }
}
