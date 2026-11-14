package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.domain.synth.PhysicalLayoutSnapshot
import com.example.domain.synth.PhysicalMaskLayer
import com.example.domain.synth.PlacedCellLayout
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale

@Composable
fun GdsLayoutViewer(
  layout: PhysicalLayoutSnapshot,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val isDark = eda.isDark

  var zoom by remember(layout.moduleName, layout.gdsSha256) { mutableFloatStateOf(1f) }
  var pan by remember(layout.moduleName, layout.gdsSha256) { mutableStateOf(Offset.Zero) }
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  var selectedCell by remember(layout.moduleName, layout.gdsSha256) { mutableStateOf<PlacedCellLayout?>(null) }

  var showSnapshotMode by remember(layout.gdsSha256) { mutableStateOf(false) }

  var enabledLayers by remember {
    mutableStateOf(
      setOf(
        PhysicalMaskLayer.DIE_AREA,
        PhysicalMaskLayer.NWELL,
        PhysicalMaskLayer.DIFF,
        PhysicalMaskLayer.POLY,
        PhysicalMaskLayer.LI1,
        PhysicalMaskLayer.MCON,
        PhysicalMaskLayer.CELLS,
        PhysicalMaskLayer.MET1,
        PhysicalMaskLayer.VIA1,
        PhysicalMaskLayer.MET2,
        PhysicalMaskLayer.VIA2,
        PhysicalMaskLayer.MET3,
        PhysicalMaskLayer.VIA3,
        PhysicalMaskLayer.CLK_TREE,
        PhysicalMaskLayer.IO_PINS,
      )
    )
  }

  val dieOutlineColor = MaterialTheme.colorScheme.outline
  fun layerColor(layer: PhysicalMaskLayer): Color = when (layer) {
    PhysicalMaskLayer.DIE_AREA -> dieOutlineColor
    PhysicalMaskLayer.NWELL -> if (isDark) Color(0xFF1E3A5F) else Color(0xFFBFDBFE)
    PhysicalMaskLayer.DIFF -> if (isDark) Color(0xFF22C55E) else Color(0xFF15803D)
    PhysicalMaskLayer.POLY -> if (isDark) Color(0xFFDC2626) else Color(0xFFB91C1C)
    PhysicalMaskLayer.LI1 -> if (isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    PhysicalMaskLayer.MCON -> if (isDark) Color(0xFFFDE047) else Color(0xFFA16207)
    PhysicalMaskLayer.CELLS -> if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
    PhysicalMaskLayer.MET1 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    PhysicalMaskLayer.VIA1 -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
    PhysicalMaskLayer.MET2 -> if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    PhysicalMaskLayer.VIA2 -> if (isDark) Color(0xFFFDE047) else Color(0xFFCA8A04)
    PhysicalMaskLayer.MET3 -> if (isDark) Color(0xFF34D399) else Color(0xFF059669)
    PhysicalMaskLayer.VIA3 -> if (isDark) Color(0xFFFDA4AF) else Color(0xFFBE123C)
    PhysicalMaskLayer.CLK_TREE -> if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)
    PhysicalMaskLayer.IO_PINS -> if (isDark) Color(0xFF22D3EE) else Color(0xFF0369A1)
  }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Physical Die & GDS Signoff Telemetry Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .testTag("gds_telemetry_bar"),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      GdsStatBadge("Die", "${"%.1f".format(Locale.US, layout.dieWidthUm)}×${"%.1f".format(Locale.US, layout.dieHeightUm)}µm")
      GdsStatBadge("Util", "${"%.1f".format(Locale.US, layout.actualUtilizationPercent)}%")
      GdsStatBadge("WL", "${"%.1f".format(Locale.US, layout.totalWirelengthUm)}µm")
      GdsStatBadge("Skew", "${"%.3f".format(Locale.US, layout.clockSkewNs)}ns")
      GdsStatBadge("Hold", "${if (layout.holdWnsNs >= 0) "+" else ""}${"%.3f".format(Locale.US, layout.holdWnsNs)}ns")
      GdsStatBadge("DRC", if (layout.drcViolations == 0) "0 errs" else "${layout.drcViolations} errs", highlightError = layout.drcViolations > 0)
      GdsStatBadge("GDS", "${layout.gdsBytes.size}B")
    }

    // 1b. View Mode Switcher (Interactive Mask Layers vs KLayout PNG Snapshot)
    if (layout.klayoutSnapshotBitmap != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = !showSnapshotMode,
          onClick = { showSnapshotMode = false },
          leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(16.dp)) },
          label = { Text("Mask Layers (${layout.gdsStructureCount} cells)") },
          modifier = Modifier.testTag("gds_mode_interactive"),
        )
        FilterChip(
          selected = showSnapshotMode,
          onClick = {
            showSnapshotMode = true
            zoom = 1f
            pan = Offset.Zero
          },
          leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
          label = { Text("KLayout Snapshot") },
          modifier = Modifier.testTag("gds_mode_klayout_snapshot"),
        )
      }
    }

    // 2. Interactive Mask Layer Toggle List
    if (!showSnapshotMode) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .testTag("gds_layer_toggle_row"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Rounded.Layers,
          contentDescription = "Physical mask layers",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
        val toggleableLayers = listOf(
          PhysicalMaskLayer.NWELL,
          PhysicalMaskLayer.DIFF,
          PhysicalMaskLayer.POLY,
          PhysicalMaskLayer.LI1,
          PhysicalMaskLayer.CELLS,
          PhysicalMaskLayer.MET1,
          PhysicalMaskLayer.VIA1,
          PhysicalMaskLayer.MET2,
          PhysicalMaskLayer.MET3,
          PhysicalMaskLayer.CLK_TREE,
          PhysicalMaskLayer.IO_PINS,
        )
        toggleableLayers.forEach { layer ->
          val active = layer in enabledLayers
          val swatch = layerColor(layer)
          FilterChip(
            selected = active,
            onClick = {
              enabledLayers = if (active) enabledLayers - layer else enabledLayers + layer
            },
            leadingIcon = {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(if (active) swatch else swatch.copy(alpha = 0.25f)),
              )
            },
            label = {
              Text(
                text = "${layer.shortName} (${layer.layerNumber})",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = swatch.copy(alpha = if (isDark) 0.20f else 0.14f),
            ),
            modifier = Modifier.testTag("layer_toggle_${layer.shortName}"),
          )
        }
      }
    }

    // 3. Zoomable & Pannable GDSII Layout Canvas (or KLayout Snapshot Bitmap)
    Surface(
      color = if (isDark) Color(0xFF070C17) else Color(0xFFF8FAFC),
      shape = MaterialTheme.shapes.large,
      border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = if (isDark) 0.65f else 0.9f)),
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .testTag("gds_layout_canvas"),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .onSizeChanged { viewport = it }
          .pointerInput(layout) {
            detectTransformGestures { _, panChange, zoomChange, _ ->
              zoom = (zoom * zoomChange).coerceIn(0.6f, 8.0f)
              pan = Offset(pan.x + panChange.x, pan.y + panChange.y)
            }
          }
          .pointerInput(layout, zoom, pan, viewport) {
            detectTapGestures { tap ->
              if (viewport.width <= 0 || viewport.height <= 0) return@detectTapGestures
              val pad = 24f
              val baseScale = minOf(
                (viewport.width - pad * 2f) / layout.dieWidthUm.coerceAtLeast(1.0).toFloat(),
                (viewport.height - pad * 2f) / layout.dieHeightUm.coerceAtLeast(1.0).toFloat(),
              ).coerceAtLeast(0.1f)
              val effScale = baseScale * zoom
              val dieScreenW = layout.dieWidthUm.toFloat() * effScale
              val dieScreenH = layout.dieHeightUm.toFloat() * effScale
              val originX = (viewport.width - dieScreenW) / 2f + pan.x
              val originY = (viewport.height - dieScreenH) / 2f + pan.y
              val wx = (tap.x - originX) / effScale
              val wy = (tap.y - originY) / effScale
              selectedCell = layout.placedCells.firstOrNull { c ->
                wx >= c.xUm && wx <= c.xUm + c.widthUm && wy >= c.yUm && wy <= c.yUm + c.heightUm
              }
            }
          },
      ) {
        val snapshotBitmap = layout.klayoutSnapshotBitmap
        if (showSnapshotMode && snapshotBitmap != null) {
          val imageBitmap = remember(layout.gdsSha256, snapshotBitmap) {
            snapshotBitmap.asImageBitmap()
          }
          Image(
            bitmap = imageBitmap,
            contentDescription = "KLayout GDSII layout snapshot for ${layout.moduleName}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
              .fillMaxSize()
              .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
              }
              .testTag("gds_klayout_snapshot_image"),
          )
        } else {
          val dieBorderColor = MaterialTheme.colorScheme.outline
          val coreColor = MaterialTheme.colorScheme.primary
          val seqAccent = MaterialTheme.colorScheme.tertiary
          val critAccent = MaterialTheme.colorScheme.secondary
          val bgGrid = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.14f else 0.25f)

          val nwellColor = layerColor(PhysicalMaskLayer.NWELL)
          val diffColor = layerColor(PhysicalMaskLayer.DIFF)
          val polyColor = layerColor(PhysicalMaskLayer.POLY)
          val li1Color = layerColor(PhysicalMaskLayer.LI1)
          val cellColor = layerColor(PhysicalMaskLayer.CELLS)
          val met1Color = layerColor(PhysicalMaskLayer.MET1)
          val via1Color = layerColor(PhysicalMaskLayer.VIA1)
          val met2Color = layerColor(PhysicalMaskLayer.MET2)
          val met3Color = layerColor(PhysicalMaskLayer.MET3)
          val clkColor = layerColor(PhysicalMaskLayer.CLK_TREE)
          val pinColor = layerColor(PhysicalMaskLayer.IO_PINS)

          Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = 24f
            val baseScale = minOf(
              (size.width - pad * 2f) / layout.dieWidthUm.coerceAtLeast(1.0).toFloat(),
              (size.height - pad * 2f) / layout.dieHeightUm.coerceAtLeast(1.0).toFloat(),
            ).coerceAtLeast(0.1f)
            val s = baseScale * zoom
            val diePxW = layout.dieWidthUm.toFloat() * s
            val diePxH = layout.dieHeightUm.toFloat() * s
            val ox = (size.width - diePxW) / 2f + pan.x
            val oy = (size.height - diePxH) / 2f + pan.y

            fun toX(um: Double): Float = ox + (um.toFloat() * s)
            fun toY(um: Double): Float = oy + (um.toFloat() * s)

            // Die background & subtle grid
            drawRect(
              color = bgGrid,
              topLeft = Offset(ox, oy),
              size = Size(diePxW, diePxH),
              style = Stroke(width = 1.5f),
            )
            // Standard-cell row guides
            for (r in 0..layout.rowCount) {
              val ry = toY(layout.coreYUm + r * layout.rowHeightUm)
              drawLine(
                color = bgGrid,
                start = Offset(toX(layout.coreXUm), ry),
                end = Offset(toX(layout.coreXUm + layout.coreWidthUm), ry),
                strokeWidth = 1f,
              )
            }

            // N-Well stripes
            if (PhysicalMaskLayer.NWELL in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.NWELL }.forEach { poly ->
                drawRect(
                  color = nwellColor.copy(alpha = if (isDark) 0.42f else 0.38f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat(), (poly.heightUm * s).toFloat()),
                )
              }
            }

            // DIFF / active stripes
            if (PhysicalMaskLayer.DIFF in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.DIFF }.forEach { poly ->
                drawRect(
                  color = diffColor.copy(alpha = 0.55f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat().coerceAtLeast(1f), (poly.heightUm * s).toFloat().coerceAtLeast(1f)),
                )
              }
            }

            // Core boundary
            drawRect(
              color = coreColor.copy(alpha = 0.55f),
              topLeft = Offset(toX(layout.coreXUm), toY(layout.coreYUm)),
              size = Size((layout.coreWidthUm * s).toFloat(), (layout.coreHeightUm * s).toFloat()),
              style = Stroke(width = 1.4f),
            )

            // Standard cells
            if (PhysicalMaskLayer.CELLS in enabledLayers) {
              layout.placedCells.filterNot { it.gateType.isPort }.forEach { cell ->
                val isSel = cell.nodeId == selectedCell?.nodeId
                val fill = when {
                  isSel -> coreColor.copy(alpha = 0.45f)
                  cell.gateType.isSequential -> seqAccent.copy(alpha = 0.30f)
                  cell.isCritical -> critAccent.copy(alpha = 0.25f)
                  else -> cellColor.copy(alpha = if (isDark) 0.55f else 0.65f)
                }
                val stroke = when {
                  isSel -> coreColor
                  cell.gateType.isSequential -> seqAccent
                  cell.isCritical -> critAccent
                  else -> dieBorderColor
                }
                drawRect(
                  color = fill,
                  topLeft = Offset(toX(cell.xUm), toY(cell.yUm)),
                  size = Size((cell.widthUm * s).toFloat().coerceAtLeast(2f), (cell.heightUm * s).toFloat().coerceAtLeast(2f)),
                )
                drawRect(
                  color = stroke,
                  topLeft = Offset(toX(cell.xUm), toY(cell.yUm)),
                  size = Size((cell.widthUm * s).toFloat().coerceAtLeast(2f), (cell.heightUm * s).toFloat().coerceAtLeast(2f)),
                  style = Stroke(width = if (isSel) 2.2f else 1f),
                )
              }
            }

            // Polysilicon fingers
            if (PhysicalMaskLayer.POLY in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.POLY }.forEach { poly ->
                drawRect(
                  color = polyColor.copy(alpha = 0.72f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat().coerceAtLeast(1f), (poly.heightUm * s).toFloat().coerceAtLeast(2f)),
                )
              }
            }

            // Local Interconnect (LI1)
            if (PhysicalMaskLayer.LI1 in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.LI1 }.forEach { poly ->
                drawRect(
                  color = li1Color.copy(alpha = 0.75f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat().coerceAtLeast(1f), (poly.heightUm * s).toFloat().coerceAtLeast(1f)),
                )
              }
            }

            // MET1 & MET2 signal routing + MET1 power rails
            if (PhysicalMaskLayer.MET1 in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.MET1 }.forEach { poly ->
                drawRect(
                  color = met1Color.copy(alpha = 0.65f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat().coerceAtLeast(1f), (poly.heightUm * s).toFloat().coerceAtLeast(1f)),
                )
              }
              layout.wireSegments.filter { it.layer == PhysicalMaskLayer.MET1 }.forEach { seg ->
                drawLine(
                  color = if (seg.isCritical) critAccent else met1Color.copy(alpha = 0.85f),
                  start = Offset(toX(seg.x1Um), toY(seg.y1Um)),
                  end = Offset(toX(seg.x2Um), toY(seg.y2Um)),
                  strokeWidth = (seg.widthUm.toFloat() * s).coerceIn(1.2f, 5f),
                )
              }
            }
            if (PhysicalMaskLayer.MET2 in enabledLayers) {
              layout.wireSegments.filter { it.layer == PhysicalMaskLayer.MET2 }.forEach { seg ->
                drawLine(
                  color = if (seg.isCritical) critAccent else met2Color.copy(alpha = 0.85f),
                  start = Offset(toX(seg.x1Um), toY(seg.y1Um)),
                  end = Offset(toX(seg.x2Um), toY(seg.y2Um)),
                  strokeWidth = (seg.widthUm.toFloat() * s).coerceIn(1.2f, 5f),
                )
              }
            }

            // MET3 power ring
            if (PhysicalMaskLayer.MET3 in enabledLayers) {
              layout.polygons.filter { it.layer == PhysicalMaskLayer.MET3 }.forEach { poly ->
                drawRect(
                  color = met3Color.copy(alpha = 0.82f),
                  topLeft = Offset(toX(poly.xUm), toY(poly.yUm)),
                  size = Size((poly.widthUm * s).toFloat().coerceAtLeast(2f), (poly.heightUm * s).toFloat().coerceAtLeast(2f)),
                )
              }
            }

            // Clock Tree H-net
            if (PhysicalMaskLayer.CLK_TREE in enabledLayers) {
              layout.wireSegments.filter { it.layer == PhysicalMaskLayer.CLK_TREE }.forEach { seg ->
                drawLine(
                  color = clkColor,
                  start = Offset(toX(seg.x1Um), toY(seg.y1Um)),
                  end = Offset(toX(seg.x2Um), toY(seg.y2Um)),
                  strokeWidth = (seg.widthUm.toFloat() * s).coerceIn(2f, 6f),
                )
              }
            }

            // VIA1 cuts
            if (PhysicalMaskLayer.VIA1 in enabledLayers) {
              layout.vias.forEach { via ->
                val vs = (0.35f * s).coerceIn(2f, 7f)
                drawRect(
                  color = via1Color,
                  topLeft = Offset(toX(via.xUm) - vs / 2f, toY(via.yUm) - vs / 2f),
                  size = Size(vs, vs),
                )
              }
            }

            // I/O Pins
            if (PhysicalMaskLayer.IO_PINS in enabledLayers) {
              layout.placedCells.filter { it.gateType.isPort }.forEach { pin ->
                drawRect(
                  color = pinColor,
                  topLeft = Offset(toX(pin.xUm), toY(pin.yUm)),
                  size = Size((pin.widthUm * s).toFloat().coerceAtLeast(4f), (pin.heightUm * s).toFloat().coerceAtLeast(4f)),
                )
              }
            }
          }
        }

        // Top-right Zoom / Fit controls
        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
              onClick = { zoom = (zoom * 1.3f).coerceAtMost(8.0f) },
              modifier = Modifier.size(48.dp).testTag("gds_zoom_in_button"),
            ) {
              Icon(Icons.Rounded.Add, contentDescription = "Zoom in GDS layout")
            }
            IconButton(
              onClick = { zoom = (zoom / 1.3f).coerceAtLeast(0.6f) },
              modifier = Modifier.size(48.dp).testTag("gds_zoom_out_button"),
            ) {
              Icon(Icons.Rounded.Remove, contentDescription = "Zoom out GDS layout")
            }
            IconButton(
              onClick = {
                zoom = 1f
                pan = Offset.Zero
              },
              modifier = Modifier.size(48.dp).testTag("gds_fit_button"),
            ) {
              Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Fit GDS die to screen")
            }
          }
        }

        // Bottom-left selected cell readout or zoom indicator
        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.65f)),
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(10.dp),
        ) {
          val cell = selectedCell
          val label = if (cell != null) {
            "${cell.instanceName} (${cell.cellName}) @ (${"%.1f".format(Locale.US, cell.xUm)}, ${"%.1f".format(Locale.US, cell.yUm)}) µm"
          } else {
            "${layout.moduleName}.gds · ${"%.0f".format(Locale.US, zoom * 100)}% · Tap cell or toggle layers"
          }
          Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun GdsStatBadge(
  label: String,
  value: String,
  highlightError: Boolean = false,
) {
  val eda = LocalEdaColors.current
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = 0.7f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        text = value,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold),
        color = if (highlightError) eda.error else MaterialTheme.colorScheme.primary,
      )
    }
  }
}
