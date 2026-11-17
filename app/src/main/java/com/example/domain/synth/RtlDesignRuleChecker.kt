package com.example.domain.synth

import java.util.Locale
import kotlin.math.abs

enum class RtlDrcSeverity(val label: String, val rank: Int) {
  ERROR("ERROR", 0),
  WARNING("WARN", 1),
  INFO("INFO", 2),
}

enum class RtlDrcCategory(val label: String) {
  RESET("Reset Hygiene"),
  LATCH("Latch Inference"),
  COMB_LOOP("Combinational Loop"),
  CLOCK_SENSITIVITY("Sensitivity & Clocking"),
  CONNECTIVITY("Drivers & Widths"),
}

data class RtlDrcViolation(
  val ruleId: String,
  val category: RtlDrcCategory,
  val severity: RtlDrcSeverity,
  val moduleName: String,
  val signalOrBlock: String,
  val line: Int,
  val title: String,
  val message: String,
  val recommendation: String,
)

data class RtlDrcReport(
  val topModuleName: String,
  val modulesScanned: Int,
  val rulesEvaluated: Int,
  val violations: List<RtlDrcViolation>,
  val passedChecks: List<String>,
  val scanDurationMs: Long,
) {
  val errorCount: Int
    get() = violations.count { it.severity == RtlDrcSeverity.ERROR }

  val warningCount: Int
    get() = violations.count { it.severity == RtlDrcSeverity.WARNING }

  val infoCount: Int
    get() = violations.count { it.severity == RtlDrcSeverity.INFO }

  val isClean: Boolean
    get() = errorCount == 0 && warningCount == 0

  val hasBlockingErrors: Boolean
    get() = errorCount > 0

  fun summaryBadgeText(): String = when {
    errorCount > 0 -> "$errorCount ERR · $warningCount WARN"
    warningCount > 0 -> "$warningCount WARN"
    infoCount > 0 -> "CLEAN ($infoCount INFO)"
    else -> "DRC CLEAN"
  }

  fun formatTextReport(): String = buildString {
    appendLine("====================================================")
    appendLine(" RTL SYNTH STUDIO :: PRE-SYNTHESIS RTL DRC REPORT")
    appendLine("====================================================")
    appendLine("Top Module       : $topModuleName")
    appendLine("Modules Scanned  : $modulesScanned")
    appendLine("Rules Evaluated  : $rulesEvaluated")
    appendLine("Summary          : $errorCount error(s), $warningCount warning(s), $infoCount info(s)")
    appendLine("Scan Duration    : $scanDurationMs ms")
    appendLine("----------------------------------------------------")
    if (violations.isEmpty()) {
      appendLine("Status: PASS — Zero RTL design rule violations detected.")
    } else {
      violations.forEachIndexed { idx, v ->
        appendLine("[${idx + 1}] [${v.severity.label}] ${v.ruleId} (${v.category.label}) @ ${v.moduleName}:L${v.line}")
        appendLine("    Target : ${v.signalOrBlock}")
        appendLine("    Issue  : ${v.title} — ${v.message}")
        appendLine("    Fix    : ${v.recommendation}")
      }
    }
    if (passedChecks.isNotEmpty()) {
      appendLine("----------------------------------------------------")
      appendLine("Passed Checks:")
      passedChecks.forEach { check ->
        appendLine("  [PASS] $check")
      }
    }
  }
}

sealed interface RtlDrcScanOutcome {
  data class Ready(val report: RtlDrcReport) : RtlDrcScanOutcome
  data class ParseFailed(val error: VerilogParseResult.Error) : RtlDrcScanOutcome
}

/**
 * Static Pre-Synthesis RTL Design Rule Checker (DRC / Lint Engine).
 *
 * Scans parsed Verilog / SystemVerilog (and lowered VHDL) ASTs before synthesis for:
 * - Missing or incomplete reset initialization in sequential blocks (`DRC-RST-01`, `DRC-RST-02`, `DRC-RST-03`)
 * - Unintended latch inference via incomplete `if`/`case` paths in combinational blocks (`DRC-LATCH-01`, `DRC-LATCH-02`, `DRC-LATCH-03`)
 * - Zero-delay combinational feedback loops across continuous assigns, always blocks, and instances (`DRC-LOOP-01`)
 * - Incomplete explicit sensitivity lists (`DRC-SENS-01`)
 * - Multi-driver contention, undriven output ports, and assignment width mismatches (`DRC-CONN-01`, `DRC-CONN-02`, `DRC-CONN-03`)
 */
object RtlDesignRuleChecker {

  private const val TOTAL_RULE_COUNT = 10

  private val resetSignalHintRegex = Regex(
    """^(?:a?rst_?n?|s?rst_?n?|n?_?rst|a?reset_?n?|s?reset_?n?|clr|clear|rst_i|reset_i|preset)$""",
    RegexOption.IGNORE_CASE,
  )

  private val gatePrimitives = setOf(
    "and", "nand", "or", "nor", "xor", "xnor", "buf", "not", "bufif0", "bufif1", "notif0", "notif1",
  )

  fun checkSource(
    source: String,
    preferredTopModule: String? = null,
  ): RtlDrcScanOutcome {
    val effectiveSource = if (VhdlFrontendParser.looksLikeVhdl(source)) {
      when (val conv = VhdlFrontendParser.toCanonicalVerilog(source)) {
        is VhdlConversionResult.Error -> {
          return RtlDrcScanOutcome.ParseFailed(
            VerilogParseResult.Error(line = conv.line, column = 1, message = conv.message)
          )
        }
        is VhdlConversionResult.Success -> conv.verilogSource
      }
    } else {
      source
    }

    return when (val parsed = VerilogHdlParser.parse(effectiveSource, preferredTopModule)) {
      is VerilogParseResult.Error -> RtlDrcScanOutcome.ParseFailed(parsed)
      is VerilogParseResult.Success -> RtlDrcScanOutcome.Ready(checkDesign(parsed.design, effectiveSource))
    }
  }

  fun checkDesign(
    design: ParsedVerilogDesign,
    source: String = "",
  ): RtlDrcReport {
    val startedNs = System.nanoTime()
    val violations = mutableListOf<RtlDrcViolation>()
    val modByName = design.modules.associateBy { it.name }

    design.modules.forEach { mod ->
      checkResetRules(mod, violations)
      checkLatchAndCombBlockRules(mod, violations)
      checkSensitivityRules(mod, violations)
      checkCombinationalLoops(mod, modByName, violations)
      checkDriverAndConnectivityRules(mod, modByName, violations)
    }

    val deduped = violations
      .distinctBy { Triple(it.ruleId, it.moduleName, it.signalOrBlock to it.line) }
      .sortedWith(compareBy<RtlDrcViolation> { it.severity.rank }.thenBy { it.line }.thenBy { it.ruleId })

    val passedChecks = buildPassedChecks(design.modules, deduped)
    val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)

    return RtlDrcReport(
      topModuleName = design.topModule.name,
      modulesScanned = design.modules.size,
      rulesEvaluated = TOTAL_RULE_COUNT,
      violations = deduped,
      passedChecks = passedChecks,
      scanDurationMs = elapsedMs,
    )
  }

  // =========================================================================
  // 1. RESET HYGIENE & SEQUENTIAL PROCEDURAL RULES
  // =========================================================================

  private fun checkResetRules(
    mod: ParsedVerilogModule,
    out: MutableList<RtlDrcViolation>,
  ) {
    val seqBlocks = mod.alwaysBlocks.filter { it.isSequential }
    seqBlocks.forEach { block ->
      val allAssignedRegs = mutableSetOf<String>()
      collectAssignedTargets(block.statements, allAssignedRegs)
      if (allAssignedRegs.isEmpty()) return@forEach

      val targetListLabel = allAssignedRegs.sorted().joinToString(", ")

      // DRC-RST-03: Blocking assignment '=' in sequential block
      val blockingAssigns = mutableListOf<VerilogStmt.Assign>()
      collectAssignmentsMatching(block.statements) { assign ->
        if (!assign.nonBlocking) blockingAssigns += assign
      }
      blockingAssigns.forEach { assign ->
        out += RtlDrcViolation(
          ruleId = "DRC-RST-03",
          category = RtlDrcCategory.CLOCK_SENSITIVITY,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = assign.target,
          line = assign.line,
          title = "Blocking Assignment in Sequential Block",
          message = "Sequential register '${assign.target}' uses blocking '=' instead of non-blocking '<=' inside clocked always block.",
          recommendation = "Use non-blocking '<=' for all sequential flip-flop updates to prevent simulation race conditions.",
        )
      }

      // Check for async reset in sensitivity list OR sync reset 'if' branch in statements
      val resetSplit = findTopLevelResetIf(block.statements, block.resetName)
      if (block.resetName == null && resetSplit == null) {
        out += RtlDrcViolation(
          ruleId = "DRC-RST-01",
          category = RtlDrcCategory.RESET,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = targetListLabel,
          line = block.line,
          title = "Missing Reset Signal",
          message = "Sequential block (@(${block.clockName ?: "clk"})) drives register(s) [$targetListLabel] without an asynchronous or synchronous reset.",
          recommendation = "Add an async reset (e.g. '@(posedge clk or negedge rst_n)') or a synchronous 'if (rst)' initialization branch.",
        )
      } else if (resetSplit != null) {
        // DRC-RST-02: Verify every register updated in functional branch is initialized in reset branch
        val (resetBranch, funcBranch, resetLine) = resetSplit
        val resetRegs = mutableSetOf<String>()
        val funcRegs = mutableSetOf<String>()
        collectAssignedTargets(resetBranch, resetRegs)
        collectAssignedTargets(funcBranch, funcRegs)
        val missingInReset = (funcRegs - resetRegs).sorted()
        if (missingInReset.isNotEmpty()) {
          out += RtlDrcViolation(
            ruleId = "DRC-RST-02",
            category = RtlDrcCategory.RESET,
            severity = RtlDrcSeverity.WARNING,
            moduleName = mod.name,
            signalOrBlock = missingInReset.joinToString(", "),
            line = resetLine,
            title = "Incomplete Reset Initialization",
            message = "Register(s) [${missingInReset.joinToString(", ")}] are updated in the functional branch but omitted from the reset branch.",
            recommendation = "Assign deterministic reset values to [${missingInReset.joinToString(", ")}] inside the reset branch to avoid X-propagation.",
          )
        }
      } else if (block.resetName != null) {
        // Async reset declared in sensitivity list, but no matching if(resetName) at top level
        out += RtlDrcViolation(
          ruleId = "DRC-RST-02",
          category = RtlDrcCategory.RESET,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = targetListLabel,
          line = block.line,
          title = "Unused Async Reset Signal '${block.resetName}'",
          message = "Sensitivity list declares reset '${block.resetName}', but no top-level reset branch initializes [$targetListLabel].",
          recommendation = "Wrap sequential updates in 'if (${if (block.resetActiveLow) "!${block.resetName}" else block.resetName})' to initialize registers.",
        )
      }
    }
  }

  private data class ResetBranchSplit(
    val resetStmts: List<VerilogStmt>,
    val functionalStmts: List<VerilogStmt>,
    val line: Int,
  )

  private fun findTopLevelResetIf(
    stmts: List<VerilogStmt>,
    explicitResetName: String?,
  ): ResetBranchSplit? {
    var cursorStmts = stmts
    while (true) {
      val firstIf = cursorStmts.firstOrNull { it is VerilogStmt.IfStmt } as? VerilogStmt.IfStmt ?: return null
      if (firstIf.cond is VerilogExpr.Const && firstIf.cond.value != 0 && firstIf.elseBranch.isEmpty()) {
        cursorStmts = firstIf.thenBranch
        continue
      }
      val polarity = matchResetCondition(firstIf.cond, explicitResetName) ?: return null
      return if (polarity) {
        ResetBranchSplit(firstIf.thenBranch, firstIf.elseBranch, firstIf.line)
      } else {
        ResetBranchSplit(firstIf.elseBranch, firstIf.thenBranch, firstIf.line)
      }
    }
  }

  /**
   * Returns:
   * - `true` if [cond] triggers reset when true (`if (rst)`, `if (!rst_n)`, `if (rst == 1'b1)`, `if (rst_n == 1'b0)`)
   * - `false` if [cond] triggers functional mode when true (`if (rst == 1'b0)`? Rare, usually `if (!rst) func else rst`)
   * - `null` if [cond] does not appear to test a reset signal.
   */
  private fun matchResetCondition(expr: VerilogExpr, explicitResetName: String?): Boolean? {
    fun isResetIdent(name: String): Boolean =
      (explicitResetName != null && name.equals(explicitResetName, ignoreCase = true)) ||
        resetSignalHintRegex.matches(name)

    return when (expr) {
      is VerilogExpr.Signal -> if (isResetIdent(expr.name)) true else null
      is VerilogExpr.Unary -> {
        if ((expr.op == "!" || expr.op == "~") && expr.inner is VerilogExpr.Signal && isResetIdent(expr.inner.name)) {
          true
        } else {
          null
        }
      }
      is VerilogExpr.Binary -> {
        val leftSig = expr.left as? VerilogExpr.Signal
        val rightConst = expr.right as? VerilogExpr.Const
        val rightSig = expr.right as? VerilogExpr.Signal
        val leftConst = expr.left as? VerilogExpr.Const
        val matchedSig = when {
          leftSig != null && isResetIdent(leftSig.name) && rightConst != null -> leftSig.name to rightConst.value
          rightSig != null && isResetIdent(rightSig.name) && leftConst != null -> rightSig.name to leftConst.value
          else -> null
        }
        if (matchedSig != null && (expr.op == "==" || expr.op == "===" || expr.op == "!=" || expr.op == "!==")) {
          true
        } else {
          null
        }
      }
      else -> null
    }
  }

  // =========================================================================
  // 2. LATCH INFERENCE & COMBINATIONAL BLOCK RULES
  // =========================================================================

  private data class LatchFinding(
    val signal: String,
    val line: Int,
    val reason: String,
  )

  private fun checkLatchAndCombBlockRules(
    mod: ParsedVerilogModule,
    out: MutableList<RtlDrcViolation>,
  ) {
    mod.alwaysBlocks.filter { !it.isSequential }.forEach { block ->
      if (block.alwaysKind == "always_latch") {
        val targets = mutableSetOf<String>()
        collectAssignedTargets(block.statements, targets)
        out += RtlDrcViolation(
          ruleId = "DRC-LATCH-03",
          category = RtlDrcCategory.LATCH,
          severity = RtlDrcSeverity.INFO,
          moduleName = mod.name,
          signalOrBlock = targets.sorted().joinToString(", ").ifBlank { "always_latch" },
          line = block.line,
          title = "Explicit Level-Sensitive Latch Block",
          message = "'always_latch' explicitly models level-sensitive storage; verify latch timing and DFT scan transparency.",
          recommendation = "Confirm latch intent or convert to edge-triggered 'always_ff' for standard synchronous ASIC/FPGA flows.",
        )
        return@forEach
      }

      // DRC-LATCH-02: Non-blocking '<=' in combinational block
      val nonBlockingInComb = mutableListOf<VerilogStmt.Assign>()
      collectAssignmentsMatching(block.statements) { assign ->
        if (assign.nonBlocking) nonBlockingInComb += assign
      }
      nonBlockingInComb.forEach { assign ->
        out += RtlDrcViolation(
          ruleId = "DRC-LATCH-02",
          category = RtlDrcCategory.LATCH,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = assign.target,
          line = assign.line,
          title = "Non-Blocking Assignment in Combinational Block",
          message = "Combinational target '${assign.target}' uses '<=' inside ${block.alwaysKind}; may cause delta-cycle simulation mismatch.",
          recommendation = "Use blocking assignment '=' inside combinational always / always_comb blocks.",
        )
      }

      // DRC-LATCH-01: Control-flow path completeness analysis for latch inference
      val latchFindings = mutableListOf<LatchFinding>()
      analyzeCombStmtList(
        stmts = block.statements,
        definitelyAssignedIn = emptySet(),
        mod = mod,
        findings = latchFindings,
      )

      latchFindings
        .distinctBy { it.signal to it.line }
        .forEach { finding ->
          out += RtlDrcViolation(
            ruleId = "DRC-LATCH-01",
            category = RtlDrcCategory.LATCH,
            severity = RtlDrcSeverity.WARNING,
            moduleName = mod.name,
            signalOrBlock = finding.signal,
            line = finding.line,
            title = "Inferred Latch on '${finding.signal}'",
            message = "Signal '${finding.signal}' is not assigned on all control paths (${finding.reason}), inferring an unintended level-sensitive latch.",
            recommendation = "Assign a default value to '${finding.signal}' before 'if'/'case', or complete all 'else' and 'default' branches.",
          )
        }
    }
  }

  private fun analyzeCombStmtList(
    stmts: List<VerilogStmt>,
    definitelyAssignedIn: Set<String>,
    mod: ParsedVerilogModule,
    findings: MutableList<LatchFinding>,
  ): Set<String> {
    var currentAssigned = definitelyAssignedIn.toMutableSet()

    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> {
          currentAssigned += stmt.target
        }
        is VerilogStmt.IfStmt -> {
          val thenOut = analyzeCombStmtList(stmt.thenBranch, currentAssigned, mod, findings)
          val elseOut = analyzeCombStmtList(stmt.elseBranch, currentAssigned, mod, findings)

          val anyAssignedInIf = mutableSetOf<String>()
          collectAssignedTargets(stmt.thenBranch, anyAssignedInIf)
          collectAssignedTargets(stmt.elseBranch, anyAssignedInIf)

          val guaranteedAfterIf = thenOut intersect elseOut
          val potentiallyLatched = anyAssignedInIf - currentAssigned - guaranteedAfterIf
          potentiallyLatched.sorted().forEach { sig ->
            val reason = if (stmt.elseBranch.isEmpty()) {
              "'if' statement at line ${stmt.line} omits 'else' branch"
            } else {
              "'if'/'else' branches at line ${stmt.line} do not both assign '$sig'"
            }
            findings += LatchFinding(sig, stmt.line, reason)
          }

          currentAssigned = guaranteedAfterIf.toMutableSet()
        }
        is VerilogStmt.CaseStmt -> {
          val branchOutputs = mutableListOf<Set<String>>()
          val anyAssignedInCase = mutableSetOf<String>()

          stmt.branches.forEach { (_, body) ->
            branchOutputs += analyzeCombStmtList(body, currentAssigned, mod, findings)
            collectAssignedTargets(body, anyAssignedInCase)
          }

          val hasDefault = stmt.defaultBranch.isNotEmpty()
          if (hasDefault) {
            branchOutputs += analyzeCombStmtList(stmt.defaultBranch, currentAssigned, mod, findings)
            collectAssignedTargets(stmt.defaultBranch, anyAssignedInCase)
          }

          val coversFullCaseSpace = hasDefault || isCaseExhaustive(stmt, mod)
          val guaranteedAfterCase = if (!coversFullCaseSpace || branchOutputs.isEmpty()) {
            currentAssigned.toSet()
          } else {
            branchOutputs.reduce { acc, set -> acc intersect set }
          }

          val potentiallyLatched = anyAssignedInCase - currentAssigned - guaranteedAfterCase
          potentiallyLatched.sorted().forEach { sig ->
            val reason = if (!coversFullCaseSpace) {
              "'case' statement at line ${stmt.line} lacks a 'default' branch"
            } else {
              "one or more 'case' branches at line ${stmt.line} fail to assign '$sig'"
            }
            findings += LatchFinding(sig, stmt.line, reason)
          }

          currentAssigned = guaranteedAfterCase.toMutableSet()
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }

    return currentAssigned
  }

  private fun isCaseExhaustive(stmt: VerilogStmt.CaseStmt, mod: ParsedVerilogModule): Boolean {
    val selWidth = estimateExprWidth(stmt.selector, mod).coerceIn(1, 16)
    if (selWidth > 6) return false
    val totalStates = 1 shl selWidth
    val coveredValues = mutableSetOf<Int>()
    stmt.branches.forEach { (labels, _) ->
      labels.forEach { labelExpr ->
        val c = evalConstantExpr(labelExpr) ?: return false
        coveredValues += c
      }
    }
    return coveredValues.size >= totalStates
  }

  // =========================================================================
  // 3. SENSITIVITY LIST COMPLETENESS
  // =========================================================================

  private fun checkSensitivityRules(
    mod: ParsedVerilogModule,
    out: MutableList<RtlDrcViolation>,
  ) {
    val paramNames = mod.parameters.map { it.name }.toSet()
    mod.alwaysBlocks.forEach { block ->
      if (block.isSequential || block.hasStarSensitivity || block.alwaysKind != "always") return@forEach
      val listed = block.sensitivitySignals.toSet()
      if (listed.isEmpty()) return@forEach

      val reads = mutableSetOf<String>()
      collectReadSignalsInStmts(block.statements, reads)
      val missing = (reads - listed - paramNames).sorted()
      if (missing.isNotEmpty()) {
        out += RtlDrcViolation(
          ruleId = "DRC-SENS-01",
          category = RtlDrcCategory.CLOCK_SENSITIVITY,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = missing.joinToString(", "),
          line = block.line,
          title = "Incomplete Sensitivity List",
          message = "Combinational always block reads [${missing.joinToString(", ")}] without listing them in @(${block.sensitivitySignals.joinToString(", ")}).",
          recommendation = "Replace explicit sensitivity list with 'always @(*)' or SystemVerilog 'always_comb'.",
        )
      }
    }
  }

  // =========================================================================
  // 4. COMBINATIONAL FEEDBACK LOOP DETECTION
  // =========================================================================

  private data class DepEdge(
    val fromSignal: String,
    val fromSlice: IntRange?,
    val toSignal: String,
    val toSlice: IntRange?,
    val line: Int,
  )

  private fun checkCombinationalLoops(
    mod: ParsedVerilogModule,
    modByName: Map<String, ParsedVerilogModule>,
    out: MutableList<RtlDrcViolation>,
  ) {
    val paramNames = mod.parameters.map { it.name }.toSet()
    val edges = mutableListOf<DepEdge>()

    // A. Continuous assign statements
    mod.assignments.forEach { assign ->
      val targetSlice = concreteSlice(assign.targetMsb, assign.targetLsb)
      val rhsDeps = mutableListOf<Pair<String, IntRange?>>()
      collectExprSignalDeps(assign.expr, rhsDeps)
      rhsDeps.forEach { (depName, depSlice) ->
        if (depName !in paramNames) {
          if (depName == assign.target && targetSlice != null && depSlice != null && !rangesOverlap(targetSlice, depSlice)) {
            return@forEach
          }
          edges += DepEdge(depName, depSlice, assign.target, targetSlice, assign.line)
        }
      }
    }

    // B. Combinational always blocks (skip sequential blocks — flip-flops break combinational cycles!)
    mod.alwaysBlocks.filter { !it.isSequential }.forEach { block ->
      collectCombBlockDeps(
        stmts = block.statements,
        conditionDeps = emptySet(),
        assignedSoFar = mutableSetOf(),
        paramNames = paramNames,
        edges = edges,
      )
    }

    // C. Built-in gate primitives & instantiated combinational modules
    mod.instances.forEach { inst ->
      if (inst.moduleType in gatePrimitives && inst.positionalConnections.size >= 2) {
        val outTargets = mutableListOf<Pair<String, IntRange?>>()
        collectLhsSignalTargets(inst.positionalConnections.first(), outTargets)
        val inDeps = mutableListOf<Pair<String, IntRange?>>()
        inst.positionalConnections.drop(1).forEach { collectExprSignalDeps(it, inDeps) }
        outTargets.forEach { (outSig, outSlice) ->
          inDeps.forEach { (inSig, inSlice) ->
            if (inSig !in paramNames) {
              edges += DepEdge(inSig, inSlice, outSig, outSlice, inst.line)
            }
          }
        }
      } else {
        val subMod = modByName[inst.moduleType]
        if (subMod != null && subMod.alwaysBlocks.none { it.isSequential }) {
          val inDeps = mutableListOf<Pair<String, IntRange?>>()
          val outTargets = mutableListOf<Pair<String, IntRange?>>()
          inst.portConnections.forEach { (pName, expr) ->
            val dir = subMod.ports.firstOrNull { it.name == pName }?.direction
            if (dir == PortDirection.OUTPUT) collectLhsSignalTargets(expr, outTargets)
            else if (dir == PortDirection.INPUT) collectExprSignalDeps(expr, inDeps)
          }
          inst.positionalConnections.forEachIndexed { idx, expr ->
            val dir = subMod.ports.getOrNull(idx)?.direction
            if (dir == PortDirection.OUTPUT) collectLhsSignalTargets(expr, outTargets)
            else if (dir == PortDirection.INPUT) collectExprSignalDeps(expr, inDeps)
          }
          outTargets.forEach { (outSig, outSlice) ->
            inDeps.forEach { (inSig, inSlice) ->
              if (inSig !in paramNames) {
                edges += DepEdge(inSig, inSlice, outSig, outSlice, inst.line)
              }
            }
          }
        }
      }
    }

    if (edges.isEmpty()) return

    // Build adjacency list and detect cycles via DFS
    val adj = mutableMapOf<String, MutableList<DepEdge>>()
    edges.forEach { e -> adj.getOrPut(e.fromSignal) { mutableListOf() }.add(e) }

    val color = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=done
    val stackNodes = mutableListOf<String>()
    val stackEdges = mutableListOf<DepEdge>()
    val reportedCycles = mutableSetOf<Set<String>>()

    fun dfs(node: String) {
      color[node] = 1
      stackNodes += node
      for (edge in adj[node].orEmpty()) {
        val next = edge.toSignal
        when (color[next] ?: 0) {
          1 -> {
            val cycleStart = stackNodes.indexOf(next)
            if (cycleStart >= 0) {
              val cycleNodes = stackNodes.subList(cycleStart, stackNodes.size) + next
              val cycleEdges = stackEdges.drop(cycleStart) + edge
              val canonicalKey = cycleNodes.toSet()
              if (reportedCycles.add(canonicalKey)) {
                val minLine = cycleEdges.minOfOrNull { it.line } ?: mod.line
                val chain = cycleNodes.joinToString(" -> ")
                out += RtlDrcViolation(
                  ruleId = "DRC-LOOP-01",
                  category = RtlDrcCategory.COMB_LOOP,
                  severity = RtlDrcSeverity.ERROR,
                  moduleName = mod.name,
                  signalOrBlock = cycleNodes.first(),
                  line = minLine,
                  title = "Combinational Feedback Loop",
                  message = "Zero-delay combinational cycle detected: $chain.",
                  recommendation = "Break the combinational feedback path by inserting a clocked register (always @(posedge clk)) or removing the cyclic dependency.",
                )
              }
            }
          }
          0 -> {
            stackEdges += edge
            dfs(next)
            stackEdges.removeAt(stackEdges.lastIndex)
          }
        }
      }
      stackNodes.removeAt(stackNodes.lastIndex)
      color[node] = 2
    }

    adj.keys.sorted().forEach { node ->
      if ((color[node] ?: 0) == 0) dfs(node)
    }
  }

  private fun collectCombBlockDeps(
    stmts: List<VerilogStmt>,
    conditionDeps: Set<String>,
    assignedSoFar: MutableSet<String>,
    paramNames: Set<String>,
    edges: MutableList<DepEdge>,
  ) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> {
          val targetSlice = concreteSlice(stmt.targetMsb, stmt.targetLsb)
          val rhsDeps = mutableListOf<Pair<String, IntRange?>>()
          collectExprSignalDeps(stmt.expr, rhsDeps)
          rhsDeps.forEach { (dep, depSlice) ->
            if (dep !in paramNames && dep !in assignedSoFar) {
              if (dep == stmt.target && targetSlice != null && depSlice != null && !rangesOverlap(targetSlice, depSlice)) {
                return@forEach
              }
              edges += DepEdge(dep, depSlice, stmt.target, targetSlice, stmt.line)
            }
          }
          conditionDeps.forEach { condDep ->
            if (condDep !in paramNames && condDep !in assignedSoFar) {
              edges += DepEdge(condDep, null, stmt.target, targetSlice, stmt.line)
            }
          }
          assignedSoFar += stmt.target
        }
        is VerilogStmt.IfStmt -> {
          val condReads = mutableListOf<Pair<String, IntRange?>>()
          collectExprSignalDeps(stmt.cond, condReads)
          val nextCondDeps = conditionDeps + condReads.map { it.first }
          val thenAssigned = assignedSoFar.toMutableSet()
          val elseAssigned = assignedSoFar.toMutableSet()
          collectCombBlockDeps(stmt.thenBranch, nextCondDeps, thenAssigned, paramNames, edges)
          collectCombBlockDeps(stmt.elseBranch, nextCondDeps, elseAssigned, paramNames, edges)
          assignedSoFar.addAll(thenAssigned intersect elseAssigned)
        }
        is VerilogStmt.CaseStmt -> {
          val selReads = mutableListOf<Pair<String, IntRange?>>()
          collectExprSignalDeps(stmt.selector, selReads)
          val nextCondDeps = conditionDeps + selReads.map { it.first }
          val branchSets = mutableListOf<Set<String>>()
          stmt.branches.forEach { (_, body) ->
            val copy = assignedSoFar.toMutableSet()
            collectCombBlockDeps(body, nextCondDeps, copy, paramNames, edges)
            branchSets += copy
          }
          if (stmt.defaultBranch.isNotEmpty()) {
            val copy = assignedSoFar.toMutableSet()
            collectCombBlockDeps(stmt.defaultBranch, nextCondDeps, copy, paramNames, edges)
            branchSets += copy
          }
          if (stmt.defaultBranch.isNotEmpty() && branchSets.isNotEmpty()) {
            assignedSoFar.addAll(branchSets.reduce { acc, s -> acc intersect s })
          }
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }
  }

  // =========================================================================
  // 5. DRIVER CONTENTION, UNDRIVEN PORTS & WIDTH CHECKS
  // =========================================================================

  private data class DriverSite(
    val sourceId: String,
    val slice: IntRange?,
    val line: Int,
  )

  private fun checkDriverAndConnectivityRules(
    mod: ParsedVerilogModule,
    modByName: Map<String, ParsedVerilogModule>,
    out: MutableList<RtlDrcViolation>,
  ) {
    val driversBySignal = mutableMapOf<String, MutableList<DriverSite>>()

    mod.assignments.forEachIndexed { idx, assign ->
      driversBySignal.getOrPut(assign.target) { mutableListOf() }.add(
        DriverSite("assign#$idx", concreteSlice(assign.targetMsb, assign.targetLsb), assign.line)
      )
      checkWidthMismatch(mod, assign.target, assign.targetMsb, assign.targetLsb, assign.expr, assign.line, out)
    }

    mod.alwaysBlocks.forEachIndexed { blockIdx, block ->
      val targetsInBlock = mutableMapOf<String, Pair<IntRange?, Int>>()
      collectProceduralDriverTargets(block.statements, targetsInBlock)
      targetsInBlock.forEach { (sig, sliceAndLine) ->
        driversBySignal.getOrPut(sig) { mutableListOf() }.add(
          DriverSite("always#$blockIdx", sliceAndLine.first, sliceAndLine.second)
        )
      }
      collectAssignmentsMatching(block.statements) { assign ->
        checkWidthMismatch(mod, assign.target, assign.targetMsb, assign.targetLsb, assign.expr, assign.line, out)
      }
    }

    mod.instances.forEachIndexed { instIdx, inst ->
      if (inst.moduleType in gatePrimitives && inst.positionalConnections.isNotEmpty()) {
        val targets = mutableListOf<Pair<String, IntRange?>>()
        collectLhsSignalTargets(inst.positionalConnections.first(), targets)
        targets.forEach { (sig, slice) ->
          driversBySignal.getOrPut(sig) { mutableListOf() }.add(
            DriverSite("inst#$instIdx", slice, inst.line)
          )
        }
      } else {
        val subMod = modByName[inst.moduleType]
        inst.portConnections.forEach { (portName, expr) ->
          val dir = subMod?.ports?.firstOrNull { it.name == portName }?.direction
          if (dir == PortDirection.OUTPUT) {
            val targets = mutableListOf<Pair<String, IntRange?>>()
            collectLhsSignalTargets(expr, targets)
            targets.forEach { (sig, slice) ->
              driversBySignal.getOrPut(sig) { mutableListOf() }.add(
                DriverSite("inst#$instIdx", slice, inst.line)
              )
            }
          }
        }
        inst.positionalConnections.forEachIndexed { idx, expr ->
          val dir = subMod?.ports?.getOrNull(idx)?.direction
          if (dir == PortDirection.OUTPUT) {
            val targets = mutableListOf<Pair<String, IntRange?>>()
            collectLhsSignalTargets(expr, targets)
            targets.forEach { (sig, slice) ->
              driversBySignal.getOrPut(sig) { mutableListOf() }.add(
                DriverSite("inst#$instIdx", slice, inst.line)
              )
            }
          }
        }
      }
    }

    // DRC-CONN-01: Multiple Drivers on same signal/bit range
    driversBySignal.forEach { (sig, sites) ->
      val distinctSites = sites.distinctBy { it.sourceId }
      if (distinctSites.size > 1) {
        val hasOverlap = distinctSites.indices.any { i ->
          ((i + 1) until distinctSites.size).any { j ->
            val a = distinctSites[i].slice
            val b = distinctSites[j].slice
            a == null || b == null || rangesOverlap(a, b)
          }
        }
        if (hasOverlap) {
          val lines = distinctSites.map { "L${it.line}" }.distinct().joinToString(", ")
          out += RtlDrcViolation(
            ruleId = "DRC-CONN-01",
            category = RtlDrcCategory.CONNECTIVITY,
            severity = RtlDrcSeverity.ERROR,
            moduleName = mod.name,
            signalOrBlock = sig,
            line = distinctSites.first().line,
            title = "Multiple Drivers on '$sig'",
            message = "Signal '$sig' is driven concurrently from ${distinctSites.size} procedural/continuous sources ($lines).",
            recommendation = "Consolidate all assignments to '$sig' into a single always block or continuous assign to prevent bus contention.",
          )
        }
      }
    }

    // DRC-CONN-02: Undriven output port
    val unknownBlackboxOutputs = mutableSetOf<String>()
    mod.instances.forEach { inst ->
      if (inst.moduleType !in gatePrimitives && modByName[inst.moduleType] == null) {
        inst.portConnections.values.forEach { collectLhsSignalTargetsToSet(it, unknownBlackboxOutputs) }
        inst.positionalConnections.forEach { collectLhsSignalTargetsToSet(it, unknownBlackboxOutputs) }
      }
    }

    mod.outputPorts.forEach { port ->
      if (port.name !in driversBySignal && port.name !in unknownBlackboxOutputs) {
        out += RtlDrcViolation(
          ruleId = "DRC-CONN-02",
          category = RtlDrcCategory.CONNECTIVITY,
          severity = RtlDrcSeverity.WARNING,
          moduleName = mod.name,
          signalOrBlock = port.name,
          line = mod.line,
          title = "Undriven Output Port",
          message = "Output port '${port.name}' has no continuous, procedural, or submodule driver.",
          recommendation = "Drive '${port.name}' with an 'assign', 'always' block, or submodule output connection.",
        )
      }
    }
  }

  private fun checkWidthMismatch(
    mod: ParsedVerilogModule,
    target: String,
    targetMsb: Int?,
    targetLsb: Int?,
    expr: VerilogExpr,
    line: Int,
    out: MutableList<RtlDrcViolation>,
  ) {
    val lhsWidth = if (targetMsb != null && targetLsb != null) {
      abs(targetMsb - targetLsb) + 1
    } else {
      mod.signalWidth(target)
    }
    if (expr is VerilogExpr.Const && expr.width <= 1) {
      val maxVal = if (lhsWidth >= 30) Int.MAX_VALUE else (1 shl lhsWidth) - 1
      if (expr.value in 0..maxVal) return
    }
    val rhsWidth = estimateExplicitWidth(expr, mod) ?: return
    if (lhsWidth != rhsWidth && rhsWidth > lhsWidth) {
      out += RtlDrcViolation(
        ruleId = "DRC-CONN-03",
        category = RtlDrcCategory.CONNECTIVITY,
        severity = RtlDrcSeverity.INFO,
        moduleName = mod.name,
        signalOrBlock = target,
        line = line,
        title = "Assignment Width Truncation",
        message = "Target '$target' ($lhsWidth-bit) is assigned a wider $rhsWidth-bit expression; upper ${rhsWidth - lhsWidth} bit(s) will be truncated.",
        recommendation = "Explicitly slice the RHS or widen '$target' to $rhsWidth bits to confirm truncation intent.",
      )
    }
  }

  private fun buildPassedChecks(
    modules: List<ParsedVerilogModule>,
    violations: List<RtlDrcViolation>,
  ): List<String> {
    val violatedRules = violations.map { it.ruleId }.toSet()
    val seqCount = modules.sumOf { m -> m.alwaysBlocks.count { it.isSequential } }
    val combCount = modules.sumOf { m -> m.alwaysBlocks.count { !it.isSequential } }
    val passed = mutableListOf<String>()

    if ("DRC-RST-01" !in violatedRules && "DRC-RST-02" !in violatedRules) {
      passed += if (seqCount > 0) {
        "Reset Hygiene: All $seqCount sequential block(s) define deterministic reset initialization."
      } else {
        "Reset Hygiene: Purely combinational design (0 sequential registers)."
      }
    }
    if ("DRC-LATCH-01" !in violatedRules && "DRC-LATCH-03" !in violatedRules) {
      passed += if (combCount > 0) {
        "Latch Check: All $combCount combinational procedural block(s) are latch-free across control branches."
      } else {
        "Latch Check: No combinational procedural latches inferred."
      }
    }
    if ("DRC-LOOP-01" !in violatedRules) {
      passed += "Cycle Check: Combinational logic graph is acyclic (0 zero-delay feedback loops)."
    }
    if ("DRC-SENS-01" !in violatedRules) {
      passed += "Sensitivity Check: All combinational sensitivity lists match read signals."
    }
    if ("DRC-CONN-01" !in violatedRules && "DRC-CONN-02" !in violatedRules) {
      passed += "Connectivity Check: Single-driver discipline and output drivers verified."
    }
    return passed
  }

  // =========================================================================
  // AST TRAVERSAL & EXPRESSION UTILITIES
  // =========================================================================

  private fun collectAssignedTargets(stmts: List<VerilogStmt>, out: MutableSet<String>) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> out += stmt.target
        is VerilogStmt.IfStmt -> {
          collectAssignedTargets(stmt.thenBranch, out)
          collectAssignedTargets(stmt.elseBranch, out)
        }
        is VerilogStmt.CaseStmt -> {
          stmt.branches.forEach { (_, body) -> collectAssignedTargets(body, out) }
          collectAssignedTargets(stmt.defaultBranch, out)
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }
  }

  private fun collectProceduralDriverTargets(
    stmts: List<VerilogStmt>,
    out: MutableMap<String, Pair<IntRange?, Int>>,
  ) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> {
          val slice = concreteSlice(stmt.targetMsb, stmt.targetLsb)
          val prev = out[stmt.target]
          if (prev == null) {
            out[stmt.target] = slice to stmt.line
          } else {
            val mergedSlice = if (prev.first == null || slice == null) null
            else minOf(prev.first!!.first, slice.first)..maxOf(prev.first!!.last, slice.last)
            out[stmt.target] = mergedSlice to minOf(prev.second, stmt.line)
          }
        }
        is VerilogStmt.IfStmt -> {
          collectProceduralDriverTargets(stmt.thenBranch, out)
          collectProceduralDriverTargets(stmt.elseBranch, out)
        }
        is VerilogStmt.CaseStmt -> {
          stmt.branches.forEach { (_, body) -> collectProceduralDriverTargets(body, out) }
          collectProceduralDriverTargets(stmt.defaultBranch, out)
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }
  }

  private fun collectAssignmentsMatching(
    stmts: List<VerilogStmt>,
    visitor: (VerilogStmt.Assign) -> Unit,
  ) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> visitor(stmt)
        is VerilogStmt.IfStmt -> {
          collectAssignmentsMatching(stmt.thenBranch, visitor)
          collectAssignmentsMatching(stmt.elseBranch, visitor)
        }
        is VerilogStmt.CaseStmt -> {
          stmt.branches.forEach { (_, body) -> collectAssignmentsMatching(body, visitor) }
          collectAssignmentsMatching(stmt.defaultBranch, visitor)
        }
        is VerilogStmt.Display, is VerilogStmt.AssertStmt -> Unit
      }
    }
  }

  private fun collectReadSignalsInStmts(stmts: List<VerilogStmt>, out: MutableSet<String>) {
    stmts.forEach { stmt ->
      when (stmt) {
        is VerilogStmt.Assign -> collectExprSignalNames(stmt.expr, out)
        is VerilogStmt.IfStmt -> {
          collectExprSignalNames(stmt.cond, out)
          collectReadSignalsInStmts(stmt.thenBranch, out)
          collectReadSignalsInStmts(stmt.elseBranch, out)
        }
        is VerilogStmt.CaseStmt -> {
          collectExprSignalNames(stmt.selector, out)
          stmt.branches.forEach { (labels, body) ->
            labels.forEach { collectExprSignalNames(it, out) }
            collectReadSignalsInStmts(body, out)
          }
          collectReadSignalsInStmts(stmt.defaultBranch, out)
        }
        is VerilogStmt.Display -> stmt.args.forEach { collectExprSignalNames(it, out) }
        is VerilogStmt.AssertStmt -> collectExprSignalNames(stmt.cond, out)
      }
    }
  }

  private fun collectExprSignalNames(expr: VerilogExpr, out: MutableSet<String>) {
    val deps = mutableListOf<Pair<String, IntRange?>>()
    collectExprSignalDeps(expr, deps)
    deps.forEach { out += it.first }
  }

  private fun collectExprSignalDeps(expr: VerilogExpr, out: MutableList<Pair<String, IntRange?>>) {
    when (expr) {
      is VerilogExpr.Const -> Unit
      is VerilogExpr.Signal -> out += expr.name to null
      is VerilogExpr.Slice -> out += expr.name to concreteSlice(expr.msb, expr.lsb)
      is VerilogExpr.Index -> {
        collectExprSignalDeps(expr.base, out)
        collectExprSignalDeps(expr.indexExpr, out)
      }
      is VerilogExpr.Concat -> expr.parts.forEach { collectExprSignalDeps(it, out) }
      is VerilogExpr.Replication -> collectExprSignalDeps(expr.inner, out)
      is VerilogExpr.Unary -> collectExprSignalDeps(expr.inner, out)
      is VerilogExpr.Binary -> {
        collectExprSignalDeps(expr.left, out)
        collectExprSignalDeps(expr.right, out)
      }
      is VerilogExpr.Ternary -> {
        collectExprSignalDeps(expr.cond, out)
        collectExprSignalDeps(expr.thenExpr, out)
        collectExprSignalDeps(expr.elseExpr, out)
      }
      is VerilogExpr.FuncCall -> expr.args.forEach { collectExprSignalDeps(it, out) }
    }
  }

  private fun collectLhsSignalTargets(expr: VerilogExpr, out: MutableList<Pair<String, IntRange?>>) {
    when (expr) {
      is VerilogExpr.Signal -> out += expr.name to null
      is VerilogExpr.Slice -> out += expr.name to concreteSlice(expr.msb, expr.lsb)
      is VerilogExpr.Index -> collectLhsSignalTargets(expr.base, out)
      is VerilogExpr.Concat -> expr.parts.forEach { collectLhsSignalTargets(it, out) }
      else -> Unit
    }
  }

  private fun collectLhsSignalTargetsToSet(expr: VerilogExpr, out: MutableSet<String>) {
    val list = mutableListOf<Pair<String, IntRange?>>()
    collectLhsSignalTargets(expr, list)
    list.forEach { out += it.first }
  }

  private fun concreteSlice(msb: Int?, lsb: Int?): IntRange? {
    if (msb == null || lsb == null) return null
    return minOf(msb, lsb)..maxOf(msb, lsb)
  }

  private fun rangesOverlap(a: IntRange, b: IntRange): Boolean =
    a.first <= b.last && b.first <= a.last

  private fun evalConstantExpr(expr: VerilogExpr): Int? = when (expr) {
    is VerilogExpr.Const -> expr.value
    is VerilogExpr.Unary -> {
      val v = evalConstantExpr(expr.inner) ?: return null
      when (expr.op) {
        "-" -> -v
        "+" -> v
        "~" -> v.inv()
        "!" -> if (v == 0) 1 else 0
        else -> null
      }
    }
    is VerilogExpr.Binary -> {
      val l = evalConstantExpr(expr.left) ?: return null
      val r = evalConstantExpr(expr.right) ?: return null
      when (expr.op) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "&" -> l and r
        "|" -> l or r
        "^" -> l xor r
        else -> null
      }
    }
    else -> null
  }

  private fun estimateExprWidth(expr: VerilogExpr, mod: ParsedVerilogModule): Int = when (expr) {
    is VerilogExpr.Const -> expr.width.coerceAtLeast(1)
    is VerilogExpr.Signal -> mod.signalWidth(expr.name)
    is VerilogExpr.Slice -> abs(expr.msb - expr.lsb) + 1
    is VerilogExpr.Index -> expr.width.coerceAtLeast(1)
    is VerilogExpr.Concat -> expr.parts.sumOf { estimateExprWidth(it, mod) }.coerceAtLeast(1)
    is VerilogExpr.Replication -> (expr.count * estimateExprWidth(expr.inner, mod)).coerceAtLeast(1)
    is VerilogExpr.Unary -> when (expr.op) {
      "!", "&", "~&", "|", "~|", "^", "~^", "^~" -> 1
      else -> estimateExprWidth(expr.inner, mod)
    }
    is VerilogExpr.Binary -> when (expr.op) {
      "==", "!=", "===", "!==", "<", "<=", ">", ">=", "&&", "||" -> 1
      else -> maxOf(estimateExprWidth(expr.left, mod), estimateExprWidth(expr.right, mod))
    }
    is VerilogExpr.Ternary -> maxOf(
      estimateExprWidth(expr.thenExpr, mod),
      estimateExprWidth(expr.elseExpr, mod),
    )
    is VerilogExpr.FuncCall -> 32
  }

  private fun estimateExplicitWidth(expr: VerilogExpr, mod: ParsedVerilogModule): Int? = when (expr) {
    is VerilogExpr.Const -> if (expr.width > 1) expr.width else null
    is VerilogExpr.Signal -> mod.signalWidth(expr.name)
    is VerilogExpr.Slice -> abs(expr.msb - expr.lsb) + 1
    is VerilogExpr.Index -> expr.width.coerceAtLeast(1)
    is VerilogExpr.Concat -> expr.parts.sumOf { estimateExprWidth(it, mod) }.coerceAtLeast(1)
    is VerilogExpr.Replication -> (expr.count * estimateExprWidth(expr.inner, mod)).coerceAtLeast(1)
    is VerilogExpr.Unary -> when (expr.op) {
      "!", "&", "~&", "|", "~|", "^", "~^", "^~" -> 1
      else -> estimateExplicitWidth(expr.inner, mod)
    }
    is VerilogExpr.Binary -> when (expr.op) {
      "==", "!=", "===", "!==", "<", "<=", ">", ">=", "&&", "||" -> 1
      else -> {
        val lw = estimateExplicitWidth(expr.left, mod)
        val rw = estimateExplicitWidth(expr.right, mod)
        when {
          lw != null && rw != null -> maxOf(lw, rw)
          else -> lw ?: rw
        }
      }
    }
    is VerilogExpr.Ternary -> {
      val tw = estimateExplicitWidth(expr.thenExpr, mod)
      val ew = estimateExplicitWidth(expr.elseExpr, mod)
      when {
        tw != null && ew != null -> maxOf(tw, ew)
        else -> tw ?: ew
      }
    }
    is VerilogExpr.FuncCall -> null
  }
}
