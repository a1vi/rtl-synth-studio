package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.PortDirection
import com.example.domain.synth.SignalTrace
import com.example.domain.synth.SimulationResult
import com.example.domain.synth.WaveformRadix
import com.example.ui.theme.JetBrainsMonoFamily

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

private val LABEL_COL_WIDTH = 108.dp
private val CYCLE_COL_WIDTH = 44.dp
private val ROW_HEIGHT = 42.dp
private val SUB_BIT_ROW_HEIGHT = 30.dp

@Composable
fun WaveformViewer(
  simulation: SimulationResult,
  radix: WaveformRadix,
  selectedCycle: Int,
  onSelectCycle: (Int) -> Unit,
  modifier: Modifier = Modifier,
  zoomScale: Float = 1f,
  visibleSignals: Set<String> = emptySet(),
  cursorBCycle: Int? = null,
  onZoomDelta: ((Float) -> Unit)? = null,
  signalSearchQuery: String = "",
  signalGroupFilter: Int = 0, // 0=All, 1=Inputs, 2=Outputs, 3=Registers, 4=Clock
) {
  val scrollState = rememberScrollState()
  val textMeasurer = rememberTextMeasurer()
  var expandedBusNames by remember(simulation.moduleName) { mutableStateOf(emptySet<String>()) }

  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.secondary
  val tertiary = MaterialTheme.colorScheme.tertiary
  val outline = MaterialTheme.colorScheme.outline
  val onSurface = MaterialTheme.colorScheme.onSurface
  val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

  val cycleColWidth = (CYCLE_COL_WIDTH.value * zoomScale.coerceIn(0.6f, 2.5f)).dp
  val totalWaveformWidth = cycleColWidth * simulation.cycleCount
  val q = signalSearchQuery.trim().lowercase(Locale.ROOT)
  val filteredTraces = simulation.traces.filter { trace ->
    val visOk = visibleSignals.isEmpty() || trace.name in visibleSignals
    val queryOk = q.isEmpty() || trace.name.lowercase(Locale.ROOT).contains(q)
    val groupOk = when (signalGroupFilter) {
      1 -> trace.direction == PortDirection.INPUT
      2 -> trace.direction == PortDirection.OUTPUT
      3 -> trace.isSequentialReg
      4 -> trace.isClock
      else -> true
    }
    visOk && queryOk && groupOk
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    shape = MaterialTheme.shapes.large,
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, outline.copy(alpha = 0.28f), MaterialTheme.shapes.large)
      .then(
        if (onZoomDelta != null) {
          Modifier.pointerInput(onZoomDelta) {
            awaitEachGesture {
              do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                  val zoom = event.calculateZoom()
                  if (zoom != 1f) {
                    onZoomDelta(zoom)
                    event.changes.forEach { it.consume() }
                  }
                }
              } while (event.changes.any { it.pressed })
            }
          }
        } else {
          Modifier
        }
      )
      .testTag("waveform_viewer_card"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Ruler Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .width(LABEL_COL_WIDTH)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
          Text(
            text = "Signal",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = onSurfaceVariant,
          )
        }

        Row(
          modifier = Modifier
            .weight(1f)
            .horizontalScroll(scrollState),
        ) {
          for (c in 0 until simulation.cycleCount) {
            val isSel = c == selectedCycle
            val isCurB = c == cursorBCycle
            val cellBg = when {
              isSel -> primary.copy(alpha = 0.22f)
              isCurB -> secondary.copy(alpha = 0.22f)
              else -> Color.Transparent
            }
            Box(
              modifier = Modifier
                .width(cycleColWidth)
                .background(cellBg)
                .pointerInput(c) {
                  detectTapGestures { onSelectCycle(c) }
                }
                .padding(vertical = 8.dp),
              contentAlignment = Alignment.Center,
            ) {
              val labelPrefix = when {
                isSel && isCurB -> "AB:"
                isSel && cursorBCycle != null -> "A:"
                isCurB -> "B:"
                else -> ""
              }
              Text(
                text = "${labelPrefix}C$c",
                style = TextStyle(
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 10.sp,
                  fontWeight = if (isSel || isCurB) FontWeight.Bold else FontWeight.Normal,
                  color = when {
                    isSel -> primary
                    isCurB -> secondary
                    else -> onSurfaceVariant
                  },
                ),
              )
            }
          }
        }
      }

      HorizontalDivider(color = outline.copy(alpha = 0.2f))

      // Signal Trace Rows
      filteredTraces.forEachIndexed { idx, trace ->
        val traceColor = when {
          trace.isClock -> secondary
          trace.direction == PortDirection.OUTPUT -> tertiary
          trace.direction == PortDirection.INPUT -> primary
          else -> onSurface
        }

        val isExpandableBus = trace.width > 1 && !trace.isClock
        val isExpanded = trace.name in expandedBusNames
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
              if (idx % 2 == 0) MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
              else Color.Transparent
            ),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Left Signal Name Rail (tap multi-bit buses to expand/collapse bit slices)
          Column(
            modifier = Modifier
              .width(LABEL_COL_WIDTH)
              .then(
                if (isExpandableBus) {
                  Modifier.clickable {
                    expandedBusNames = if (isExpanded) expandedBusNames - trace.name else expandedBusNames + trace.name
                  }
                } else {
                  Modifier
                }
              )
              .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              text = if (isExpandableBus) "${if (isExpanded) "▼ " else "▶ "}${trace.name}" else trace.name,
              style = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
              ),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val tag = buildString {
              append(
                when (trace.direction) {
                  PortDirection.INPUT -> "IN"
                  PortDirection.OUTPUT -> "OUT"
                  PortDirection.INOUT -> "IO"
                  null -> "SIG"
                }
              )
              if (trace.width > 1) append(" [${trace.width - 1}:0]")
            }
            Text(
              text = tag,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
              color = traceColor,
              maxLines = 1,
            )
          }

          // Right Waveform Canvas
          Box(
            modifier = Modifier
              .weight(1f)
              .horizontalScroll(scrollState),
          ) {
            Canvas(
              modifier = Modifier
                .width(totalWaveformWidth)
                .height(ROW_HEIGHT)
                .pointerInput(simulation.cycleCount) {
                  detectTapGestures { offset ->
                    val cycleW = size.width.toFloat() / simulation.cycleCount.coerceAtLeast(1)
                    val tapped = (offset.x / cycleW).toInt().coerceIn(0, simulation.cycleCount - 1)
                    onSelectCycle(tapped)
                  }
                }
                .testTag("waveform_row_${trace.name}"),
            ) {
              val cycleW = size.width / simulation.cycleCount.coerceAtLeast(1)
              val topY = 9.dp.toPx()
              val botY = size.height - 9.dp.toPx()

              // Selected cycle A vertical cursor band
              drawRect(
                color = primary.copy(alpha = 0.14f),
                topLeft = Offset(selectedCycle * cycleW, 0f),
                size = Size(cycleW, size.height),
              )

              // Optional Cursor B measurement band
              if (cursorBCycle != null && cursorBCycle in 0 until simulation.cycleCount) {
                drawRect(
                  color = secondary.copy(alpha = 0.14f),
                  topLeft = Offset(cursorBCycle * cycleW, 0f),
                  size = Size(cycleW, size.height),
                )
              }

              // Vertical cycle gridlines
              for (c in 0..simulation.cycleCount) {
                val gx = c * cycleW
                drawLine(
                  color = when (c) {
                    selectedCycle -> primary.copy(alpha = 0.65f)
                    cursorBCycle -> secondary.copy(alpha = 0.65f)
                    else -> outline.copy(alpha = 0.14f)
                  },
                  start = Offset(gx, 0f),
                  end = Offset(gx, size.height),
                  strokeWidth = if (c == selectedCycle || c == cursorBCycle) 1.8f else 1f,
                )
              }

              if (trace.isClock) {
                drawClockTrace(
                  cycles = simulation.cycleCount,
                  cycleW = cycleW,
                  topY = topY,
                  botY = botY,
                  color = traceColor,
                )
              } else if (trace.width == 1) {
                drawSingleBitTrace(
                  trace = trace,
                  cycleW = cycleW,
                  topY = topY,
                  botY = botY,
                  color = traceColor,
                )
              } else {
                drawBusTrace(
                  trace = trace,
                  radix = radix,
                  cycleW = cycleW,
                  topY = topY,
                  botY = botY,
                  color = traceColor,
                  textColor = onSurface,
                  textMeasurer = textMeasurer,
                )
              }
            }
          }
        }
        if (isExpandableBus && isExpanded) {
          val bitLimit = trace.width.coerceAtMost(8)
          for (bitIdx in (bitLimit - 1) downTo 0) {
            val bitTrace = SignalTrace(
              name = "${trace.name}[$bitIdx]",
              direction = trace.direction,
              width = 1,
              isClock = false,
              isReset = false,
              isSequentialReg = trace.isSequentialReg,
              values = trace.values.map { (it ushr bitIdx) and 1L },
            )
            HorizontalDivider(color = outline.copy(alpha = 0.08f))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(SUB_BIT_ROW_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier.width(LABEL_COL_WIDTH).padding(start = 18.dp, end = 8.dp),
              ) {
                Text(
                  text = bitTrace.name,
                  style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, color = onSurfaceVariant),
                  maxLines = 1,
                )
              }
              Box(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                Canvas(
                  modifier = Modifier
                    .width(totalWaveformWidth)
                    .height(SUB_BIT_ROW_HEIGHT)
                    .testTag("waveform_bit_${trace.name}_$bitIdx"),
                ) {
                  val cycleW = size.width / simulation.cycleCount.coerceAtLeast(1)
                  drawSingleBitTrace(
                    trace = bitTrace,
                    cycleW = cycleW,
                    topY = 6.dp.toPx(),
                    botY = size.height - 6.dp.toPx(),
                    color = traceColor.copy(alpha = 0.85f),
                  )
                }
              }
            }
          }
        }
        if (idx < filteredTraces.lastIndex) {
          HorizontalDivider(color = outline.copy(alpha = 0.12f))
        }
      }
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClockTrace(
  cycles: Int,
  cycleW: Float,
  topY: Float,
  botY: Float,
  color: Color,
) {
  val path = Path()
  path.moveTo(0f, botY)
  for (c in 0 until cycles) {
    val x0 = c * cycleW
    val xMid = x0 + cycleW * 0.5f
    val x1 = x0 + cycleW
    path.lineTo(x0, topY)
    path.lineTo(xMid, topY)
    path.lineTo(xMid, botY)
    path.lineTo(x1, botY)
  }
  drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSingleBitTrace(
  trace: SignalTrace,
  cycleW: Float,
  topY: Float,
  botY: Float,
  color: Color,
) {
  val path = Path()
  var prevY = if ((trace.values.firstOrNull() ?: 0L) != 0L) topY else botY
  path.moveTo(0f, prevY)

  trace.values.forEachIndexed { c, v ->
    val x0 = c * cycleW
    val x1 = x0 + cycleW
    val y = if ((v and 1L) != 0L) topY else botY
    if (y != prevY && c > 0) {
      path.lineTo(x0, y)
    }
    if ((v and 1L) != 0L) {
      drawRect(
        color = color.copy(alpha = 0.14f),
        topLeft = Offset(x0, topY),
        size = Size(cycleW, botY - topY),
      )
    }
    path.lineTo(x1, y)
    prevY = y
  }
  drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBusTrace(
  trace: SignalTrace,
  radix: WaveformRadix,
  cycleW: Float,
  topY: Float,
  botY: Float,
  color: Color,
  textColor: Color,
  textMeasurer: TextMeasurer,
) {
  val midY = (topY + botY) * 0.5f
  val bevel = (cycleW * 0.16f).coerceAtMost(6.dp.toPx())
  val values = trace.values
  if (values.isEmpty()) return

  // Group contiguous equal bus values into clean merged diamonds
  var startIdx = 0
  while (startIdx < values.size) {
    var endIdx = startIdx
    while (endIdx + 1 < values.size && values[endIdx + 1] == values[startIdx]) {
      endIdx++
    }
    val xStart = startIdx * cycleW
    val xEnd = (endIdx + 1) * cycleW
    val leftBevel = if (startIdx == 0) 0f else bevel
    val rightBevel = if (endIdx == values.lastIndex) 0f else bevel

    val busPath = Path().apply {
      moveTo(xStart, midY)
      lineTo(xStart + leftBevel, topY)
      lineTo(xEnd - rightBevel, topY)
      lineTo(xEnd, midY)
      lineTo(xEnd - rightBevel, botY)
      lineTo(xStart + leftBevel, botY)
      close()
    }
    drawPath(path = busPath, color = color.copy(alpha = 0.10f))
    drawPath(path = busPath, color = color, style = Stroke(width = 1.6.dp.toPx()))

    val label = trace.formatValue(startIdx, radix)
    val availW = (xEnd - xStart - leftBevel - rightBevel - 4.dp.toPx()).coerceAtLeast(0f)
    if (availW >= 18f) {
      val measured = textMeasurer.measure(
        text = label,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 9.5.sp,
          fontWeight = FontWeight.Medium,
          color = textColor,
        ),
        maxLines = 1,
        overflow = TextOverflow.Clip,
      )
      if (measured.size.width <= availW + 4f) {
        drawText(
          textLayoutResult = measured,
          topLeft = Offset(
            x = xStart + ((xEnd - xStart) - measured.size.width) * 0.5f,
            y = midY - measured.size.height * 0.5f,
          ),
        )
      }
    }
    startIdx = endIdx + 1
  }
}
