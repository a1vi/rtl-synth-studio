package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.GateType
import com.example.ui.theme.JetBrainsMonoFamily
import kotlin.math.roundToInt

enum class PrimitiveCategory {
  CORE_LOGIC,
  SEQUENTIAL,
  IO_ROUTING,
}

data class LogicPrimitiveItem(
  val gateType: GateType,
  val label: String,
  val subtitle: String,
  val category: PrimitiveCategory,
)

object SchematicPrimitiveCatalog {
  val items: List<LogicPrimitiveItem> = listOf(
    LogicPrimitiveItem(GateType.AND2, "AND", "2-in Y = A & B", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.OR2, "OR", "2-in Y = A | B", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.INV, "NOT", "Inverter Y = ~A", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.DFF, "FLIP-FLOP", "D-FF Q <= D @clk", PrimitiveCategory.SEQUENTIAL),
    LogicPrimitiveItem(GateType.NAND2, "NAND", "2-in Y = ~(A & B)", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.XOR2, "XOR", "2-in Y = A ^ B", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.MUX2, "MUX", "2:1 S ? A : B", PrimitiveCategory.CORE_LOGIC),
    LogicPrimitiveItem(GateType.INPUT_PORT, "INPUT", "Primary Input", PrimitiveCategory.IO_ROUTING),
    LogicPrimitiveItem(GateType.OUTPUT_PORT, "OUTPUT", "Primary Output", PrimitiveCategory.IO_ROUTING),
  )
}

@Composable
fun LogicPrimitiveLibrarySidebar(
  expanded: Boolean,
  compactMode: Boolean,
  wireModeActive: Boolean,
  onToggleExpanded: () -> Unit,
  onToggleWireMode: () -> Unit,
  onStartBlankSchematic: () -> Unit,
  onTapPlace: (GateType) -> Unit,
  onDragStart: (LogicPrimitiveItem, Offset) -> Unit,
  onDragMove: (Offset) -> Unit,
  onDragEnd: (LogicPrimitiveItem, Offset) -> Unit,
  onDragCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!expanded) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      shape = MaterialTheme.shapes.medium,
      modifier = modifier
        .fillMaxHeight()
        .width(48.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.medium)
        .testTag("logic_library_collapsed_rail"),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 6.dp),
      ) {
        IconButton(
          onClick = onToggleExpanded,
          modifier = Modifier
            .size(48.dp)
            .testTag("toggle_library_sidebar_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "Open logic primitive library",
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        IconButton(
          onClick = onToggleWireMode,
          modifier = Modifier
            .size(48.dp)
            .testTag("schematic_wire_mode_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Cable,
            contentDescription = if (wireModeActive) "Exit wire mode" else "Connect gates with wire",
            tint = if (wireModeActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    return
  }

  val sidebarWidth = if (compactMode) 90.dp else 188.dp

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = modifier
      .fillMaxHeight()
      .width(sidebarWidth)
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), MaterialTheme.shapes.large)
      .testTag("logic_library_sidebar"),
  ) {
    Column(modifier = Modifier.fillMaxHeight()) {
      // Header bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(start = if (compactMode) 6.dp else 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (!compactMode) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Logic Library",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "Drag to canvas",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          Text(
            text = "Gates",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
          )
        }
        IconButton(
          onClick = onToggleExpanded,
          modifier = Modifier
            .size(40.dp)
            .testTag("toggle_library_sidebar_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ChevronLeft,
            contentDescription = "Collapse logic primitive library",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Quick Schematic Tools: Wire + Blank Canvas
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        LibraryToolButton(
          label = "Wire",
          icon = Icons.Rounded.Cable,
          active = wireModeActive,
          compact = compactMode,
          contentDesc = if (wireModeActive) "Exit wire mode" else "Wire gates together",
          onClick = onToggleWireMode,
          modifier = Modifier
            .weight(1f)
            .testTag("schematic_wire_mode_button"),
        )
        LibraryToolButton(
          label = "Blank",
          icon = Icons.AutoMirrored.Rounded.NoteAdd,
          active = false,
          compact = compactMode,
          contentDesc = "Start blank schematic canvas",
          onClick = onStartBlankSchematic,
          modifier = Modifier
            .weight(1f)
            .testTag("new_blank_schematic_button"),
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

      LazyColumn(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
          .weight(1f)
          .testTag("logic_library_list"),
      ) {
        items(SchematicPrimitiveCatalog.items, key = { it.gateType.name }) { item ->
          PrimitivePaletteTile(
            item = item,
            compactMode = compactMode,
            onTapPlace = { onTapPlace(item.gateType) },
            onDragStart = { rootPos -> onDragStart(item, rootPos) },
            onDragMove = onDragMove,
            onDragEnd = { rootPos -> onDragEnd(item, rootPos) },
            onDragCancel = onDragCancel,
          )
        }
      }
    }
  }
}

@Composable
private fun LibraryToolButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  active: Boolean,
  compact: Boolean,
  contentDesc: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bg = if (active) {
    MaterialTheme.colorScheme.secondaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
  }
  val fg = if (active) {
    MaterialTheme.colorScheme.secondary
  } else {
    MaterialTheme.colorScheme.onSurface
  }
  val borderColor = if (active) {
    MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
  } else {
    MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
  }

  Surface(
    onClick = onClick,
    color = bg,
    shape = MaterialTheme.shapes.small,
    modifier = modifier
      .height(48.dp)
      .border(1.dp, borderColor, MaterialTheme.shapes.small)
      .semantics { contentDescription = contentDesc },
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = fg,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (compact) 9.5.sp else 10.5.sp),
        color = fg,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun PrimitivePaletteTile(
  item: LogicPrimitiveItem,
  compactMode: Boolean,
  onTapPlace: () -> Unit,
  onDragStart: (Offset) -> Unit,
  onDragMove: (Offset) -> Unit,
  onDragEnd: (Offset) -> Unit,
  onDragCancel: () -> Unit,
) {
  var tilePosInRoot by remember { mutableStateOf(Offset.Zero) }
  var currentDragRoot by remember { mutableStateOf(Offset.Zero) }

  val accentColor = when (item.category) {
    PrimitiveCategory.SEQUENTIAL -> MaterialTheme.colorScheme.tertiary
    PrimitiveCategory.IO_ROUTING -> MaterialTheme.colorScheme.secondary
    PrimitiveCategory.CORE_LOGIC -> MaterialTheme.colorScheme.primary
  }

  val shape = MaterialTheme.shapes.medium
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
      .border(1.dp, accentColor.copy(alpha = 0.25f), shape)
      .onGloballyPositioned { coords ->
        tilePosInRoot = coords.positionInRoot()
      }
      .pointerInput(item.gateType) {
        detectDragGestures(
          onDragStart = { localOffset ->
            currentDragRoot = tilePosInRoot + localOffset
            onDragStart(currentDragRoot)
          },
          onDrag = { change, dragAmount ->
            change.consume()
            currentDragRoot += dragAmount
            onDragMove(currentDragRoot)
          },
          onDragEnd = {
            onDragEnd(currentDragRoot)
          },
          onDragCancel = {
            onDragCancel()
          },
        )
      }
      .clickable(onClick = onTapPlace)
      .padding(horizontal = if (compactMode) 6.dp else 10.dp, vertical = if (compactMode) 5.dp else 8.dp)
      .testTag("library_primitive_${item.gateType.name}"),
  ) {
    if (compactMode) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        PrimitiveGateGlyph(
          gateType = item.gateType,
          accent = accentColor,
          fill = MaterialTheme.colorScheme.surface,
          modifier = Modifier.size(40.dp, 22.dp),
        )
        Text(
          text = item.label,
          fontFamily = JetBrainsMonoFamily,
          fontSize = 9.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    } else {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        PrimitiveGateGlyph(
          gateType = item.gateType,
          accent = accentColor,
          fill = MaterialTheme.colorScheme.surface,
          modifier = Modifier.size(48.dp, 28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            text = item.subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
fun DraggedPrimitiveOverlay(
  item: LogicPrimitiveItem,
  localPointerOffset: Offset,
  canDropOnCanvas: Boolean,
) {
  val accent = when {
    !canDropOnCanvas -> MaterialTheme.colorScheme.outline
    item.category == PrimitiveCategory.SEQUENTIAL -> MaterialTheme.colorScheme.tertiary
    item.category == PrimitiveCategory.IO_ROUTING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
  }

  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    tonalElevation = 6.dp,
    shadowElevation = 10.dp,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .offset {
        IntOffset(
          x = (localPointerOffset.x - 58.dp.toPx()).roundToInt(),
          y = (localPointerOffset.y - 34.dp.toPx()).roundToInt(),
        )
      }
      .border(1.8.dp, accent, MaterialTheme.shapes.medium)
      .testTag("dragged_primitive_ghost"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PrimitiveGateGlyph(
        gateType = item.gateType,
        accent = accent,
        fill = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(48.dp, 28.dp),
      )
      Column {
        Text(
          text = item.label,
          fontFamily = JetBrainsMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = accent,
        )
        Text(
          text = if (canDropOnCanvas) "Release to place" else "Drag onto canvas",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun PrimitiveGateGlyph(
  gateType: GateType,
  accent: Color,
  fill: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val padX = w * 0.14f
    val padY = h * 0.10f
    val bx0 = padX
    val bx1 = w - padX
    val by0 = padY
    val by1 = h - padY
    val bw = bx1 - bx0
    val bh = by1 - by0
    val strokeW = (h * 0.085f).coerceIn(1.5f, 3f)

    // Pin lead lines
    if (gateType != GateType.INPUT_PORT) {
      if (gateType == GateType.INV || gateType == GateType.OUTPUT_PORT) {
        drawLine(accent.copy(alpha = 0.8f), Offset(0f, h / 2f), Offset(bx0, h / 2f), strokeWidth = strokeW)
      } else {
        drawLine(accent.copy(alpha = 0.8f), Offset(0f, by0 + bh * 0.30f), Offset(bx0 + bw * 0.12f, by0 + bh * 0.30f), strokeWidth = strokeW)
        drawLine(accent.copy(alpha = 0.8f), Offset(0f, by0 + bh * 0.70f), Offset(bx0 + bw * 0.12f, by0 + bh * 0.70f), strokeWidth = strokeW)
      }
    }
    if (gateType != GateType.OUTPUT_PORT) {
      drawLine(accent.copy(alpha = 0.8f), Offset(bx1, h / 2f), Offset(w, h / 2f), strokeWidth = strokeW)
    }

    when (gateType) {
      GateType.AND2, GateType.NAND2 -> {
        val bodyRight = if (gateType == GateType.NAND2) bx1 - bh * 0.22f else bx1
        val midX = bx0 + bw * 0.45f
        val path = Path().apply {
          moveTo(bx0, by0)
          lineTo(midX, by0)
          cubicTo(bodyRight, by0, bodyRight, by1, midX, by1)
          lineTo(bx0, by1)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
        if (gateType == GateType.NAND2) {
          val r = bh * 0.12f
          val c = Offset(bx1 - r, h / 2f)
          drawCircle(fill, radius = r, center = c)
          drawCircle(accent, radius = r, center = c, style = Stroke(width = strokeW))
        }
      }
      GateType.OR2, GateType.XOR2 -> {
        val leftX = if (gateType == GateType.XOR2) bx0 + bw * 0.14f else bx0
        val path = Path().apply {
          moveTo(leftX, by0)
          lineTo(bx0 + bw * 0.45f, by0)
          quadraticTo(bx0 + bw * 0.85f, by0, bx1, h / 2f)
          quadraticTo(bx0 + bw * 0.85f, by1, bx0 + bw * 0.45f, by1)
          lineTo(leftX, by1)
          quadraticTo(leftX + bw * 0.20f, h / 2f, leftX, by0)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
        if (gateType == GateType.XOR2) {
          val arc = Path().apply {
            moveTo(bx0, by0 + bh * 0.08f)
            quadraticTo(bx0 + bw * 0.20f, h / 2f, bx0, by1 - bh * 0.08f)
          }
          drawPath(arc, accent, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }
      }
      GateType.INV -> {
        val r = bh * 0.13f
        val tipX = bx1 - r * 2f
        val path = Path().apply {
          moveTo(bx0, by0)
          lineTo(tipX, h / 2f)
          lineTo(bx0, by1)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
        val c = Offset(bx1 - r, h / 2f)
        drawCircle(fill, radius = r, center = c)
        drawCircle(accent, radius = r, center = c, style = Stroke(width = strokeW))
      }
      GateType.DFF, GateType.DFFR -> {
        drawRoundRect(
          color = fill,
          topLeft = Offset(bx0, by0),
          size = Size(bw, bh),
          cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
          color = accent,
          topLeft = Offset(bx0, by0),
          size = Size(bw, bh),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = strokeW),
        )
        val clkTri = Path().apply {
          moveTo(bx0, by1 - bh * 0.34f)
          lineTo(bx0 + bw * 0.22f, by1 - bh * 0.22f)
          lineTo(bx0, by1 - bh * 0.10f)
        }
        drawPath(clkTri, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
      }
      GateType.MUX2 -> {
        val slope = bh * 0.22f
        val path = Path().apply {
          moveTo(bx0, by0)
          lineTo(bx1, by0 + slope)
          lineTo(bx1, by1 - slope)
          lineTo(bx0, by1)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
      }
      GateType.INPUT_PORT -> {
        val tip = bw * 0.25f
        val path = Path().apply {
          moveTo(bx0, by0 + bh * 0.12f)
          lineTo(bx1 - tip, by0 + bh * 0.12f)
          lineTo(bx1, h / 2f)
          lineTo(bx1 - tip, by1 - bh * 0.12f)
          lineTo(bx0, by1 - bh * 0.12f)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
      }
      GateType.OUTPUT_PORT -> {
        val notch = bw * 0.25f
        val path = Path().apply {
          moveTo(bx0 + notch, by0 + bh * 0.12f)
          lineTo(bx1, by0 + bh * 0.12f)
          lineTo(bx1, by1 - bh * 0.12f)
          lineTo(bx0 + notch, by1 - bh * 0.12f)
          lineTo(bx0, h / 2f)
          close()
        }
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(width = strokeW, join = StrokeJoin.Round))
      }
      else -> {
        drawRoundRect(
          color = fill,
          topLeft = Offset(bx0, by0),
          size = Size(bw, bh),
          cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
          color = accent,
          topLeft = Offset(bx0, by0),
          size = Size(bw, bh),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = strokeW),
        )
      }
    }
  }
}
