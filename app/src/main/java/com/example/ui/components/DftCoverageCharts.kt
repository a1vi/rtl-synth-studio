package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.synth.AtpgTestVector
import com.example.domain.synth.DftReport
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

data class CoverageRampPoint(
  val vectorIndex: Int,
  val cumulativeDetected: Int,
  val testCoveragePct: Double,
  val stuckAtCoveragePct: Double,
)

fun computeCoverageRamp(
  vectors: List<AtpgTestVector>,
  totalFaults: Int,
  untestableFaults: Int,
): List<CoverageRampPoint> {
  if (vectors.isEmpty() || totalFaults <= 0) return emptyList()
  val testable = (totalFaults - untestableFaults).coerceAtLeast(1)
  var cumDet = 0
  return vectors.map { vec ->
    cumDet = (cumDet + vec.newlyDetectedFaults).coerceAtMost(totalFaults)
    CoverageRampPoint(
      vectorIndex = vec.index,
      cumulativeDetected = cumDet,
      testCoveragePct = (cumDet * 100.0 / testable).coerceIn(0.0, 100.0),
      stuckAtCoveragePct = (cumDet * 100.0 / totalFaults).coerceIn(0.0, 100.0),
    )
  }
}

@Composable
fun TestCoverageVisualizationSection(
  dft: DftReport,
  onEnableScanInsertion: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val undetectedFaults = (dft.totalFaults - dft.detectedFaults - dft.untestableFaults).coerceAtLeast(0)
  val testCovPct = dft.testCoveragePct.coerceIn(0.0, 100.0)
  val stuckAtPct = dft.stuckAtCoveragePct.coerceIn(0.0, 100.0)

  val covColor = when {
    testCovPct >= 90.0 -> eda.success
    testCovPct >= 75.0 -> eda.cyanHighlight
    else -> eda.warning
  }
  val covContainer = when {
    testCovPct >= 90.0 -> eda.successContainer
    testCovPct >= 75.0 -> eda.cyanContainer
    else -> eda.warningContainer
  }
  val tierLabel = when {
    testCovPct >= 90.0 -> "SIGNOFF PASS (>=90%)"
    testCovPct >= 75.0 -> "ACCEPTABLE (75-89%)"
    else -> "LOW COVERAGE (<75%)"
  }

  val animatedSweepFraction by animateFloatAsState(
    targetValue = (testCovPct / 100.0).toFloat().coerceIn(0f, 1f),
    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
    label = "dft_test_coverage_arc",
  )

  Column(
    modifier = modifier.fillMaxWidth().testTag("dft_test_coverage_viz"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Radial Arc Gauge + Key Coverage Comparison
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val trackColor = eda.subtleBorder.copy(alpha = 0.35f)
      val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
      val arcSecondary = eda.cyanHighlight

      Box(
        modifier = Modifier
          .size(124.dp)
          .testTag("dft_coverage_gauge_canvas"),
        contentAlignment = Alignment.Center,
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val strokePx = 11.dp.toPx()
          val pad = strokePx / 2f + 4.dp.toPx()
          val arcSize = Size(size.width - pad * 2f, size.height - pad * 2f)
          val topLeft = Offset(pad, pad)
          val startAngle = 150f
          val maxSweep = 240f

          // Background track
          drawArc(
            color = trackColor,
            startAngle = startAngle,
            sweepAngle = maxSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
          )

          // Active Test Coverage sweep gradient arc
          if (animatedSweepFraction > 0.005f) {
            drawArc(
              brush = Brush.sweepGradient(
                colors = listOf(arcSecondary, covColor, covColor),
              ),
              startAngle = startAngle,
              sweepAngle = maxSweep * animatedSweepFraction,
              useCenter = false,
              topLeft = topLeft,
              size = arcSize,
              style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
          }

          // 90% Signoff Threshold Tick Mark
          val thresholdAngleRad = Math.toRadians((startAngle + maxSweep * 0.90).toDouble())
          val cx = size.width / 2f
          val cy = size.height / 2f
          val outerR = arcSize.width / 2f + strokePx * 0.6f
          val innerR = arcSize.width / 2f - strokePx * 0.6f
          drawLine(
            color = markerColor,
            start = Offset(
              cx + (innerR * cos(thresholdAngleRad)).toFloat(),
              cy + (innerR * sin(thresholdAngleRad)).toFloat(),
            ),
            end = Offset(
              cx + (outerR * cos(thresholdAngleRad)).toFloat(),
              cy + (outerR * sin(thresholdAngleRad)).toFloat(),
            ),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "${"%.1f".format(Locale.US, testCovPct)}%",
            style = MaterialTheme.typography.titleLarge.copy(
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
            ),
            color = covColor,
            modifier = Modifier.testTag("dft_test_coverage_value"),
          )
          Text(
            text = "TEST COV",
            style = MaterialTheme.typography.labelSmall.copy(
              fontSize = 10.sp,
              fontFamily = JetBrainsMonoFamily,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        EdaStatusPill(
          text = tierLabel,
          color = covColor,
          containerColor = covContainer,
          showDot = true,
          modifier = Modifier.testTag("dft_coverage_tier_pill"),
        )

        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          CoverageMetricLine("Test Coverage", "${"%.1f".format(Locale.US, testCovPct)}%", covColor)
          CoverageMetricLine("Stuck-At Cov", "${"%.1f".format(Locale.US, stuckAtPct)}%", eda.cyanHighlight)
          CoverageMetricLine(
            "Signoff Goal",
            if (testCovPct >= 90.0) "MET (>=90%)" else "-${"%.1f".format(Locale.US, 90.0 - testCovPct)}%",
            if (testCovPct >= 90.0) eda.success else eda.warning,
          )
        }
      }
    }

    // Stacked Fault Population Breakdown Bar (Detected vs Undetected vs Untestable)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("Fault Population Breakdown", style = MaterialTheme.typography.labelMedium)
        Text(
          text = "${dft.totalFaults} collapsed SA0/SA1 faults",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      val totalF = dft.totalFaults.coerceAtLeast(1).toFloat()
      val detFrac = dft.detectedFaults / totalF
      val undetFrac = undetectedFaults / totalF
      val untestColor = MaterialTheme.colorScheme.outlineVariant

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(12.dp)
          .clip(MaterialTheme.shapes.small)
          .testTag("dft_fault_breakdown_bar"),
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
          drawRoundRect(color = untestColor.copy(alpha = 0.55f), cornerRadius = r)

          val detW = w * detFrac
          if (detW > 0f) {
            drawRect(color = eda.success, topLeft = Offset.Zero, size = Size(detW, h))
          }
          val undetW = w * undetFrac
          if (undetW > 0f) {
            drawRect(color = eda.warning, topLeft = Offset(detW, 0f), size = Size(undetW, h))
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        CoverageLegendDot(eda.success, "Det ${dft.detectedFaults}")
        CoverageLegendDot(eda.warning, "Undet $undetectedFaults")
        CoverageLegendDot(untestColor, "Untest ${dft.untestableFaults}")
      }
    }

    // Call to action when sequential FFs exist but Scan Chain Insertion is disabled
    if (!dft.scanInserted && dft.totalSeqElements > 0) {
      Surface(
        color = eda.warningContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, eda.warning.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "${dft.untestableFaults} fault(s) shadowed behind ${dft.totalSeqElements} non-scan FF(s). Insert scan chains to make sequential nodes controllable/observable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Button(
            onClick = onEnableScanInsertion,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("dft_enable_scan_boost_button"),
          ) {
            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Insert Scan Chains & Re-Run ATPG", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    } else if (dft.scanInserted && testCovPct >= 90.0) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = eda.success, modifier = Modifier.size(16.dp))
        Text(
          text = "High fault observability verified across ${dft.scanChains.size} stitched scan chain(s).",
          style = MaterialTheme.typography.bodySmall,
          color = eda.success,
        )
      }
    }
  }
}

@Composable
fun AtpgCoverageRampCard(
  dft: DftReport,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  val ramp = remember(dft.atpgVectors, dft.totalFaults, dft.untestableFaults) {
    computeCoverageRamp(dft.atpgVectors, dft.totalFaults, dft.untestableFaults)
  }

  EdaSurfaceCard(modifier = modifier.fillMaxWidth().testTag("dft_coverage_curve_card")) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        EdaSectionHeader(
          title = "ATPG Coverage Growth",
          subtitle = "Cumulative Test vs Stuck-At coverage across vectors",
          modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
          contentDescription = null,
          tint = eda.cyanHighlight,
          modifier = Modifier.size(20.dp),
        )
      }

      if (ramp.isEmpty()) {
        Text(
          text = "No ATPG patterns simulated yet. Run synthesis or tap Run ATPG to plot fault detection.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        val testLineColor = eda.success
        val saLineColor = eda.cyanHighlight
        val gridColor = eda.subtleBorder.copy(alpha = 0.45f)
        val signoffColor = eda.warning.copy(alpha = 0.85f)

        Surface(
          color = eda.codeSurface.copy(alpha = 0.7f),
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .testTag("dft_coverage_curve_canvas"),
            ) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 8.dp.toPx()
                val rightPad = 8.dp.toPx()
                val topPad = 10.dp.toPx()
                val bottomPad = 10.dp.toPx()
                val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                fun yForPct(pct: Double): Float {
                  val clamped = (pct / 100.0).toFloat().coerceIn(0f, 1f)
                  return topPad + plotH * (1f - clamped)
                }

                // Horizontal reference gridlines (0%, 50%, 100%)
                listOf(0.0, 50.0, 100.0).forEach { level ->
                  val y = yForPct(level)
                  drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 1.dp.toPx(),
                  )
                }

                // 90% Signoff Threshold dashed line
                val y90 = yForPct(90.0)
                drawLine(
                  color = signoffColor,
                  start = Offset(leftPad, y90),
                  end = Offset(leftPad + plotW, y90),
                  strokeWidth = 1.5.dp.toPx(),
                  pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                )

                val n = ramp.size
                fun xForIdx(i: Int): Float = if (n <= 1) {
                  leftPad + plotW * 0.5f
                } else {
                  leftPad + (i.toFloat() / (n - 1).toFloat()) * plotW
                }

                // Build Stuck-At path & Test Coverage path + filled area
                val testPath = Path()
                val fillPath = Path()
                val saPath = Path()

                ramp.forEachIndexed { idx, pt ->
                  val x = xForIdx(idx)
                  val yt = yForPct(pt.testCoveragePct)
                  val ys = yForPct(pt.stuckAtCoveragePct)
                  if (idx == 0) {
                    testPath.moveTo(x, yt)
                    fillPath.moveTo(x, topPad + plotH)
                    fillPath.lineTo(x, yt)
                    saPath.moveTo(x, ys)
                  } else {
                    testPath.lineTo(x, yt)
                    fillPath.lineTo(x, yt)
                    saPath.lineTo(x, ys)
                  }
                  if (idx == n - 1) {
                    fillPath.lineTo(x, topPad + plotH)
                    fillPath.close()
                  }
                }

                if (n > 1) {
                  drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                      colors = listOf(testLineColor.copy(alpha = 0.28f), Color.Transparent),
                      startY = topPad,
                      endY = topPad + plotH,
                    ),
                  )
                  drawPath(
                    path = saPath,
                    color = saLineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                  )
                  drawPath(
                    path = testPath,
                    color = testLineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                  )
                }

                ramp.forEachIndexed { idx, pt ->
                  val x = xForIdx(idx)
                  drawCircle(color = saLineColor, radius = 3.dp.toPx(), center = Offset(x, yForPct(pt.stuckAtCoveragePct)))
                  drawCircle(color = testLineColor, radius = 3.5.dp.toPx(), center = Offset(x, yForPct(pt.testCoveragePct)))
                }
              }
            }

            HorizontalDivider(color = eda.subtleBorder.copy(alpha = 0.45f))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CoverageLegendDot(eda.success, "Test Cov (${"%.1f".format(Locale.US, ramp.last().testCoveragePct)}%)")
              CoverageLegendDot(eda.cyanHighlight, "Stuck-At (${"%.1f".format(Locale.US, ramp.last().stuckAtCoveragePct)}%)")
              CoverageLegendDot(eda.warning, "90% Goal")
            }

            val first = ramp.first()
            val last = ramp.last()
            Text(
              text = "V0: ${"%.1f".format(Locale.US, first.testCoveragePct)}% -> V${last.vectorIndex}: ${"%.1f".format(Locale.US, last.testCoveragePct)}% (+${last.cumulativeDetected}/${dft.totalFaults} faults)",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CoverageMetricLine(label: String, value: String, valueColor: Color) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.SemiBold,
      ),
      color = valueColor,
    )
  }
}

@Composable
private fun CoverageLegendDot(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
