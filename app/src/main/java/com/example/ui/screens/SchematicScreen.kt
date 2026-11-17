package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.synth.GateNode
import com.example.domain.synth.NetlistLayoutEngine
import com.example.domain.synth.ParsedVerilogDesign
import com.example.domain.synth.SchematicCell
import com.example.domain.synth.SchematicCellCategory
import com.example.domain.synth.SchematicPin
import com.example.domain.synth.SchematicSearchHit
import com.example.domain.synth.StructuralNetlistParser
import com.example.domain.synth.SynthesisResult
import com.example.ui.components.CadenceSchematicPalette
import com.example.ui.components.NetlistCanvasViewer
import com.example.ui.components.SchematicExporter
import com.example.ui.components.SchematicToolMode
import com.example.ui.components.rememberNetlistViewportState

@Composable
fun SchematicScreen(
  synthesisResult: SynthesisResult?,
  selectedNodeId: String?,
  onSelectNode: (String?) -> Unit,
  onNotifyCopied: (String) -> Unit,
  modifier: Modifier = Modifier,
  rtlSource: String = "",
  parsedDesign: ParsedVerilogDesign? = null,
  onJumpToRtlLine: (Int) -> Unit = {},
  highlightedPathNodeIds: Set<String> = emptySet(),
  highlightedPathNets: Set<String> = emptySet(),
  onClearPathHighlight: () -> Unit = {},
  embedded: Boolean = false,
) {
  val context = LocalContext.current
  val report = (synthesisResult as? SynthesisResult.Success)?.report
  val errorResult = synthesisResult as? SynthesisResult.Error
  val viewportState = rememberNetlistViewportState()

  var viewMode by rememberSaveable { mutableIntStateOf(0) } // 0 = Schematic Canvas, 1 = Netlist Verilog
  var toolMode by rememberSaveable { mutableStateOf(SchematicToolMode.SELECT) }
  var showNetLabels by rememberSaveable { mutableStateOf(true) }
  var searchOpen by rememberSaveable { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var selectedNetName by remember { mutableStateOf<String?>(null) }

  // Hierarchy scope & Previous/Next view history stack
  val scopeHistory = remember(report?.moduleName) { mutableStateListOf("") }
  var historyIndex by remember(report?.moduleName) { mutableIntStateOf(0) }
  val activeScopePath = scopeHistory.getOrElse(historyIndex) { "" }

  var fitTriggerToken by remember { mutableIntStateOf(0) }
  var zoomDeltaTrigger by remember { mutableStateOf(0 to 1f) }

  var pendingPngBytes by remember { mutableStateOf<ByteArray?>(null) }
  var pendingSvgString by remember { mutableStateOf<String?>(null) }

  val pngExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("image/png"),
  ) { uri: Uri? ->
    val bytes = pendingPngBytes
    pendingPngBytes = null
    if (uri != null && bytes != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
      }.onSuccess {
        onNotifyCopied("Saved schematic PNG (${bytes.size / 1024} KB).")
      }.onFailure { err ->
        onNotifyCopied("PNG export failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  val svgExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("image/svg+xml"),
  ) { uri: Uri? ->
    val svg = pendingSvgString
    pendingSvgString = null
    if (uri != null && svg != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(svg.toByteArray(Charsets.UTF_8)) }
      }.onSuccess {
        onNotifyCopied("Saved schematic SVG.")
      }.onFailure { err ->
        onNotifyCopied("SVG export failed: ${err.localizedMessage ?: "error"}")
      }
    }
  }

  fun navigateToScope(newScope: String) {
    val clean = newScope.trim().trim('/')
    if (clean == activeScopePath) return
    while (scopeHistory.size - 1 > historyIndex) {
      scopeHistory.removeAt(scopeHistory.lastIndex)
    }
    scopeHistory.add(clean)
    historyIndex = scopeHistory.lastIndex
    onSelectNode(null)
    selectedNetName = null
  }

  val schematicGraph = remember(report?.netlistVerilog, report?.nodes, parsedDesign, activeScopePath) {
    report?.let {
      StructuralNetlistParser.buildSchematicGraph(
        report = it,
        parsedDesign = parsedDesign,
        scopePath = activeScopePath,
      )
    }
  }

  val routedLayout = remember(schematicGraph) {
    schematicGraph?.let { NetlistLayoutEngine.computeRoutedSchematicLayout(it) }
  }

  val searchHits = remember(schematicGraph, searchQuery) {
    if (schematicGraph == null || searchQuery.isBlank()) {
      emptyList()
    } else {
      StructuralNetlistParser.searchCellsAndNets(schematicGraph, searchQuery)
    }
  }

  val activeCell: SchematicCell? = remember(schematicGraph, report, selectedNodeId) {
    if (selectedNodeId == null) {
      null
    } else {
      schematicGraph?.cellById?.get(selectedNodeId)
        ?: report?.nodes?.firstOrNull { it.id == selectedNodeId }?.let { gateToFallbackCell(it) }
    }
  }
  val activeNet = remember(schematicGraph, selectedNetName) {
    selectedNetName?.let { schematicGraph?.netByName?.get(it) }
  }

  LaunchedEffect(selectedNodeId) {
    if (selectedNodeId != null) {
      selectedNetName = null
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(CadenceSchematicPalette.ChromeBg)
      .clipToBounds()
      .then(if (embedded) Modifier else Modifier.padding(horizontal = 8.dp, vertical = 6.dp)),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
      val isWideDesktop = maxWidth >= 680.dp
      val isInspectorSidePane = maxWidth >= 920.dp
      val availHeight = maxHeight
      val minCanvasHeight = (availHeight * 0.34f).coerceIn(136.dp, 220.dp)
      val maxInspectorDrawerHeight = (availHeight * 0.25f).coerceIn(104.dp, 190.dp)
      var sidebarOpen by rememberSaveable(isWideDesktop) { mutableStateOf(isWideDesktop) }

      Column(modifier = Modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // 1. Top Cadence-Style Engineering Toolbar
        CadenceTopToolbar(
          sidebarOpen = sidebarOpen,
          toolMode = toolMode,
          showNetLabels = showNetLabels,
          searchOpen = searchOpen,
          canGoBack = historyIndex > 0,
          canGoForward = historyIndex < scopeHistory.lastIndex,
          canGoUpHierarchy = activeScopePath.isNotEmpty(),
          viewMode = viewMode,
          hasLayout = routedLayout != null,
          onToggleSidebar = { sidebarOpen = !sidebarOpen },
          onSetToolMode = { toolMode = it },
          onZoomIn = { zoomDeltaTrigger = (zoomDeltaTrigger.first + 1) to 1.28f },
          onZoomOut = { zoomDeltaTrigger = (zoomDeltaTrigger.first + 1) to 0.78f },
          onFit = { fitTriggerToken += 1 },
          onHierarchyUp = {
            val parent = if ('/' in activeScopePath) activeScopePath.substringBeforeLast('/') else ""
            navigateToScope(parent)
          },
          onHistoryPrev = {
            if (historyIndex > 0) {
              historyIndex -= 1
              onSelectNode(null)
              selectedNetName = null
            }
          },
          onHistoryNext = {
            if (historyIndex < scopeHistory.lastIndex) {
              historyIndex += 1
              onSelectNode(null)
              selectedNetName = null
            }
          },
          onToggleSearch = { searchOpen = !searchOpen },
          onToggleNetLabels = { showNetLabels = !showNetLabels },
          onExportPng = {
            routedLayout?.let { layout ->
              pendingPngBytes = SchematicExporter.buildPngBytes(layout, showNetLabels)
              pngExportLauncher.launch("${layout.graph.topModuleName}_schematic.png")
            }
          },
          onExportSvg = {
            routedLayout?.let { layout ->
              pendingSvgString = SchematicExporter.buildSvgString(layout, showNetLabels)
              svgExportLauncher.launch("${layout.graph.topModuleName}_schematic.svg")
            }
          },
          onSharePng = {
            routedLayout?.let { layout ->
              val bytes = SchematicExporter.buildPngBytes(layout, showNetLabels)
              SchematicExporter.shareSchematicFile(
                context = context,
                fileName = "${layout.graph.topModuleName}_schematic.png",
                mimeType = "image/png",
                bytes = bytes,
                onResult = onNotifyCopied,
              )
            }
          },
          onShareSvg = {
            routedLayout?.let { layout ->
              val svg = SchematicExporter.buildSvgString(layout, showNetLabels)
              SchematicExporter.shareSchematicFile(
                context = context,
                fileName = "${layout.graph.topModuleName}_schematic.svg",
                mimeType = "image/svg+xml",
                bytes = svg.toByteArray(Charsets.UTF_8),
                onResult = onNotifyCopied,
              )
            }
          },
          onSetViewMode = { viewMode = it },
        )

        if (report == null) {
          if (errorResult != null) {
            SynthesisErrorSchematicState(
              error = errorResult,
              onJumpToRtlLine = onJumpToRtlLine,
              modifier = Modifier.weight(1f),
            )
          } else {
            EmptySchematicState(modifier = Modifier.weight(1f))
          }
          CadenceBottomStatusBar(
            synthesisResult = synthesisResult,
            graph = null,
            selectedCell = null,
            selectedNet = null,
          )
          return@Column
        }

        val graph = schematicGraph!!
        val layout = routedLayout!!

        if (viewMode == 0) {
          SchematicSummaryBar(
            report = report,
            selectedNodeId = selectedNodeId,
            onFocusCriticalPath = {
              val pathIds = report.criticalPath.map { it.nodeId }
              if (pathIds.isNotEmpty()) {
                val currentIndex = selectedNodeId?.let { pathIds.indexOf(it) } ?: -1
                val nextId = if (currentIndex >= 0) {
                  pathIds[(currentIndex + 1) % pathIds.size]
                } else {
                  pathIds.last()
                }
                if (activeScopePath.isNotEmpty() && graph.cellById[nextId] == null) {
                  navigateToScope("")
                }
                selectedNetName = null
                onSelectNode(nextId)
              }
            },
          )

          HierarchyBreadcrumbBar(
            topModuleName = graph.topModuleName,
            activeScopePath = activeScopePath,
            cellCount = graph.cells.count { !it.category.isPort },
            netCount = graph.nets.size,
            onSelectScope = ::navigateToScope,
          )

          if (highlightedPathNodeIds.isNotEmpty() || highlightedPathNets.isNotEmpty()) {
            androidx.compose.material3.Surface(
              color = CadenceSchematicPalette.ChromeSurface,
              shape = androidx.compose.ui.graphics.RectangleShape,
              border = androidx.compose.foundation.BorderStroke(1.dp, CadenceSchematicPalette.WireFanInCyan.copy(alpha = 0.6f)),
              modifier = Modifier.fillMaxWidth().clipToBounds(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Analysis Highlight: ${highlightedPathNodeIds.size} cell(s) · ${highlightedPathNets.size} net(s)",
                  style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                  color = CadenceSchematicPalette.WireFanInCyan,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                  androidx.compose.material3.TextButton(
                    onClick = onClearPathHighlight,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.heightIn(min = 26.dp),
                  ) {
                    Text("Clear Highlight", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1)
                  }
                }
              }
            }
          }

          AnimatedVisibility(visible = searchOpen) {
            SchematicSearchPanel(
              query = searchQuery,
              results = searchHits,
              onQueryChange = { searchQuery = it },
              onPickHit = { hit ->
                when (hit) {
                  is SchematicSearchHit.CellHit -> {
                    selectedNetName = null
                    onSelectNode(hit.cell.id)
                  }
                  is SchematicSearchHit.NetHit -> {
                    onSelectNode(null)
                    selectedNetName = hit.net.name
                  }
                }
              },
              onClose = { searchOpen = false },
            )
          }

          // 2. Three-Area Schematic Workspace: Left Sidebar + Center Black Canvas + Inspector
          Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Row(
              modifier = Modifier.fillMaxSize(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (sidebarOpen && isWideDesktop) {
                SchematicLeftSidebar(
                  graph = graph,
                  activeScopePath = activeScopePath,
                  selectedCellId = activeCell?.id,
                  selectedSourceLine = activeCell?.sourceLine ?: 0,
                  rtlSource = rtlSource,
                  onSelectScope = ::navigateToScope,
                  onSelectCell = { cellId ->
                    selectedNetName = null
                    onSelectNode(cellId)
                  },
                  onJumpToRtlLine = onJumpToRtlLine,
                  modifier = Modifier
                    .width(236.dp)
                    .fillMaxHeight(),
                )
              }

              // Center Canvas & Inspector
              if (isInspectorSidePane) {
                Row(
                  modifier = Modifier.weight(1f).fillMaxHeight(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  NetlistCanvasViewer(
                    report = report,
                    selectedNodeId = activeCell?.id,
                    onSelectNode = { nodeId ->
                      if (nodeId != null) selectedNetName = null
                      onSelectNode(nodeId)
                    },
                    routedLayout = layout,
                    selectedNetName = selectedNetName,
                    onSelectNet = { net ->
                      if (net != null) onSelectNode(null)
                      selectedNetName = net
                    },
                    onDrillSubmodule = ::navigateToScope,
                    toolMode = toolMode,
                    showNetLabels = showNetLabels,
                    fitTriggerToken = fitTriggerToken,
                    zoomDeltaTrigger = zoomDeltaTrigger,
                    viewportState = viewportState,
                    highlightedPathNodeIds = highlightedPathNodeIds,
                    highlightedPathNets = highlightedPathNets,
                    modifier = Modifier
                      .weight(1f)
                      .fillMaxHeight(),
                  )

                  Column(
                    modifier = Modifier
                      .width(268.dp)
                      .fillMaxHeight()
                      .clipToBounds()
                      .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    SchematicCellStrip(
                      cells = graph.cells,
                      selectedCellId = activeCell?.id,
                      onSelectCell = { id ->
                        selectedNetName = null
                        onSelectNode(id)
                      },
                    )
                    when {
                      activeCell != null -> CellInspectorCard(
                        cell = activeCell,
                        graph = graph,
                        onSelectCell = { id ->
                          selectedNetName = null
                          onSelectNode(id)
                        },
                        onSelectNet = { net ->
                          onSelectNode(null)
                          selectedNetName = net
                        },
                        onDrillSubmodule = ::navigateToScope,
                        onJumpToRtlLine = onJumpToRtlLine,
                        onClearSelection = { onSelectNode(null) },
                      )
                      activeNet != null -> NetInspectorCard(
                        net = activeNet,
                        graph = graph,
                        onSelectCell = { id ->
                          selectedNetName = null
                          onSelectNode(id)
                        },
                        onClearSelection = { selectedNetName = null },
                      )
                      else -> SchematicIdleHintCard(graph = graph, onSelectCell = onSelectNode)
                    }
                  }
                }
              } else {
                Column(
                  modifier = Modifier.weight(1f).fillMaxHeight(),
                  verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                  NetlistCanvasViewer(
                    report = report,
                    selectedNodeId = activeCell?.id,
                    onSelectNode = { nodeId ->
                      if (nodeId != null) selectedNetName = null
                      onSelectNode(nodeId)
                    },
                    routedLayout = layout,
                    selectedNetName = selectedNetName,
                    onSelectNet = { net ->
                      if (net != null) onSelectNode(null)
                      selectedNetName = net
                    },
                    onDrillSubmodule = ::navigateToScope,
                    toolMode = toolMode,
                    showNetLabels = showNetLabels,
                    fitTriggerToken = fitTriggerToken,
                    zoomDeltaTrigger = zoomDeltaTrigger,
                    viewportState = viewportState,
                    highlightedPathNodeIds = highlightedPathNodeIds,
                    highlightedPathNets = highlightedPathNets,
                    modifier = Modifier
                      .weight(1f)
                      .fillMaxWidth()
                      .heightIn(min = minCanvasHeight),
                  )

                  SchematicCellStrip(
                    cells = graph.cells,
                    selectedCellId = activeCell?.id,
                    onSelectCell = { id ->
                      selectedNetName = null
                      onSelectNode(id)
                    },
                  )

                  AnimatedVisibility(visible = activeCell != null || activeNet != null) {
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxInspectorDrawerHeight)
                        .clipToBounds()
                        .verticalScroll(rememberScrollState()),
                    ) {
                      when {
                        activeCell != null -> CellInspectorCard(
                          cell = activeCell,
                          graph = graph,
                          onSelectCell = { id ->
                            selectedNetName = null
                            onSelectNode(id)
                          },
                          onSelectNet = { net ->
                            onSelectNode(null)
                            selectedNetName = net
                          },
                          onDrillSubmodule = ::navigateToScope,
                          onJumpToRtlLine = onJumpToRtlLine,
                          onClearSelection = { onSelectNode(null) },
                        )
                        activeNet != null -> NetInspectorCard(
                          net = activeNet,
                          graph = graph,
                          onSelectCell = { id ->
                            selectedNetName = null
                            onSelectNode(id)
                          },
                          onClearSelection = { selectedNetName = null },
                        )
                      }
                    }
                  }
                }
              }
            }

            // Compact drawer overlay when Left Sidebar toggled open on portrait phone (< 680dp)
            if (sidebarOpen && !isWideDesktop) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.45f))
                  .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                  ) { sidebarOpen = false },
              )
              SchematicLeftSidebar(
                graph = graph,
                activeScopePath = activeScopePath,
                selectedCellId = activeCell?.id,
                selectedSourceLine = activeCell?.sourceLine ?: 0,
                rtlSource = rtlSource,
                onSelectScope = { scope ->
                  navigateToScope(scope)
                },
                onSelectCell = { cellId ->
                  selectedNetName = null
                  onSelectNode(cellId)
                  sidebarOpen = false
                },
                onJumpToRtlLine = onJumpToRtlLine,
                modifier = Modifier
                  .align(Alignment.CenterStart)
                  .widthIn(max = 248.dp)
                  .fillMaxWidth(0.78f)
                  .fillMaxHeight(),
              )
            }
          }
        } else {
          Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sidebarOpen && isWideDesktop) {
              SchematicLeftSidebar(
                graph = graph,
                activeScopePath = activeScopePath,
                selectedCellId = activeCell?.id,
                selectedSourceLine = activeCell?.sourceLine ?: 0,
                rtlSource = rtlSource,
                onSelectScope = ::navigateToScope,
                onSelectCell = { cellId ->
                  selectedNetName = null
                  onSelectNode(cellId)
                  viewMode = 0
                },
                onJumpToRtlLine = onJumpToRtlLine,
                modifier = Modifier
                  .width(236.dp)
                  .fillMaxHeight(),
              )
            }
            StructuralNetlistCard(
              netlist = report.netlistVerilog,
              onCopy = { onNotifyCopied("Copied structural Verilog netlist to clipboard.") },
              modifier = Modifier.weight(1f).fillMaxHeight(),
            )
          }
        }

        // 3. Bottom EDA Status Bar
        CadenceBottomStatusBar(
          synthesisResult = synthesisResult,
          graph = graph,
          selectedCell = activeCell,
          selectedNet = activeNet,
        )
      }
    }
  }
}

@Composable
private fun SchematicIdleHintCard(
  graph: com.example.domain.synth.SchematicNetlistGraph,
  onSelectCell: (String) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(CadenceSchematicPalette.ChromeSurface)
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "GATE-LEVEL CONNECTIVITY READY",
      style = androidx.compose.ui.text.TextStyle(
        fontFamily = com.example.ui.theme.JetBrainsMonoFamily,
        fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        color = CadenceSchematicPalette.WireGreen,
      ),
    )
    val firstGate = graph.cells.firstOrNull { !it.category.isPort }
    if (firstGate != null) {
      Text(
        text = "Tap any orange standard cell or green orthogonal wire to inspect properties and trace connections.",
        style = androidx.compose.ui.text.TextStyle(
          fontFamily = com.example.ui.theme.JetBrainsMonoFamily,
          fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
          color = CadenceSchematicPalette.ChromeMuted,
        ),
      )
      androidx.compose.material3.OutlinedButton(
        onClick = { onSelectCell(firstGate.id) },
        shape = androidx.compose.ui.graphics.RectangleShape,
      ) {
        Text(
          text = "Inspect ${firstGate.instanceName}",
          style = androidx.compose.ui.text.TextStyle(
            fontFamily = com.example.ui.theme.JetBrainsMonoFamily,
            fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
          ),
        )
      }
    }
  }
}

private fun gateToFallbackCell(node: GateNode): SchematicCell = SchematicCell(
  id = node.id,
  instanceName = node.instanceName,
  cellType = node.cellName,
  category = when {
    node.gateType.isPort -> SchematicCellCategory.INPUT_PORT
    node.gateType.isSequential -> SchematicCellCategory.DFF
    else -> SchematicCellCategory.STD_CELL
  },
  gateType = node.gateType,
  inputPins = node.inputs.mapIndexed { idx, net -> SchematicPin("I$idx", net, isOutput = false, bitWidth = node.bitWidth) },
  outputPins = listOf(SchematicPin(if (node.gateType.isSequential) "Q" else "Y", node.outputNet, isOutput = true, bitWidth = node.bitWidth)),
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
