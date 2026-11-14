package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalEdaColors

@Composable
fun EdaSurfaceCard(
  modifier: Modifier = Modifier,
  elevated: Boolean = false,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val eda = LocalEdaColors.current
  val container = if (elevated) eda.elevatedCard else MaterialTheme.colorScheme.surface
  val border = BorderStroke(
    width = 1.dp,
    color = eda.subtleBorder.copy(alpha = if (eda.isDark) 0.75f else 0.92f),
  )
  val cardShadow = if (eda.isDark || elevated) 0.dp else 1.dp
  if (onClick != null) {
    Surface(
      onClick = onClick,
      color = container,
      shape = MaterialTheme.shapes.medium,
      border = border,
      shadowElevation = cardShadow,
      modifier = modifier,
    ) {
      Column(content = content)
    }
  } else {
    Surface(
      color = container,
      shape = MaterialTheme.shapes.medium,
      border = border,
      shadowElevation = cardShadow,
      modifier = modifier,
    ) {
      Column(content = content)
    }
  }
}

@Composable
fun EdaSectionHeader(
  title: String,
  subtitle: String? = null,
  trailing: (@Composable () -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (trailing != null) {
      trailing()
    }
  }
}

@Composable
fun EdaStatusPill(
  text: String,
  color: Color,
  containerColor: Color,
  showDot: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.extraSmall,
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (showDot) {
        Box(
          modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
        )
        Spacer(Modifier.width(5.dp))
      }
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Medium,
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun EdaCompactMetric(
  label: String,
  value: String,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
  modifier: Modifier = Modifier,
) {
  val eda = LocalEdaColors.current
  Surface(
    color = eda.elevatedCard,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, eda.subtleBorder.copy(alpha = if (eda.isDark) 0.65f else 0.88f)),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium.copy(
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.SemiBold,
        ),
        color = valueColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
