package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.AreaAnalysisSummary
import com.example.domain.synth.ResourceAreaBucket
import com.example.domain.synth.ResourceAreaSlice
import com.example.ui.screens.analysis.formatAreaMetric
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

private data class DonutArcGeometry(
  val slice: ResourceAreaSlice,
  val startAngleDeg: Float,
  val sweepAngleDeg: Float,
  val color: Color,
)

@Composable
fun AreaDistributionDonutCard(
  summary: AreaAnalysisSummary,
  isFpga: Boolean,
  onLocateBucketInSchematic: ((Set<String>) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val slices = summary.resourceSlices
  val totalBucketArea = remember(slices) { slices.sumOf { it.areaUm2 } }
  val dspSlice = remember(slices) { slices.firstOrNull { it.bucket == ResourceAreaBucket.DSPS } }

  var selectedBucket by rememberSaveable { mutableStateOf<ResourceAreaBucket?>(null) }
  val selectedSlice = remember(slices, selectedBucket) {
    slices.firstOrNull { it.bucket == selectedBucket }
  }

  val lutsColor = MaterialTheme.colorScheme.primary
  val ffColor = eda.cyanHighlight
  val dspColor = eda.warning

  fun bucketColor(bucket: ResourceAreaBucket): Color = when (bucket) {
    ResourceAreaBucket.LUTS -> lutsColor
    ResourceAreaBucket.FLIP_FLOPS -> ffColor
    ResourceAreaBucket.DSPS -> dspColor
  }

  val nonZeroSlices = remember(slices) { slices.filter { it.areaUm2 > 1e-9 } }
  val arcGapDeg = if (nonZeroSlices.size > 1) 2.4f else 0f

  val arcGeometries = remember(slices, totalBucketArea, lutsColor, ffColor, dspColor) {
    if (totalBucketArea <= 1e-9) {
      emptyList()
    } else {
      var cursor = -90f
      nonZeroSlices.map { slice ->
        val rawSweep = ((slice.areaUm2 / totalBucketArea) * 360.0).toFloat()
        val drawnSweep = (rawSweep - arcGapDeg).coerceAtLeast(1.5f)
        val geom = DonutArcGeometry(
          slice = slice,
          startAngleDeg = cursor + arcGapDeg / 2f,
          sweepAngleDeg = drawnSweep,
          color = when (slice.bucket) {
            ResourceAreaBucket.LUTS -> lutsColor
            ResourceAreaBucket.FLIP_FLOPS -> ffColor
            ResourceAreaBucket.DSPS -> dspColor
          },
        )
        cursor += rawSweep
        geom
      }
    }
  }

  val animProgress by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
    label = "area_donut_sweep",
  )

  val subtitle = if (isFpga) {
    "LUTs vs. Flip-Flops vs. DSPs · Slice units"
  } else {
    "LUT-equiv Comb vs. Flip-Flops vs. DSP/Mul · µm²"
  }

  EdaSurfaceCard(modifier = modifier.fillMaxWidth().testTag("area_resource_donut_card")) {
    Column {
      EdaPanelHeader(
        title = "Resource Area Donut",
        subtitle = subtitle,
        icon = Icons.Rounded.DonutLarge,
        trailing = {
          val dspCount = dspSlice?.cellCount ?: 0
          EdaStatusPill(
            text = if (dspCount > 0) "$dspCount DSP" else "0 DSP",
            color = if (dspCount > 0) eda.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = if (dspCount > 0) eda.warningContainer else eda.elevatedCard,
          )
        },
      )

      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
          val wideEnough = maxWidth >= 350.dp
          if (wideEnough) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              DonutCanvasRing(
                arcGeometries = arcGeometries,
                animProgress = animProgress,
                totalBucketArea = totalBucketArea,
                totalCellCount = summary.totalCellCount,
                selectedSlice = selectedSlice,
                isFpga = isFpga,
                onSliceToggle = { bucket ->
                  selectedBucket = if (selectedBucket == bucket) null else bucket
                },
                modifier = Modifier.size(152.dp),
              )
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                slices.forEach { slice ->
                  DonutLegendRow(
                    slice = slice,
                    color = bucketColor(slice.bucket),
                    isSelected = selectedBucket == slice.bucket,
                    isFpga = isFpga,
                    onClick = {
                      selectedBucket = if (selectedBucket == slice.bucket) null else slice.bucket
                    },
                  )
                }
              }
            }
          } else {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              DonutCanvasRing(
                arcGeometries = arcGeometries,
                animProgress = animProgress,
                totalBucketArea = totalBucketArea,
                totalCellCount = summary.totalCellCount,
                selectedSlice = selectedSlice,
                isFpga = isFpga,
                onSliceToggle = { bucket ->
                  selectedBucket = if (selectedBucket == bucket) null else bucket
                },
                modifier = Modifier.size(152.dp),
              )
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                slices.forEach { slice ->
                  DonutLegendRow(
                    slice = slice,
                    color = bucketColor(slice.bucket),
                    isSelected = selectedBucket == slice.bucket,
                    isFpga = isFpga,
                    onClick = {
                      selectedBucket = if (selectedBucket == slice.bucket) null else slice.bucket
                    },
                  )
                }
              }
            }
          }
        }

        AnimatedVisibility(visible = selectedSlice != null) {
          val active = selectedSlice ?: return@AnimatedVisibility
          val activeColor = bucketColor(active.bucket)
          Surface(
            color = eda.elevatedCard,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth().testTag("area_donut_selected_detail"),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Box(
                    modifier = Modifier
                      .size(9.dp)
                      .clip(CircleShape)
                      .background(activeColor),
                  )
                  Text(
                    text = "${active.label} · ${active.cellCount} cell(s)",
                    style = MaterialTheme.typography.labelMedium.copy(
                      fontFamily = JetBrainsMonoFamily,
                      fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
                Text(
                  text = "${formatAreaMetric(active.areaUm2, isFpga)} (${"%.1f".format(Locale.US, active.areaPct)}%)",
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                  color = activeColor,
                )
              }

              Text(
                text = active.cellTypesSummary,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )

              if (onLocateBucketInSchematic != null && active.instanceNodeIds.isNotEmpty()) {
                OutlinedButton(
                  onClick = { onLocateBucketInSchematic(active.instanceNodeIds.toSet()) },
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("area_donut_locate_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.AccountTree,
                    contentDescription = null,
                    tint = eda.cyanHighlight,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    text = "Locate ${active.label} in Schematic (${active.instanceNodeIds.size})",
                    style = MaterialTheme.typography.labelMedium,
                  )
                }
              }
            }
          }
        }

        if (selectedSlice == null) {
          HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.4f))
          Text(
            text = "Tap any slice or legend row to inspect mapped primitives and area share.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun DonutCanvasRing(
  arcGeometries: List<DonutArcGeometry>,
  animProgress: Float,
  totalBucketArea: Double,
  totalCellCount: Int,
  selectedSlice: ResourceAreaSlice?,
  isFpga: Boolean,
  onSliceToggle: (ResourceAreaBucket) -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val trackColor = eda.subtleBorder.copy(alpha = 0.35f)
  val selectedBucket = selectedSlice?.bucket

  Box(
    modifier = modifier
      .clipToBounds()
      .testTag("area_resource_donut_canvas")
      .semantics {
        contentDescription = if (totalBucketArea <= 1e-9) {
          "Resource area donut chart showing no synthesized area data"
        } else {
          "Resource area donut chart showing LUTs, Flip-Flops, and DSPs area distribution"
        }
      }
      .pointerInput(arcGeometries) {
        detectTapGestures { offset ->
          if (arcGeometries.isEmpty()) return@detectTapGestures
          val cx = size.width / 2f
          val cy = size.height / 2f
          val dx = offset.x - cx
          val dy = offset.y - cy
          val dist = hypot(dx, dy)
          val outerRadius = minOf(size.width, size.height) / 2f
          val innerRadius = outerRadius * 0.42f
          if (dist < innerRadius || dist > outerRadius) return@detectTapGestures

          var deg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
          if (deg < -90f) deg += 360f
          val hit = arcGeometries.firstOrNull { geom ->
            val end = geom.startAngleDeg + geom.sweepAngleDeg
            deg >= geom.startAngleDeg && deg <= end
          }
          if (hit != null) {
            onSliceToggle(hit.slice.bucket)
          }
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val baseStrokePx = 22.dp.toPx()
      val selectedStrokePx = 27.dp.toPx()
      val pad = selectedStrokePx / 2f + 4.dp.toPx()
      val arcSize = Size(
        width = (size.width - pad * 2f).coerceAtLeast(10f),
        height = (size.height - pad * 2f).coerceAtLeast(10f),
      )
      val topLeft = Offset(pad, pad)

      // Base ring track
      drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = baseStrokePx),
      )

      if (arcGeometries.isNotEmpty()) {
        arcGeometries.forEach { geom ->
          val isSelected = selectedBucket == geom.slice.bucket
          val isDimmed = selectedBucket != null && !isSelected
          val strokeWidth = if (isSelected) selectedStrokePx else baseStrokePx
          val drawColor = if (isDimmed) geom.color.copy(alpha = 0.42f) else geom.color
          val sweep = geom.sweepAngleDeg * animProgress
          if (sweep > 0.2f) {
            drawArc(
              color = drawColor,
              startAngle = geom.startAngleDeg,
              sweepAngle = sweep,
              useCenter = false,
              topLeft = topLeft,
              size = arcSize,
              style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
          }
        }
      }
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(horizontal = 24.dp),
    ) {
      if (totalBucketArea <= 1e-9) {
        Text(
          text = "N/A",
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("area_donut_center_value"),
        )
        Text(
          text = "NO AREA DATA",
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontFamily = JetBrainsMonoFamily,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      } else if (selectedSlice != null) {
        Text(
          text = "${"%.1f".format(Locale.US, selectedSlice.areaPct)}%",
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          modifier = Modifier.testTag("area_donut_center_value"),
        )
        Text(
          text = selectedSlice.bucket.shortLabel,
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          text = formatAreaMetric(selectedSlice.areaUm2, isFpga),
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontFamily = JetBrainsMonoFamily,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Text(
          text = if (isFpga) "${"%.0f".format(Locale.US, totalBucketArea)}" else "${"%.1f".format(Locale.US, totalBucketArea)}",
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          modifier = Modifier.testTag("area_donut_center_value"),
        )
        Text(
          text = if (isFpga) "SLICE UNITS" else "TOTAL µm²",
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontFamily = JetBrainsMonoFamily,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          text = "$totalCellCount CELLS",
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontFamily = JetBrainsMonoFamily,
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun DonutLegendRow(
  slice: ResourceAreaSlice,
  color: Color,
  isSelected: Boolean,
  isFpga: Boolean,
  onClick: () -> Unit,
) {
  val eda = LocalEdaColors.current
  val tagSuffix = slice.bucket.name.lowercase(Locale.ROOT)
  Surface(
    color = if (isSelected) color.copy(alpha = 0.14f) else eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(
      width = if (isSelected) 1.5.dp else 1.dp,
      color = if (isSelected) color else eda.subtleBorder.copy(alpha = 0.45f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clip(MaterialTheme.shapes.small)
      .clickable(
        role = Role.Button,
        onClickLabel = "Inspect ${slice.label} area",
        onClick = onClick,
      )
      .testTag("area_donut_legend_$tagSuffix"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
        )
        Column {
          Text(
            text = slice.label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${slice.cellCount} cells",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }

      Column(horizontalAlignment = Alignment.End) {
        Text(
          text = "${"%.1f".format(Locale.US, slice.areaPct)}%",
          style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = if (slice.cellCount > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          text = formatAreaMetric(slice.areaUm2, isFpga),
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}
