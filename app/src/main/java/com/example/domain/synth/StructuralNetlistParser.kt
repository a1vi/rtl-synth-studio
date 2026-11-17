package com.example.domain.synth

import java.util.Locale

enum class SchematicCellCategory(
  val badge: String,
  val isSequential: Boolean = false,
  val isPort: Boolean = false,
  val isModule: Boolean = false,
) {
  INPUT_PORT("IN", isPort = true),
  OUTPUT_PORT("OUT", isPort = true),
  MODULE("MOD", isModule = true),
  AND("AND"),
  OR("OR"),
  NOT("NOT"),
  NAND("NAND"),
  NOR("NOR"),
  XOR("XOR"),
  XNOR("XNOR"),
  MUX("MUX"),
  DFF("DFF", isSequential = true),
  REG("REG", isSequential = true),
  ADDER("ADD"),
  CMP("CMP"),
  MUL("MUL"),
  BUF("BUF"),
  CONST("CONST"),
  STD_CELL("CELL"),
}

data class SchematicPin(
  val pinName: String,
  val netName: String,
  val isOutput: Boolean,
  val bitWidth: Int = 1,
)

data class SchematicCell(
  val id: String,
  val instanceName: String,
  val cellType: String,
  val category: SchematicCellCategory,
  val gateType: GateType,
  val inputPins: List<SchematicPin>,
  val outputPins: List<SchematicPin>,
  val bitWidth: Int,
  val level: Int,
  val delayNs: Double,
  val arrivalNs: Double,
  val requiredNs: Double,
  val slackNs: Double,
  val areaUm2: Double,
  val powerUw: Double,
  val isCriticalPath: Boolean,
  val sourceLine: Int,
  val hierarchyPath: String = "",
  val targetSubmoduleScope: String? = null,
) {
  val primaryOutputNet: String
    get() = outputPins.firstOrNull()?.netName ?: instanceName
}

data class SchematicNet(
  val name: String,
  val width: Int,
  val driverCellId: String?,
  val driverPinName: String?,
  val loadPins: List<Pair<String, String>>, // (cellId, pinName)
  val isCritical: Boolean,
  val isClock: Boolean,
  val isReset: Boolean,
)

data class SchematicHierarchyNode(
  val scopePath: String, // "" for root top module, or "u_alu", "u_core/u_dec"
  val instanceName: String,
  val moduleType: String,
  val cellCount: Int,
  val seqCount: Int,
  val netCount: Int,
  val sourceLine: Int,
  val children: List<SchematicHierarchyNode>,
)

data class SchematicNetlistGraph(
  val topModuleName: String,
  val scopePath: String,
  val scopeTitle: String,
  val cells: List<SchematicCell>,
  val nets: List<SchematicNet>,
  val hierarchyRoot: SchematicHierarchyNode,
  val parsedPortCount: Int,
  val parsedCellCount: Int,
  val parsedWireCount: Int,
  val isCriticalPathAvailable: Boolean,
) {
  val cellById: Map<String, SchematicCell> by lazy { cells.associateBy { it.id } }
  val netByName: Map<String, SchematicNet> by lazy { nets.associateBy { it.name } }
}

sealed interface SchematicSearchHit {
  val label: String
  val subtitle: String

  data class CellHit(val cell: SchematicCell) : SchematicSearchHit {
    override val label: String get() = cell.instanceName
    override val subtitle: String get() = "${cell.cellType} · ${cell.category.badge} · ${cell.primaryOutputNet}"
  }

  data class NetHit(val net: SchematicNet) : SchematicSearchHit {
    override val label: String get() = net.name
    override val subtitle: String get() = "Net[${net.width}b] · ${net.loadPins.size} load(s)"
  }
}

object StructuralNetlistParser {

  internal data class ParsedPortDecl(
    val name: String,
    val direction: PortDirection,
    val width: Int,
  )

  internal data class ParsedInstDecl(
    val cellType: String,
    val instanceName: String,
    val namedPins: Map<String, String>,
    val positionalPins: List<String>,
  )

  fun buildSchematicGraph(
    report: SynthesisReport,
    parsedDesign: ParsedVerilogDesign? = null,
    scopePath: String = "",
  ): SchematicNetlistGraph {
    val parsedStructural = parseVerilogNetlistStructure(report.netlistVerilog)
    val reportNodeByInstance = report.nodes
      .filter { !it.gateType.isPort }
      .associateBy { it.instanceName }
    val reportPortByName = report.ports.associateBy { it.name }
    val reportNodeById = report.nodes.associateBy { it.id }
    val criticalNetNames = report.edges.filter { it.isCritical }.map { it.netName }.toSet()

    val allCells = mutableListOf<SchematicCell>()

    // 1. Primary Input / Inout Ports
    val effectivePorts = parsedStructural.ports.ifEmpty {
      report.ports.map { ParsedPortDecl(it.name, it.direction, it.width) }
    }
    effectivePorts.filter { it.direction != PortDirection.OUTPUT }.forEach { port ->
      val rNode = reportNodeById["in_${port.name}"]
      allCells += SchematicCell(
        id = rNode?.id ?: "in_${port.name}",
        instanceName = port.name,
        cellType = if (port.direction == PortDirection.INOUT) "INOUT" else "INPUT",
        category = SchematicCellCategory.INPUT_PORT,
        gateType = GateType.INPUT_PORT,
        inputPins = emptyList(),
        outputPins = listOf(SchematicPin(pinName = port.name, netName = port.name, isOutput = true, bitWidth = port.width)),
        bitWidth = port.width,
        level = 0,
        delayNs = 0.0,
        arrivalNs = rNode?.arrivalNs ?: 0.0,
        requiredNs = rNode?.requiredNs ?: report.clockPeriodNs,
        slackNs = rNode?.slackNs ?: report.clockPeriodNs,
        areaUm2 = 0.0,
        powerUw = 0.0,
        isCriticalPath = rNode?.isCriticalPath ?: false,
        sourceLine = reportPortByName[port.name]?.line ?: 1,
        hierarchyPath = "",
      )
    }

    // 2. Standard-Cell & Gate Instances parsed directly from the synthesized Verilog netlist
    if (parsedStructural.instances.isNotEmpty()) {
      parsedStructural.instances.forEachIndexed { idx, inst ->
        val matched = reportNodeByInstance[inst.instanceName]
        val gateType = matched?.gateType ?: classifyGateTypeFromCell(inst.cellType)
        val category = classifyCategory(inst.cellType, gateType, inst.namedPins)
        val width = matched?.bitWidth
          ?: inferInstanceWidth(inst, parsedStructural.wireWidths, reportPortByName)

        val (inPins, outPins) = extractPins(
          inst = inst,
          gateType = gateType,
          matchedNode = matched,
          wireWidths = parsedStructural.wireWidths,
          portWidths = reportPortByName,
          defaultWidth = width,
        )

        val hierPath = matched?.hierarchyPath?.takeIf { it.isNotBlank() }
          ?: inferHierarchyPathFromInstanceName(inst.instanceName, parsedDesign)

        allCells += SchematicCell(
          id = matched?.id ?: "parsed_${idx}_${inst.instanceName}",
          instanceName = inst.instanceName,
          cellType = inst.cellType,
          category = category,
          gateType = gateType,
          inputPins = inPins,
          outputPins = outPins,
          bitWidth = width,
          level = matched?.level ?: 1,
          delayNs = matched?.delayNs ?: 0.0,
          arrivalNs = matched?.arrivalNs ?: 0.0,
          requiredNs = matched?.requiredNs ?: report.clockPeriodNs,
          slackNs = matched?.slackNs ?: report.clockPeriodNs,
          areaUm2 = matched?.areaUm2 ?: 0.0,
          powerUw = matched?.powerUw ?: 0.0,
          isCriticalPath = matched?.isCriticalPath ?: false,
          sourceLine = matched?.sourceLine ?: 0,
          hierarchyPath = hierPath,
        )
      }
    } else {
      // Fallback to SynthesisReport nodes when netlist text is empty/custom
      report.nodes.filter { !it.gateType.isPort }.forEach { node ->
        val category = classifyCategory(node.cellName, node.gateType, emptyMap())
        val (inPins, outPins) = pinsFromGateNode(node)
        allCells += SchematicCell(
          id = node.id,
          instanceName = node.instanceName,
          cellType = node.cellName,
          category = category,
          gateType = node.gateType,
          inputPins = inPins,
          outputPins = outPins,
          bitWidth = node.bitWidth,
          level = node.level,
          delayNs = node.delayNs,
          arrivalNs = node.arrivalNs,
          requiredNs = node.requiredNs,
          slackNs = node.slackNs,
          areaUm2 = node.areaUm2,
          powerUw = node.powerUw,
          isCriticalPath = node.isCriticalPath,
          sourceLine = node.sourceLine,
          hierarchyPath = node.hierarchyPath,
        )
      }
    }

    // Also represent `assign out = net;` tail connections as output-driving BUF connections if not already driven
    val drivenNets = allCells.flatMap { c -> c.outputPins.map { it.netName } }.toSet()
    parsedStructural.continuousAssigns.forEachIndexed { idx, (lhs, rhs) ->
      if (lhs !in drivenNets && rhs.isNotBlank()) {
        val w = reportPortByName[lhs]?.width ?: parsedStructural.wireWidths[lhs] ?: 1
        allCells += SchematicCell(
          id = "assign_${idx}_$lhs",
          instanceName = "u_assign_$lhs",
          cellType = "BUF",
          category = SchematicCellCategory.BUF,
          gateType = GateType.BUF,
          inputPins = listOf(SchematicPin("A", rhs, isOutput = false, bitWidth = w)),
          outputPins = listOf(SchematicPin("Y", lhs, isOutput = true, bitWidth = w)),
          bitWidth = w,
          level = 1,
          delayNs = 0.0,
          arrivalNs = 0.0,
          requiredNs = report.clockPeriodNs,
          slackNs = report.clockPeriodNs,
          areaUm2 = 0.0,
          powerUw = 0.0,
          isCriticalPath = false,
          sourceLine = 0,
        )
      }
    }

    // 3. Primary Output Ports
    val maxLevel = (allCells.maxOfOrNull { it.level } ?: 1).coerceAtLeast(1)
    effectivePorts.filter { it.direction == PortDirection.OUTPUT }.forEach { port ->
      val rNode = reportNodeById["out_${port.name}"]
      allCells += SchematicCell(
        id = rNode?.id ?: "out_${port.name}",
        instanceName = port.name,
        cellType = "OUTPUT",
        category = SchematicCellCategory.OUTPUT_PORT,
        gateType = GateType.OUTPUT_PORT,
        inputPins = listOf(SchematicPin(pinName = port.name, netName = port.name, isOutput = false, bitWidth = port.width)),
        outputPins = emptyList(),
        bitWidth = port.width,
        level = rNode?.level ?: maxLevel,
        delayNs = 0.0,
        arrivalNs = rNode?.arrivalNs ?: 0.0,
        requiredNs = rNode?.requiredNs ?: report.clockPeriodNs,
        slackNs = rNode?.slackNs ?: report.clockPeriodNs,
        areaUm2 = 0.0,
        powerUw = 0.0,
        isCriticalPath = rNode?.isCriticalPath ?: false,
        sourceLine = reportPortByName[port.name]?.line ?: 1,
        hierarchyPath = "",
      )
    }

    // Build Hierarchy Tree from ParsedVerilogDesign + synthesized cells
    val hierarchyRoot = buildHierarchyTree(
      topModuleName = report.moduleName,
      parsedDesign = parsedDesign,
      allCells = allCells,
    )

    // Filter / slice to active scope if user navigated into a submodule
    val normalizedScope = scopePath.trim().trim('/')
    val scopedCells = if (normalizedScope.isEmpty()) {
      allCells
    } else {
      sliceCellsForScope(
        scopePath = normalizedScope,
        allCells = allCells,
        parsedDesign = parsedDesign,
        clockPeriodNs = report.clockPeriodNs,
      )
    }

    val recomputedCells = recomputeLevelsIfNeeded(scopedCells)
    val nets = buildNets(recomputedCells, criticalNetNames)

    val scopeTitle = if (normalizedScope.isEmpty()) {
      report.moduleName
    } else {
      val hierNode = findHierarchyNode(hierarchyRoot, normalizedScope)
      hierNode?.let { "${it.instanceName} (${it.moduleType})" } ?: normalizedScope
    }

    return SchematicNetlistGraph(
      topModuleName = report.moduleName,
      scopePath = normalizedScope,
      scopeTitle = scopeTitle,
      cells = recomputedCells,
      nets = nets,
      hierarchyRoot = hierarchyRoot,
      parsedPortCount = effectivePorts.size,
      parsedCellCount = parsedStructural.instances.size.takeIf { it > 0 }
        ?: allCells.count { !it.category.isPort },
      parsedWireCount = parsedStructural.wireWidths.size.takeIf { it > 0 } ?: nets.size,
      isCriticalPathAvailable = report.criticalPath.isNotEmpty(),
    )
  }

  fun searchCellsAndNets(
    graph: SchematicNetlistGraph,
    query: String,
    limit: Int = 24,
  ): List<SchematicSearchHit> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return emptyList()
    val cellHits = graph.cells.filter { c ->
      c.instanceName.lowercase(Locale.ROOT).contains(q) ||
        c.cellType.lowercase(Locale.ROOT).contains(q) ||
        c.category.badge.lowercase(Locale.ROOT).contains(q) ||
        c.primaryOutputNet.lowercase(Locale.ROOT).contains(q)
    }.take(limit).map { SchematicSearchHit.CellHit(it) }

    val netHits = graph.nets.filter { n ->
      n.name.lowercase(Locale.ROOT).contains(q)
    }.take((limit - cellHits.size).coerceAtLeast(4)).map { SchematicSearchHit.NetHit(it) }

    return (cellHits + netHits).take(limit)
  }

  private fun sliceCellsForScope(
    scopePath: String,
    allCells: List<SchematicCell>,
    parsedDesign: ParsedVerilogDesign?,
    clockPeriodNs: Double,
  ): List<SchematicCell> {
    val matchingInternal = allCells.filter { cell ->
      !cell.category.isPort &&
        (cell.hierarchyPath == scopePath || cell.hierarchyPath.startsWith("$scopePath/"))
    }
    if (matchingInternal.isEmpty()) {
      // Fallback: synthesize submodule standalone if parsedDesign has its AST module
      val targetModuleName = scopePath.substringAfterLast('/')
      val modAst = parsedDesign?.modules?.firstOrNull {
        it.name == targetModuleName ||
          parsedDesign.topModule.instances.any { inst -> inst.instanceName == targetModuleName && inst.moduleType == it.name }
      }
      if (modAst != null) {
        return allCells.filter { !it.category.isPort && it.instanceName.contains(scopePath, ignoreCase = true) }
          .ifEmpty { allCells }
      }
      return allCells
    }

    val drivenInside = matchingInternal.flatMap { c -> c.outputPins.map { it.netName } }.toSet()
    val usedInside = matchingInternal.flatMap { c -> c.inputPins.map { it.netName } }.toSet()

    // Also include outputs consumed by parent/external cells
    val externalConsumedNets = allCells
      .filter { it !in matchingInternal }
      .flatMap { c -> c.inputPins.map { it.netName } }
      .toSet()

    val boundaryInNets = (usedInside - drivenInside).filter { !it.startsWith("1'b") && !it.startsWith("_const_") }.distinct()
    val boundaryOutNets = (drivenInside intersect externalConsumedNets).distinct()
      .ifEmpty {
        matchingInternal
          .map { it.primaryOutputNet }
          .filter { net -> matchingInternal.none { other -> other.inputPins.any { it.netName == net } } }
          .distinct()
      }

    val out = mutableListOf<SchematicCell>()
    boundaryInNets.forEach { net ->
      val w = matchingInternal.flatMap { it.inputPins }.firstOrNull { it.netName == net }?.bitWidth ?: 1
      out += SchematicCell(
        id = "scope_in_$net",
        instanceName = net,
        cellType = "INPUT",
        category = SchematicCellCategory.INPUT_PORT,
        gateType = GateType.INPUT_PORT,
        inputPins = emptyList(),
        outputPins = listOf(SchematicPin(net, net, isOutput = true, bitWidth = w)),
        bitWidth = w,
        level = 0,
        delayNs = 0.0,
        arrivalNs = 0.0,
        requiredNs = clockPeriodNs,
        slackNs = clockPeriodNs,
        areaUm2 = 0.0,
        powerUw = 0.0,
        isCriticalPath = false,
        sourceLine = 0,
        hierarchyPath = scopePath,
      )
    }
    out += matchingInternal
    val maxLvl = (matchingInternal.maxOfOrNull { it.level } ?: 1) + 1
    boundaryOutNets.forEach { net ->
      val w = matchingInternal.flatMap { it.outputPins }.firstOrNull { it.netName == net }?.bitWidth ?: 1
      out += SchematicCell(
        id = "scope_out_$net",
        instanceName = net,
        cellType = "OUTPUT",
        category = SchematicCellCategory.OUTPUT_PORT,
        gateType = GateType.OUTPUT_PORT,
        inputPins = listOf(SchematicPin(net, net, isOutput = false, bitWidth = w)),
        outputPins = emptyList(),
        bitWidth = w,
        level = maxLvl,
        delayNs = 0.0,
        arrivalNs = 0.0,
        requiredNs = clockPeriodNs,
        slackNs = clockPeriodNs,
        areaUm2 = 0.0,
        powerUw = 0.0,
        isCriticalPath = false,
        sourceLine = 0,
        hierarchyPath = scopePath,
      )
    }
    return out
  }

  private fun recomputeLevelsIfNeeded(cells: List<SchematicCell>): List<SchematicCell> {
    if (cells.isEmpty()) return cells
    val driverByNet = mutableMapOf<String, SchematicCell>()
    cells.filter { it.category != SchematicCellCategory.OUTPUT_PORT }.forEach { cell ->
      cell.outputPins.forEach { p -> driverByNet[p.netName] = cell }
    }
    val cellMap = cells.associateBy { it.id }
    val levelCache = mutableMapOf<String, Int>()
    val visiting = mutableSetOf<String>()

    fun levelOf(cell: SchematicCell): Int {
      levelCache[cell.id]?.let { return it }
      if (cell.category == SchematicCellCategory.INPUT_PORT || cell.category.isSequential) {
        levelCache[cell.id] = 0
        return 0
      }
      if (!visiting.add(cell.id)) return cell.level.coerceAtLeast(1)
      val maxDriverLvl = cell.inputPins.mapNotNull { pin ->
        val drv = driverByNet[pin.netName]
        if (drv != null && drv.id != cell.id && cellMap.containsKey(drv.id)) levelOf(drv) else null
      }.maxOrNull() ?: 0
      visiting.remove(cell.id)
      val computed = maxDriverLvl + 1
      levelCache[cell.id] = computed
      return computed
    }

    cells.filter { it.category != SchematicCellCategory.OUTPUT_PORT }.forEach { levelOf(it) }
    val maxCombLevel = (levelCache.values.maxOrNull() ?: 1).coerceAtLeast(1)

    return cells.map { cell ->
      val lvl = if (cell.category == SchematicCellCategory.OUTPUT_PORT) {
        maxCombLevel
      } else {
        levelCache[cell.id] ?: cell.level
      }
      cell.copy(level = lvl)
    }
  }

  private fun buildNets(
    cells: List<SchematicCell>,
    criticalNets: Set<String>,
  ): List<SchematicNet> {
    val driverByNet = mutableMapOf<String, Pair<String, String>>()
    cells.filter { it.category != SchematicCellCategory.OUTPUT_PORT }.forEach { cell ->
      cell.outputPins.forEach { pin ->
        driverByNet[pin.netName] = cell.id to pin.pinName
      }
    }
    val loadsByNet = linkedMapOf<String, MutableList<Pair<String, String>>>()
    val widthByNet = mutableMapOf<String, Int>()
    val critByNet = mutableMapOf<String, Boolean>()

    cells.forEach { cell ->
      cell.outputPins.forEach { pin ->
        widthByNet[pin.netName] = maxOf(widthByNet[pin.netName] ?: 1, pin.bitWidth)
        loadsByNet.putIfAbsent(pin.netName, mutableListOf())
      }
      cell.inputPins.forEach { pin ->
        widthByNet[pin.netName] = maxOf(widthByNet[pin.netName] ?: 1, pin.bitWidth)
        loadsByNet.getOrPut(pin.netName) { mutableListOf() }.add(cell.id to pin.pinName)
        if (cell.isCriticalPath) {
          val drvId = driverByNet[pin.netName]?.first
          val drvCritical = drvId != null && cells.firstOrNull { it.id == drvId }?.isCriticalPath == true
          if (drvCritical) critByNet[pin.netName] = true
        }
      }
    }

    return loadsByNet.entries.map { (netName, loads) ->
      val drv = driverByNet[netName]
      val lower = netName.lowercase(Locale.ROOT)
      SchematicNet(
        name = netName,
        width = widthByNet[netName] ?: 1,
        driverCellId = drv?.first,
        driverPinName = drv?.second,
        loadPins = loads.toList(),
        isCritical = (netName in criticalNets) || (critByNet[netName] == true),
        isClock = lower == "clk" || lower.endsWith("_clk") || lower.startsWith("clk_"),
        isReset = lower == "rst" || lower == "rst_n" || lower == "reset" || lower.endsWith("_rst"),
      )
    }
  }

  private fun buildHierarchyTree(
    topModuleName: String,
    parsedDesign: ParsedVerilogDesign?,
    allCells: List<SchematicCell>,
  ): SchematicHierarchyNode {
    val nonPortCells = allCells.filter { !it.category.isPort }
    val moduleByName = parsedDesign?.modules?.associateBy { it.name }.orEmpty()

    fun buildFromAst(mod: ParsedVerilogModule, scopePath: String, instName: String, visited: Set<String>): SchematicHierarchyNode {
      val cellsInSubtree = nonPortCells.filter {
        scopePath.isEmpty() || it.hierarchyPath == scopePath || it.hierarchyPath.startsWith("$scopePath/")
      }
      val distinctNets = cellsInSubtree.flatMap { c ->
        c.inputPins.map { it.netName } + c.outputPins.map { it.netName }
      }.toSet()

      val childNodes = mod.instances.mapNotNull { inst ->
        val childMod = moduleByName[inst.moduleType]
        val childScope = if (scopePath.isEmpty()) inst.instanceName else "$scopePath/${inst.instanceName}"
        if (childMod != null && childMod.name !in visited) {
          buildFromAst(childMod, childScope, inst.instanceName, visited + childMod.name)
        } else {
          val subCells = nonPortCells.filter {
            it.hierarchyPath == childScope || it.hierarchyPath.startsWith("$childScope/")
          }
          if (subCells.isNotEmpty() || childMod != null) {
            SchematicHierarchyNode(
              scopePath = childScope,
              instanceName = inst.instanceName,
              moduleType = inst.moduleType,
              cellCount = subCells.size,
              seqCount = subCells.count { it.category.isSequential },
              netCount = subCells.flatMap { it.inputPins.map { p -> p.netName } + it.outputPins.map { p -> p.netName } }.toSet().size,
              sourceLine = inst.line,
              children = emptyList(),
            )
          } else {
            null
          }
        }
      }

      return SchematicHierarchyNode(
        scopePath = scopePath,
        instanceName = instName,
        moduleType = mod.name,
        cellCount = cellsInSubtree.size,
        seqCount = cellsInSubtree.count { it.category.isSequential },
        netCount = distinctNets.size,
        sourceLine = mod.line,
        children = childNodes,
      )
    }

    val topAst = parsedDesign?.topModule
    if (topAst != null) {
      return buildFromAst(topAst, "", topAst.name, setOf(topAst.name))
    }

    // Fallback: build hierarchy purely from synthesized cells' hierarchyPath
    val distinctHierPaths = nonPortCells.map { it.hierarchyPath }.filter { it.isNotBlank() }.distinct().sorted()
    val childHierNodes = distinctHierPaths.filter { '/' !in it }.map { path ->
      val subCells = nonPortCells.filter { it.hierarchyPath == path || it.hierarchyPath.startsWith("$path/") }
      SchematicHierarchyNode(
        scopePath = path,
        instanceName = path,
        moduleType = path,
        cellCount = subCells.size,
        seqCount = subCells.count { it.category.isSequential },
        netCount = subCells.flatMap { it.inputPins.map { p -> p.netName } + it.outputPins.map { p -> p.netName } }.toSet().size,
        sourceLine = subCells.firstOrNull()?.sourceLine ?: 1,
        children = emptyList(),
      )
    }
    return SchematicHierarchyNode(
      scopePath = "",
      instanceName = topModuleName,
      moduleType = topModuleName,
      cellCount = nonPortCells.size,
      seqCount = nonPortCells.count { it.category.isSequential },
      netCount = nonPortCells.flatMap { it.inputPins.map { p -> p.netName } + it.outputPins.map { p -> p.netName } }.toSet().size,
      sourceLine = 1,
      children = childHierNodes,
    )
  }

  private fun findHierarchyNode(node: SchematicHierarchyNode, targetScope: String): SchematicHierarchyNode? {
    if (node.scopePath == targetScope) return node
    node.children.forEach { child ->
      findHierarchyNode(child, targetScope)?.let { return it }
    }
    return null
  }

  private fun inferHierarchyPathFromInstanceName(
    instName: String,
    parsedDesign: ParsedVerilogDesign?,
  ): String {
    val topInstances = parsedDesign?.topModule?.instances.orEmpty()
    for (inst in topInstances) {
      if (instName.startsWith("${inst.instanceName}_")) {
        return inst.instanceName
      }
    }
    return ""
  }

  // ==================== VERILOG NETLIST LEXER / PARSER ====================

  internal data class ParsedVerilogNetlist(
    val moduleName: String,
    val ports: List<ParsedPortDecl>,
    val wireWidths: Map<String, Int>,
    val instances: List<ParsedInstDecl>,
    val continuousAssigns: List<Pair<String, String>>,
  )

  internal fun parseVerilogNetlistStructure(verilog: String): ParsedVerilogNetlist {
    if (verilog.isBlank()) {
      return ParsedVerilogNetlist("", emptyList(), emptyMap(), emptyList(), emptyList())
    }
    val cleaned = stripCommentsAndDirectives(verilog)
    val moduleMatch = Regex("""\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)\s*(?:\(([^)]*)\))?\s*;""").find(cleaned)
    val moduleName = moduleMatch?.groupValues?.get(1).orEmpty()
    val headerPortsText = moduleMatch?.groupValues?.getOrNull(2).orEmpty()

    val ports = linkedMapOf<String, ParsedPortDecl>()
    val wireWidths = linkedMapOf<String, Int>()
    val instances = mutableListOf<ParsedInstDecl>()
    val assigns = mutableListOf<Pair<String, String>>()

    // Parse ANSI ports in module header if present
    if (headerPortsText.contains(Regex("""\b(input|output|inout)\b"""))) {
      parsePortDeclarationList(headerPortsText, ports)
    }

    val bodyStart = moduleMatch?.range?.last?.plus(1) ?: 0
    val endModuleIdx = cleaned.indexOf("endmodule", startIndex = bodyStart).let { if (it < 0) cleaned.length else it }
    val body = cleaned.substring(bodyStart.coerceAtMost(endModuleIdx), endModuleIdx)

    val statements = splitSemicolonStatements(body)
    for (stmt in statements) {
      val trimmed = stmt.trim()
      if (trimmed.isEmpty()) continue
      when {
        trimmed.startsWith("input ") || trimmed.startsWith("output ") || trimmed.startsWith("inout ") ||
          trimmed.startsWith("input[") || trimmed.startsWith("output[") || trimmed.startsWith("inout[") -> {
          parsePortDeclarationList(trimmed, ports)
        }
        trimmed.startsWith("wire ") || trimmed.startsWith("wire[") ||
          trimmed.startsWith("reg ") || trimmed.startsWith("reg[") ||
          trimmed.startsWith("logic ") || trimmed.startsWith("logic[") -> {
          parseWireDeclaration(trimmed, wireWidths)
        }
        trimmed.startsWith("assign ") -> {
          val after = trimmed.removePrefix("assign").trim()
          val eqIdx = after.indexOf('=')
          if (eqIdx > 0) {
            val lhs = after.substring(0, eqIdx).trim().substringBefore('[')
            val rhs = after.substring(eqIdx + 1).trim()
            if (lhs.isNotEmpty() && rhs.isNotEmpty()) {
              assigns += lhs to rhs
            }
          }
        }
        else -> {
          parseInstanceStatement(trimmed)?.let { instances += it }
        }
      }
    }

    return ParsedVerilogNetlist(
      moduleName = moduleName,
      ports = ports.values.toList(),
      wireWidths = wireWidths,
      instances = instances,
      continuousAssigns = assigns,
    )
  }

  private fun stripCommentsAndDirectives(text: String): String {
    val withoutBlock = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
    return withoutBlock.lines().joinToString("\n") { line ->
      val noLineComment = line.substringBefore("//")
      if (noLineComment.trimStart().startsWith("`")) "" else noLineComment
    }
  }

  private fun splitSemicolonStatements(body: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var parenDepth = 0
    for (ch in body) {
      when (ch) {
        '(' -> {
          parenDepth++
          sb.append(ch)
        }
        ')' -> {
          if (parenDepth > 0) parenDepth--
          sb.append(ch)
        }
        ';' -> {
          if (parenDepth == 0) {
            result += sb.toString()
            sb.clear()
          } else {
            sb.append(ch)
          }
        }
        else -> sb.append(ch)
      }
    }
    if (sb.isNotBlank()) result += sb.toString()
    return result
  }

  private fun parsePortDeclarationList(text: String, out: MutableMap<String, ParsedPortDecl>) {
    var activeDir: PortDirection? = null
    var activeWidth = 1
    val chunks = text.split(',')
    for (chunk in chunks) {
      var work = chunk.trim()
      if (work.isEmpty()) continue
      val dirMatch = Regex("""^(input|output|inout)\b""").find(work)
      if (dirMatch != null) {
        activeDir = when (dirMatch.groupValues[1]) {
          "input" -> PortDirection.INPUT
          "output" -> PortDirection.OUTPUT
          else -> PortDirection.INOUT
        }
        work = work.removeRange(dirMatch.range).trim()
        work = work.replace(Regex("""^(wire|reg|logic|signed)\b\s*"""), "").trim()
        work = work.replace(Regex("""^(wire|reg|logic|signed)\b\s*"""), "").trim()
        val rangeMatch = Regex("""^\[\s*(\d+)\s*:\s*(\d+)\s*]""").find(work)
        if (rangeMatch != null) {
          val msb = rangeMatch.groupValues[1].toIntOrNull() ?: 0
          val lsb = rangeMatch.groupValues[2].toIntOrNull() ?: 0
          activeWidth = kotlin.math.abs(msb - lsb) + 1
          work = work.removeRange(rangeMatch.range).trim()
        } else {
          activeWidth = 1
        }
      }
      val name = Regex("""([A-Za-z_][A-Za-z0-9_$]*)\s*$""").find(work)?.groupValues?.get(1)
      if (name != null && activeDir != null) {
        out[name] = ParsedPortDecl(name = name, direction = activeDir, width = activeWidth)
      }
    }
  }

  private fun parseWireDeclaration(stmt: String, out: MutableMap<String, Int>) {
    var work = stmt.replace(Regex("""^(wire|reg|logic|signed)\b\s*"""), "").trim()
    work = work.replace(Regex("""^(wire|reg|logic|signed)\b\s*"""), "").trim()
    var width = 1
    val rangeMatch = Regex("""^\[\s*(\d+)\s*:\s*(\d+)\s*]""").find(work)
    if (rangeMatch != null) {
      val msb = rangeMatch.groupValues[1].toIntOrNull() ?: 0
      val lsb = rangeMatch.groupValues[2].toIntOrNull() ?: 0
      width = kotlin.math.abs(msb - lsb) + 1
      work = work.removeRange(rangeMatch.range).trim()
    }
    work.split(',').forEach { part ->
      val name = Regex("""^([A-Za-z_][A-Za-z0-9_$]*)""").find(part.trim())?.groupValues?.get(1)
      if (name != null) out[name] = width
    }
  }

  private fun parseInstanceStatement(stmt: String): ParsedInstDecl? {
    // Pattern: CELL_TYPE [#(...)] inst_name ( ... )
    val headerRegex = Regex("""^([A-Za-z_$\\][A-Za-z0-9_$.\\]*)\s+(?:#\s*\([^)]*\)\s*)?([A-Za-z_][A-Za-z0-9_$]*)\s*\(([\s\S]*)\)\s*$""")
    val m = headerRegex.matchEntire(stmt.trim()) ?: return null
    val cellType = m.groupValues[1].trim()
    val instName = m.groupValues[2].trim()
    val pinBody = m.groupValues[3].trim()

    val namedPins = linkedMapOf<String, String>()
    val positionalPins = mutableListOf<String>()

    val pinParts = splitTopLevelCommas(pinBody)
    for (part in pinParts) {
      val trimmed = part.trim()
      if (trimmed.isEmpty()) continue
      val namedMatch = Regex("""^\.([A-Za-z_][A-Za-z0-9_$]*)\s*\(\s*([\s\S]*?)\s*\)$""").matchEntire(trimmed)
      if (namedMatch != null) {
        val pinName = namedMatch.groupValues[1]
        val netExpr = cleanNetExpr(namedMatch.groupValues[2])
        namedPins[pinName] = netExpr
      } else {
        positionalPins += cleanNetExpr(trimmed)
      }
    }

    return ParsedInstDecl(
      cellType = cellType,
      instanceName = instName,
      namedPins = namedPins,
      positionalPins = positionalPins,
    )
  }

  private fun splitTopLevelCommas(text: String): List<String> {
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    for (ch in text) {
      when (ch) {
        '(', '{', '[' -> {
          depth++
          sb.append(ch)
        }
        ')', '}', ']' -> {
          if (depth > 0) depth--
          sb.append(ch)
        }
        ',' -> {
          if (depth == 0) {
            parts += sb.toString()
            sb.clear()
          } else {
            sb.append(ch)
          }
        }
        else -> sb.append(ch)
      }
    }
    if (sb.isNotBlank()) parts += sb.toString()
    return parts
  }

  private fun cleanNetExpr(expr: String): String {
    val t = expr.trim()
    if (t.isEmpty()) return ""
    // Preserve simple identifier or strip bit/range slice only when matching whole net
    return t.replace(Regex("""\s+"""), "")
  }

  internal fun classifyGateTypeFromCell(cellType: String): GateType {
    val u = cellType.uppercase(Locale.ROOT)
    return when {
      "SDFFR" in u || "SDFFSQ" in u -> GateType.SDFFR
      "SDFF" in u -> GateType.SDFF
      "DFFR" in u || "FDCE" in u || "DFRTP" in u -> GateType.DFFR
      "DFF" in u || "FDRE" in u || "DFXTP" in u || "DFFQ" in u -> GateType.DFF
      "DLAT" in u || "DLLATCH" in u || "DLL_" in u || "LDCE" in u || "LOCKUP" in u -> GateType.LOCKUP_LATCH
      "XNOR" in u -> GateType.XNOR2
      "XOR" in u -> GateType.XOR2
      "NAND" in u -> GateType.NAND2
      "NOR" in u -> GateType.NOR2
      "AND" in u -> GateType.AND2
      "OR" in u -> GateType.OR2
      "INV" in u || "NOT" in u -> GateType.INV
      "MUX" in u -> GateType.MUX2
      "ADD" in u || "CARRY4" in u -> GateType.ADD_CELL
      "SUB" in u -> GateType.SUB_CELL
      "CMP" in u || "COMP" in u -> GateType.CMP_CELL
      "MUL" in u || "DSP48" in u -> GateType.MUL_CELL
      "TIE" in u || "CONB" in u || "LOGIC0" in u || "GND" in u -> GateType.CONST
      "LUT4" in u -> GateType.LUT4
      "LUT6" in u -> GateType.LUT6
      "BUF" in u -> GateType.BUF
      else -> GateType.BUF
    }
  }

  private fun classifyCategory(
    cellType: String,
    gateType: GateType,
    namedPins: Map<String, String>,
  ): SchematicCellCategory {
    val u = cellType.uppercase(Locale.ROOT)
    return when {
      gateType == GateType.INPUT_PORT -> SchematicCellCategory.INPUT_PORT
      gateType == GateType.OUTPUT_PORT -> SchematicCellCategory.OUTPUT_PORT
      "XNOR" in u || gateType == GateType.XNOR2 -> SchematicCellCategory.XNOR
      "XOR" in u || gateType == GateType.XOR2 -> SchematicCellCategory.XOR
      "NAND" in u || gateType == GateType.NAND2 -> SchematicCellCategory.NAND
      "NOR" in u || gateType == GateType.NOR2 -> SchematicCellCategory.NOR
      "AND" in u || gateType == GateType.AND2 -> SchematicCellCategory.AND
      "OR" in u || gateType == GateType.OR2 -> SchematicCellCategory.OR
      "INV" in u || "NOT" in u || gateType == GateType.INV -> SchematicCellCategory.NOT
      "MUX" in u || gateType == GateType.MUX2 -> SchematicCellCategory.MUX
      "SDFF" in u || "DFF" in u || "FD" in u || gateType == GateType.DFF ||
        gateType == GateType.DFFR || gateType == GateType.SDFF || gateType == GateType.SDFFR -> SchematicCellCategory.DFF
      "DLAT" in u || "LATCH" in u || "LDCE" in u || "DLL" in u || gateType == GateType.LOCKUP_LATCH -> SchematicCellCategory.REG
      "ADD" in u || "CARRY" in u || gateType == GateType.ADD_CELL || gateType == GateType.SUB_CELL -> SchematicCellCategory.ADDER
      "CMP" in u || "COMP" in u || gateType == GateType.CMP_CELL -> SchematicCellCategory.CMP
      "MUL" in u || "DSP" in u || gateType == GateType.MUL_CELL -> SchematicCellCategory.MUL
      "BUF" in u || gateType == GateType.BUF -> SchematicCellCategory.BUF
      "TIE" in u || "CONB" in u || "LOGIC" in u || "GND" in u || gateType == GateType.CONST -> SchematicCellCategory.CONST
      namedPins.size > 3 -> SchematicCellCategory.STD_CELL
      else -> SchematicCellCategory.STD_CELL
    }
  }

  private fun inferInstanceWidth(
    inst: ParsedInstDecl,
    wireWidths: Map<String, Int>,
    portWidths: Map<String, RtlPort>,
  ): Int {
    val nets = inst.namedPins.values + inst.positionalPins
    return nets.mapNotNull { n ->
      val base = n.substringBefore('[')
      wireWidths[base] ?: portWidths[base]?.width
    }.maxOrNull() ?: 1
  }

  private fun extractPins(
    inst: ParsedInstDecl,
    gateType: GateType,
    matchedNode: GateNode?,
    wireWidths: Map<String, Int>,
    portWidths: Map<String, RtlPort>,
    defaultWidth: Int,
  ): Pair<List<SchematicPin>, List<SchematicPin>> {
    fun widthOf(net: String): Int {
      val base = net.substringBefore('[')
      return wireWidths[base] ?: portWidths[base]?.width ?: defaultWidth
    }

    if (inst.namedPins.isNotEmpty()) {
      val outPinNames = setOf("Y", "Q", "QN", "CO", "COUT", "O", "OUT", "Z", "ZN", "S_OUT")
      val orderedInOrder = listOf("S", "A", "B", "C", "D", "CLK", "CP", "GN", "G", "RST", "RN", "RESET", "SE", "SI", "SCE", "SCD", "EN")
      val outPins = mutableListOf<SchematicPin>()
      val inPins = mutableListOf<SchematicPin>()

      inst.namedPins.forEach { (pinName, net) ->
        val upper = pinName.uppercase(Locale.ROOT)
        if (upper in outPinNames) {
          outPins += SchematicPin(pinName, net, isOutput = true, bitWidth = widthOf(net))
        } else {
          inPins += SchematicPin(pinName, net, isOutput = false, bitWidth = widthOf(net))
        }
      }

      val sortedInputs = inPins.sortedBy { p ->
        val idx = orderedInOrder.indexOf(p.pinName.uppercase(Locale.ROOT))
        if (idx >= 0) idx else 100
      }

      if (outPins.isEmpty() && matchedNode != null) {
        outPins += SchematicPin(
          pinName = if (gateType.isSequential) "Q" else "Y",
          netName = matchedNode.outputNet,
          isOutput = true,
          bitWidth = matchedNode.bitWidth,
        )
      }
      return sortedInputs to outPins
    }

    if (inst.positionalPins.isNotEmpty()) {
      val outNet = inst.positionalPins.first()
      val inPins = inst.positionalPins.drop(1).mapIndexed { idx, net ->
        SchematicPin("I$idx", net, isOutput = false, bitWidth = widthOf(net))
      }
      val outPins = listOf(SchematicPin("Y", outNet, isOutput = true, bitWidth = widthOf(outNet)))
      return inPins to outPins
    }

    return matchedNode?.let { pinsFromGateNode(it) } ?: (emptyList<SchematicPin>() to emptyList())
  }

  private fun pinsFromGateNode(node: GateNode): Pair<List<SchematicPin>, List<SchematicPin>> {
    val inPins = when (node.gateType) {
      GateType.SDFFR -> listOf("D", "CLK", "RST", "SE", "SI").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = if (idx == 0) node.bitWidth else 1) }
      }
      GateType.SDFF -> listOf("D", "CLK", "SE", "SI").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = if (idx == 0) node.bitWidth else 1) }
      }
      GateType.DFFR -> listOf("D", "CLK", "RST").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = if (idx == 0) node.bitWidth else 1) }
      }
      GateType.DFF -> listOf("D", "CLK").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = if (idx == 0) node.bitWidth else 1) }
      }
      GateType.LOCKUP_LATCH -> listOf("D", "GN").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = 1) }
      }
      GateType.MUX2 -> listOf("S", "A", "B").mapIndexedNotNull { idx, label ->
        node.inputs.getOrNull(idx)?.let { SchematicPin(label, it, isOutput = false, bitWidth = if (idx == 0) 1 else node.bitWidth) }
      }
      else -> node.inputs.mapIndexed { idx, net ->
        val label = when {
          node.inputs.size == 1 -> "A"
          idx == 0 -> "A"
          idx == 1 -> "B"
          idx == 2 -> "C"
          else -> "I$idx"
        }
        SchematicPin(label, net, isOutput = false, bitWidth = node.bitWidth)
      }
    }
    val outPin = SchematicPin(
      pinName = if (node.gateType.isSequential) "Q" else "Y",
      netName = node.outputNet,
      isOutput = true,
      bitWidth = node.bitWidth,
    )
    return inPins to listOf(outPin)
  }
}
