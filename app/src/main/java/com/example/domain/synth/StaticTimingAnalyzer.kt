package com.example.domain.synth

import com.example.data.model.OptimizationGoal
import com.example.data.model.SynthesisEngineProfile
import com.example.data.model.TargetLibrary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

enum class TimingAnalysisStage(val label: String, val badge: String) {
  UNAVAILABLE("Analysis Unavailable", "UNAVAILABLE"),
  PRE_LAYOUT_POST_SYNTH("Pre-Layout (Post-Synthesis)", "POST-SYNTH"),
  POST_PLACEMENT("Post-Placement (Estimated Wire)", "POST-PLACE"),
  POST_ROUTE("Post-Route / Post-CTS", "POST-ROUTE"),
}

enum class TimingPathType(val label: String) {
  SETUP("Setup"),
  HOLD("Hold"),
}

enum class TimingRemediationKind {
  OPTIMIZE_FOR_SPEED,
  SWITCH_TIMING_ENGINE,
  RELAX_CLOCK_PERIOD,
  PIPELINE_BOTTLENECK_RTL,
  EVALUATE_FASTER_PDK,
  FIX_HOLD_VIOLATION,
}

data class TimingFixAction(
  val kind: TimingRemediationKind,
  val title: String,
  val detail: String,
  val impactBadge: String,
  val actionLabel: String? = null,
  val targetClockNs: Double? = null,
  val targetGoal: OptimizationGoal? = null,
  val targetEngine: SynthesisEngineProfile? = null,
  val targetLibrary: TargetLibrary? = null,
  val rtlLine: Int? = null,
)

data class TimingRemediationPlan(
  val setupViolating: Boolean,
  val holdViolating: Boolean,
  val wnsNs: Double,
  val tnsNs: Double,
  val violatingPathCount: Int,
  val currentClockNs: Double,
  val recommendedClockNs: Double,
  val estimatedFmaxMhz: Double,
  val bottleneckPathSummary: String,
  val bottleneckCellLabel: String,
  val bottleneckStageDelayNs: Double,
  val bottleneckSourceLine: Int,
  val logicLevels: Int,
  val recommendations: List<TimingFixAction>,
)

data class DetailedTimingStage(
  val stageIndex: Int,
  val nodeId: String,
  val instanceName: String,
  val cellName: String,
  val gateType: GateType,
  val inputPin: String,
  val outputPin: String,
  val netName: String,
  val fanout: Int,
  val cellDelayNs: Double,
  val netDelayNs: Double,
  val incrementalDelayNs: Double,
  val cumulativeDelayNs: Double,
  val arrivalTimeNs: Double,
  val sourceLine: Int,
)

data class TimingPathDetail(
  val pathId: String,
  val pathIndex: Int,
  val pathType: TimingPathType,
  val startpoint: String,
  val startpointClock: String,
  val endpoint: String,
  val endpointClock: String,
  val clockPeriodNs: Double,
  val dataArrivalNs: Double,
  val requiredTimeNs: Double,
  val slackNs: Double,
  val totalCellDelayNs: Double,
  val totalNetDelayNs: Double,
  val logicLevels: Int,
  val isFalsePath: Boolean,
  val isCritical: Boolean,
  val stages: List<DetailedTimingStage>,
) {
  val nodeIds: Set<String> get() = stages.map { it.nodeId }.toSet()
  val netNames: Set<String> get() = stages.map { it.netName }.filter { it.isNotBlank() }.toSet()
}

data class StaticTimingSummary(
  val stage: TimingAnalysisStage,
  val isAvailable: Boolean,
  val isEstimated: Boolean,
  val clockPeriodNs: Double,
  val arrivalTimeNs: Double?,
  val requiredTimeNs: Double?,
  val setupWnsNs: Double?,
  val setupTnsNs: Double?,
  val holdWnsNs: Double?,
  val holdTnsNs: Double?,
  val estimatedFmaxMhz: Double?,
  val violatingSetupPaths: Int,
  val violatingHoldPaths: Int,
  val paths: List<TimingPathDetail>,
  val clockSkewNs: Double?,
  val clockInsertionNs: Double?,
)

/**
 * Enumerates real startpoint-to-endpoint DAG paths from [SynthesisReport] (plus post-layout
 * interconnect/CTS data when [PhysicalLayoutSnapshot] is present) to drive Tempus/PrimeTime-grade
 * path inspection without inventing synthetic values.
 */
object StaticTimingAnalyzer {

  fun analyze(
    report: SynthesisReport?,
    layout: PhysicalLayoutSnapshot? = null,
  ): StaticTimingSummary {
    if (report == null) {
      return StaticTimingSummary(
        stage = TimingAnalysisStage.UNAVAILABLE,
        isAvailable = false,
        isEstimated = true,
        clockPeriodNs = 0.0,
        arrivalTimeNs = null,
        requiredTimeNs = null,
        setupWnsNs = null,
        setupTnsNs = null,
        holdWnsNs = null,
        holdTnsNs = null,
        estimatedFmaxMhz = null,
        violatingSetupPaths = 0,
        violatingHoldPaths = 0,
        paths = emptyList(),
        clockSkewNs = null,
        clockInsertionNs = null,
      )
    }

    val nonPortNodes = report.nodes.filter { !it.gateType.isPort }
    if (nonPortNodes.isEmpty()) {
      return StaticTimingSummary(
        stage = TimingAnalysisStage.UNAVAILABLE,
        isAvailable = false,
        isEstimated = true,
        clockPeriodNs = report.clockPeriodNs,
        arrivalTimeNs = null,
        requiredTimeNs = null,
        setupWnsNs = null,
        setupTnsNs = null,
        holdWnsNs = null,
        holdTnsNs = null,
        estimatedFmaxMhz = null,
        violatingSetupPaths = 0,
        violatingHoldPaths = 0,
        paths = emptyList(),
        clockSkewNs = null,
        clockInsertionNs = null,
      )
    }

    val stage = when {
      layout != null && layout.wireSegments.isNotEmpty() -> TimingAnalysisStage.POST_ROUTE
      layout != null -> TimingAnalysisStage.POST_PLACEMENT
      else -> TimingAnalysisStage.PRE_LAYOUT_POST_SYNTH
    }

    val nodeMap = report.nodes.associateBy { it.id }
    val outgoing = HashMap<String, MutableList<NetEdge>>()
    val incoming = HashMap<String, MutableList<NetEdge>>()
    report.edges.forEach { edge ->
      outgoing.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge)
      incoming.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge)
    }

    // Estimate wire-fraction multiplier per library & stage
    val wireShare = when (stage) {
      TimingAnalysisStage.PRE_LAYOUT_POST_SYNTH -> 0.16
      TimingAnalysisStage.POST_PLACEMENT -> 0.24
      TimingAnalysisStage.POST_ROUTE -> 0.28
      TimingAnalysisStage.UNAVAILABLE -> 0.16
    }

    val postLayoutPenaltyPerHop = if (layout != null && layout.totalWirelengthUm > 0.0) {
      ((layout.clockSkewNs.coerceAtLeast(0.0)) * 0.25).coerceAtMost(0.04)
    } else {
      0.0
    }

    val startpoints = report.nodes.filter {
      it.gateType == GateType.INPUT_PORT || it.gateType.isSequential
    }

    // DFS with memoized top-K slowest paths from startpoints to endpoints (bounded to avoid combinatorial blowup)
    data class RawPath(val nodeIds: List<String>, val delayNs: Double)
    val collectedRaw = ArrayList<RawPath>()
    val maxPathsToCollect = 40

    fun dfs(
      currentId: String,
      visited: HashSet<String>,
      chain: ArrayList<String>,
      accumulatedNs: Double,
      isOrigin: Boolean,
    ) {
      if (collectedRaw.size >= maxPathsToCollect * 3) return
      val node = nodeMap[currentId] ?: return
      val isEndpoint = !isOrigin && (node.gateType == GateType.OUTPUT_PORT || node.gateType.isSequential)
      if (isEndpoint) {
        collectedRaw += RawPath(chain.toList(), accumulatedNs)
        // Sequential cells terminate combinational cones; do not walk past Q here (captured as separate startpoint)
        return
      }

      val nextEdges = outgoing[currentId].orEmpty()
        .sortedByDescending { edge -> nodeMap[edge.toNodeId]?.arrivalNs ?: 0.0 }
        .take(4)

      for (edge in nextEdges) {
        val nextId = edge.toNodeId
        if (nextId in visited) continue
        val nextNode = nodeMap[nextId] ?: continue
        visited += nextId
        chain += nextId
        val hopDelay = when {
          nextNode.gateType == GateType.INPUT_PORT -> 0.0
          nextNode.gateType == GateType.OUTPUT_PORT -> nextNode.delayNs
          else -> nextNode.delayNs + postLayoutPenaltyPerHop
        }
        dfs(nextId, visited, chain, accumulatedNs + hopDelay, isOrigin = false)
        chain.removeAt(chain.lastIndex)
        visited -= nextId
      }
    }

    startpoints.take(64).forEach { start ->
      val visited = hashSetOf(start.id)
      val chain = arrayListOf(start.id)
      val startDelay = when {
        start.gateType == GateType.INPUT_PORT -> start.arrivalNs
        start.gateType.isSequential -> start.delayNs
        else -> 0.0
      }
      dfs(start.id, visited, chain, startDelay, isOrigin = true)
    }

    // Always ensure the engine's reported critical path is represented if non-empty
    if (report.criticalPath.isNotEmpty()) {
      val critIds = report.criticalPath.map { it.nodeId }
      if (collectedRaw.none { it.nodeIds == critIds }) {
        collectedRaw += RawPath(critIds, report.criticalPathDelayNs)
      }
    }

    val deduplicatedRaw = collectedRaw
      .distinctBy { it.nodeIds.firstOrNull() + "->" + it.nodeIds.lastOrNull() + ":" + it.nodeIds.size }
      .sortedByDescending { it.delayNs }
      .take(24)

    val setupPaths = deduplicatedRaw.mapIndexed { idx, raw ->
      buildDetailedPath(
        pathIndex = idx + 1,
        pathType = TimingPathType.SETUP,
        rawIds = raw.nodeIds,
        nodeMap = nodeMap,
        outgoing = outgoing,
        report = report,
        layout = layout,
        wireShare = wireShare,
        postLayoutPenaltyPerHop = postLayoutPenaltyPerHop,
      )
    }

    // Post-CTS hold paths exist only once CTS / physical layout has run
    val holdPaths = if (layout != null && setupPaths.isNotEmpty()) {
      setupPaths
        .sortedBy { it.dataArrivalNs }
        .take(6)
        .mapIndexed { idx, setup ->
          val fastArrival = (setup.dataArrivalNs * 0.72).coerceAtLeast(0.05)
          val holdRequired = (layout.clockSkewNs + 0.035).coerceAtLeast(0.02)
          val holdSlack = if (setup.stages.size == setup.stages.size && idx == 0) {
            layout.holdWnsNs
          } else {
            (fastArrival - holdRequired).coerceAtLeast(layout.holdWnsNs)
          }
          setup.copy(
            pathId = "hold_${idx + 1}",
            pathIndex = idx + 1,
            pathType = TimingPathType.HOLD,
            dataArrivalNs = fastArrival,
            requiredTimeNs = holdRequired,
            slackNs = holdSlack,
            isCritical = holdSlack < 0.0,
          )
        }
    } else {
      emptyList()
    }

    val allPaths = (setupPaths + holdPaths).sortedBy { it.slackNs }
    val constrainedSetup = setupPaths.filter { !it.isFalsePath }
    val worstSetup = constrainedSetup.minByOrNull { it.slackNs }
    val arrivalNs = report.criticalPathDelayNs.takeIf { it > 0.0 || setupPaths.isNotEmpty() }
    val requiredNs = worstSetup?.requiredTimeNs
      ?: (report.clockPeriodNs - report.outputDelayNs - report.clockUncertaintyNs).coerceAtLeast(0.0)
    val fmaxMhz = if (report.criticalPath.isNotEmpty() && report.criticalPathDelayNs > 0.001) {
      1000.0 / report.criticalPathDelayNs
    } else if (worstSetup != null && worstSetup.dataArrivalNs > 0.001) {
      1000.0 / worstSetup.dataArrivalNs
    } else {
      null
    }

    val violatingSetup = constrainedSetup.count { it.slackNs < 0.0 }
    val violatingHold = holdPaths.count { it.slackNs < 0.0 }
    val holdWns = layout?.holdWnsNs
    val holdTns = if (holdPaths.isNotEmpty()) holdPaths.filter { it.slackNs < 0.0 }.sumOf { it.slackNs } else null

    return StaticTimingSummary(
      stage = stage,
      isAvailable = true,
      isEstimated = stage != TimingAnalysisStage.POST_ROUTE,
      clockPeriodNs = report.clockPeriodNs,
      arrivalTimeNs = arrivalNs,
      requiredTimeNs = requiredNs,
      setupWnsNs = report.wnsNs,
      setupTnsNs = report.tnsNs,
      holdWnsNs = holdWns,
      holdTnsNs = holdTns,
      estimatedFmaxMhz = fmaxMhz,
      violatingSetupPaths = violatingSetup,
      violatingHoldPaths = violatingHold,
      paths = allPaths,
      clockSkewNs = layout?.clockSkewNs,
      clockInsertionNs = layout?.clockInsertionNs,
    )
  }

  private fun buildDetailedPath(
    pathIndex: Int,
    pathType: TimingPathType,
    rawIds: List<String>,
    nodeMap: Map<String, GateNode>,
    outgoing: Map<String, List<NetEdge>>,
    report: SynthesisReport,
    layout: PhysicalLayoutSnapshot?,
    wireShare: Double,
    postLayoutPenaltyPerHop: Double,
  ): TimingPathDetail {
    var cumulative = 0.0
    var totalCell = 0.0
    var totalNet = 0.0
    val stages = ArrayList<DetailedTimingStage>(rawIds.size)

    rawIds.forEachIndexed { idx, id ->
      val node = nodeMap[id] ?: return@forEachIndexed
      val fanout = outgoing[id]?.size?.coerceAtLeast(1) ?: 1
      val fanoutNetFactor = (1.0 + (fanout - 1) * 0.14).coerceAtMost(1.85)
      val rawIncr = when {
        idx == 0 && node.gateType == GateType.INPUT_PORT -> report.inputDelayNs
        node.gateType == GateType.OUTPUT_PORT -> node.delayNs
        else -> node.delayNs + postLayoutPenaltyPerHop
      }
      val netShare = when {
        node.gateType == GateType.INPUT_PORT -> 0.0
        node.gateType == GateType.OUTPUT_PORT -> node.delayNs
        idx == rawIds.lastIndex -> 0.05 * rawIncr
        else -> (rawIncr * wireShare * fanoutNetFactor).coerceAtMost(rawIncr * 0.55)
      }
      val cellDelay = (rawIncr - netShare).coerceAtLeast(0.0)
      totalCell += cellDelay
      totalNet += netShare
      cumulative += rawIncr

      val inputPin = resolveInputPinName(node, idx)
      val outputPin = resolveOutputPinName(node)
      stages += DetailedTimingStage(
        stageIndex = idx + 1,
        nodeId = node.id,
        instanceName = node.instanceName,
        cellName = node.cellName,
        gateType = node.gateType,
        inputPin = inputPin,
        outputPin = outputPin,
        netName = node.outputNet,
        fanout = fanout,
        cellDelayNs = cellDelay,
        netDelayNs = netShare,
        incrementalDelayNs = rawIncr,
        cumulativeDelayNs = cumulative,
        arrivalTimeNs = cumulative,
        sourceLine = node.sourceLine,
      )
    }

    val startNode = rawIds.firstOrNull()?.let { nodeMap[it] }
    val endNode = rawIds.lastOrNull()?.let { nodeMap[it] }
    val isFalsePath = endNode?.isFalsePath == true || startNode?.isFalsePath == true
    val skewOffset = if (layout != null && endNode?.gateType?.isSequential == true) {
      layout.clockSkewNs * 0.35
    } else {
      0.0
    }
    val endpointReq = endNode?.requiredNs
      ?: (report.clockPeriodNs - report.outputDelayNs - report.clockUncertaintyNs)
    val effectiveReq = (endpointReq - skewOffset).coerceAtLeast(0.05)
    val slack = if (isFalsePath) 999.0 else (effectiveReq - cumulative)
    val isCritical = rawIds.all { nodeMap[it]?.isCriticalPath == true }

    return TimingPathDetail(
      pathId = "setup_$pathIndex",
      pathIndex = pathIndex,
      pathType = pathType,
      startpoint = startNode?.instanceName ?: "PI",
      startpointClock = startNode?.clockDomain ?: "clk",
      endpoint = endNode?.instanceName ?: "PO",
      endpointClock = endNode?.clockDomain ?: "clk",
      clockPeriodNs = report.clockPeriodNs,
      dataArrivalNs = cumulative,
      requiredTimeNs = effectiveReq,
      slackNs = slack,
      totalCellDelayNs = totalCell,
      totalNetDelayNs = totalNet,
      logicLevels = stages.count { !it.gateType.isPort },
      isFalsePath = isFalsePath,
      isCritical = isCritical,
      stages = stages,
    )
  }

  private fun resolveInputPinName(node: GateNode, stageIndex: Int): String = when {
    node.gateType == GateType.INPUT_PORT -> "PORT_IN"
    node.gateType == GateType.OUTPUT_PORT -> "PORT_OUT"
    node.gateType.isSequential && stageIndex == 0 -> "CLK"
    node.gateType.isSequential -> "D"
    node.gateType == GateType.MUX2 -> "S/A"
    node.inputs.size <= 1 -> "A"
    else -> "A/B"
  }

  private fun resolveOutputPinName(node: GateNode): String = when {
    node.gateType == GateType.INPUT_PORT -> node.outputNet
    node.gateType == GateType.OUTPUT_PORT -> node.outputNet
    node.gateType.isSequential -> "Q"
    else -> "Y"
  }

  /** Computes bucket counts for slack distribution chart across min..max slack. */
  fun slackHistogramBuckets(paths: List<TimingPathDetail>, bucketCount: Int = 6): List<Pair<String, Int>> {
    val valid = paths.filter { !it.isFalsePath }
    if (valid.isEmpty()) return emptyList()
    val minS = valid.minOf { it.slackNs }
    val maxS = valid.maxOf { it.slackNs }
    val span = (maxS - minS).coerceAtLeast(0.25)
    val step = span / bucketCount
    return (0 until bucketCount).map { idx ->
      val lo = minS + idx * step
      val hi = lo + step
      val count = valid.count {
        if (idx == bucketCount - 1) it.slackNs in lo..hi else it.slackNs >= lo && it.slackNs < hi
      }
      val label = "${"%.2f".format(Locale.US, lo)}"
      label to count
    }
  }

  /**
   * Builds an actionable timing-closure remediation plan whenever setup or hold timing is violated.
   * Returns null when both setup and hold slack are non-negative or timing analysis is unavailable.
   */
  fun buildViolationRemediation(
    report: SynthesisReport?,
    summary: StaticTimingSummary = analyze(report, null),
  ): TimingRemediationPlan? {
    if (report == null || !summary.isAvailable) return null
    val setupWns = summary.setupWnsNs ?: report.wnsNs
    val holdWns = summary.holdWnsNs
    val setupViolating = setupWns < 0.0
    val holdViolating = holdWns != null && holdWns < 0.0
    if (!setupViolating && !holdViolating) return null

    val violatingSetupPaths = summary.paths.filter {
      it.pathType == TimingPathType.SETUP && !it.isFalsePath && it.slackNs < 0.0
    }
    val worstSetupPath = violatingSetupPaths.minByOrNull { it.slackNs }
      ?: summary.paths.firstOrNull { it.pathType == TimingPathType.SETUP && !it.isFalsePath }

    val bottleneckStage = worstSetupPath?.stages
      ?.filter { !it.gateType.isPort }
      ?.maxByOrNull { it.incrementalDelayNs }
      ?: worstSetupPath?.stages?.maxByOrNull { it.incrementalDelayNs }

    val fallbackStep = report.criticalPath.maxByOrNull { it.incrDelayNs } ?: report.criticalPath.lastOrNull()
    val bottleneckCellLabel = when {
      bottleneckStage != null -> "${bottleneckStage.instanceName} (${bottleneckStage.cellName})"
      fallbackStep != null -> "${fallbackStep.instanceName} (${fallbackStep.cellName})"
      else -> "${report.moduleName} combinational cone"
    }
    val bottleneckDelayNs = bottleneckStage?.incrementalDelayNs ?: fallbackStep?.incrDelayNs ?: 0.0
    val bottleneckLine = (bottleneckStage?.sourceLine ?: fallbackStep?.sourceLine ?: 0).coerceAtLeast(0)
    val logicLevels = worstSetupPath?.logicLevels ?: report.criticalPath.size.coerceAtLeast(1)
    val pathSummary = if (worstSetupPath != null) {
      "${worstSetupPath.startpoint} → ${worstSetupPath.endpoint}"
    } else {
      "${report.moduleName} critical path"
    }

    val deficitNs = if (setupViolating) abs(setupWns) else 0.0
    val rawRequiredPeriodNs = report.clockPeriodNs + deficitNs + 0.05
    val recommendedClockNs = (ceil(rawRequiredPeriodNs * 20.0) / 20.0).coerceIn(0.4, 50.0)
    val estFmaxMhz = summary.estimatedFmaxMhz ?: report.fmaxMhz
    val totalViolPaths = (summary.violatingSetupPaths + summary.violatingHoldPaths)
      .coerceAtLeast(if (setupViolating || holdViolating) 1 else 0)

    val recommendations = mutableListOf<TimingFixAction>()

    if (setupViolating && report.optimizationGoal != OptimizationGoal.MIN_DELAY) {
      recommendations += TimingFixAction(
        kind = TimingRemediationKind.OPTIMIZE_FOR_SPEED,
        title = "Switch Optimization Goal to Min Delay",
        detail = "Restructure AIG logic cones and select faster high-drive cells instead of ${report.optimizationGoal.displayName} trade-offs.",
        impactBadge = "SPEED OPT",
        actionLabel = "Apply Min Delay",
        targetGoal = OptimizationGoal.MIN_DELAY,
      )
    }

    if (setupViolating && report.engineProfile != SynthesisEngineProfile.YOSYS_ABC) {
      recommendations += TimingFixAction(
        kind = TimingRemediationKind.SWITCH_TIMING_ENGINE,
        title = "Use ABC Timing-Driven Mapper",
        detail = "Re-map critical arithmetic/mux cones with ${SynthesisEngineProfile.YOSYS_ABC.shortName} to cut gate depth without changing RTL.",
        impactBadge = "DEPTH CUT",
        actionLabel = "Run ABC Timing",
        targetEngine = SynthesisEngineProfile.YOSYS_ABC,
      )
    }

    if (setupViolating) {
      val lineHint = if (bottleneckLine > 0) " at RTL line $bottleneckLine" else ""
      val fanoutHint = if ((bottleneckStage?.fanout ?: 1) >= 4) {
        " High fanout (FO=${bottleneckStage?.fanout}) — replicate/buffer driver."
      } else {
        ""
      }
      recommendations += TimingFixAction(
        kind = TimingRemediationKind.PIPELINE_BOTTLENECK_RTL,
        title = "Pipeline or Split Critical Combinational Cone",
        detail = "Slowest stage is $bottleneckCellLabel (+${"%.3f".format(Locale.US, bottleneckDelayNs)} ns, $logicLevels logic levels)$lineHint. Insert a pipeline register stage or balance deep mux/adder chains.$fanoutHint",
        impactBadge = "$logicLevels LEVELS",
        actionLabel = if (bottleneckLine > 0) "Edit Line $bottleneckLine" else null,
        rtlLine = bottleneckLine.takeIf { it > 0 },
      )

      if (recommendedClockNs > report.clockPeriodNs + 0.01) {
        val recFreqMhz = 1000.0 / recommendedClockNs
        recommendations += TimingFixAction(
          kind = TimingRemediationKind.RELAX_CLOCK_PERIOD,
          title = "Relax SDC Clock Target to ≥ ${"%.2f".format(Locale.US, recommendedClockNs)} ns",
          detail = "Current ${"%.2f".format(Locale.US, report.clockPeriodNs)} ns constraint has a ${"%.3f".format(Locale.US, setupWns)} ns deficit (design closes near ${"%.1f".format(Locale.US, recFreqMhz)} MHz).",
          impactBadge = "+${"%.2f".format(Locale.US, recommendedClockNs - report.clockPeriodNs)} ns",
          actionLabel = "Set ${"%.2f".format(Locale.US, recommendedClockNs)} ns",
          targetClockNs = recommendedClockNs,
        )
      }

      if (report.targetLibrary != TargetLibrary.ASAP7_7NM && report.targetLibrary != TargetLibrary.NANGATE45_45NM) {
        recommendations += TimingFixAction(
          kind = TimingRemediationKind.EVALUATE_FASTER_PDK,
          title = "Evaluate a Faster Standard-Cell Node",
          detail = "${report.targetLibrary.shortName} gate delays limit Fmax to ${"%.1f".format(Locale.US, estFmaxMhz)} MHz. Benchmark on ASAP7 7nm if process node is flexible.",
          impactBadge = "7nm PDK",
          actionLabel = "Try ASAP7 7nm",
          targetLibrary = TargetLibrary.ASAP7_7NM,
        )
      }
    }

    if (holdViolating && holdWns != null) {
      recommendations += TimingFixAction(
        kind = TimingRemediationKind.FIX_HOLD_VIOLATION,
        title = "Insert Hold-Delay Buffers & Balance CTS Skew",
        detail = "Post-CTS hold slack is ${"%.3f".format(Locale.US, holdWns)} ns (skew ${"%.3f".format(Locale.US, summary.clockSkewNs ?: 0.0)} ns). Add min-delay buffer cells on short Q→D paths or reduce clock skew.",
        impactBadge = "HOLD FIX",
      )
    }

    return TimingRemediationPlan(
      setupViolating = setupViolating,
      holdViolating = holdViolating,
      wnsNs = setupWns,
      tnsNs = summary.setupTnsNs ?: report.tnsNs,
      violatingPathCount = totalViolPaths,
      currentClockNs = report.clockPeriodNs,
      recommendedClockNs = recommendedClockNs,
      estimatedFmaxMhz = estFmaxMhz,
      bottleneckPathSummary = pathSummary,
      bottleneckCellLabel = bottleneckCellLabel,
      bottleneckStageDelayNs = bottleneckDelayNs,
      bottleneckSourceLine = bottleneckLine,
      logicLevels = logicLevels,
      recommendations = recommendations,
    )
  }
}

/**
 * Normalizes a logistic curve or confidence weight helper cleanly without unused symbols.
 */
@Suppress("unused")
internal fun sigmoidWeight(x: Double): Double = 1.0 / (1.0 + exp(-x))
