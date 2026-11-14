package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class EdaSemanticColors(
  val isDark: Boolean,
  val codeSurface: Color,
  val elevatedCard: Color,
  val subtleBorder: Color,
  val cyanHighlight: Color,
  val cyanContainer: Color,
  val success: Color,
  val successContainer: Color,
  val warning: Color,
  val warningContainer: Color,
  val error: Color,
  val errorContainer: Color,
)

private val DarkEdaSemantics = EdaSemanticColors(
  isDark = true,
  codeSurface = IndustrialCodeBgDark,
  elevatedCard = IndustrialCardDark,
  subtleBorder = IndustrialOutlineDark,
  cyanHighlight = CyanHighlightDark,
  cyanContainer = CyanContainerDark,
  success = EmeraldSuccessDark,
  successContainer = EmeraldContainerDark,
  warning = AmberWarningDark,
  warningContainer = AmberContainerDark,
  error = RedErrorDark,
  errorContainer = RedContainerDark,
)

private val LightEdaSemantics = EdaSemanticColors(
  isDark = false,
  codeSurface = IndustrialCodeBgLight,
  elevatedCard = IndustrialCardLight,
  subtleBorder = IndustrialOutlineLight,
  cyanHighlight = CyanHighlightLight,
  cyanContainer = CyanContainerLight,
  success = EmeraldSuccessLight,
  successContainer = EmeraldContainerLight,
  warning = AmberWarningLight,
  warningContainer = AmberContainerLight,
  error = RedErrorLight,
  errorContainer = RedContainerLight,
)

val LocalEdaColors = staticCompositionLocalOf { DarkEdaSemantics }

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricBlueDark,
    onPrimary = ElectricBlueOnDark,
    primaryContainer = ElectricBlueContainerDark,
    onPrimaryContainer = ElectricBlueOnContainerDark,
    secondary = CyanHighlightDark,
    onSecondary = CyanOnDark,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = CyanOnContainerDark,
    tertiary = EmeraldSuccessDark,
    onTertiary = ElectricBlueOnDark,
    tertiaryContainer = EmeraldContainerDark,
    onTertiaryContainer = EmeraldSuccessDark,
    background = IndustrialBgDark,
    onBackground = TextPrimaryDark,
    surface = IndustrialSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = IndustrialCardDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = IndustrialCodeBgDark,
    surfaceContainerLow = IndustrialBgDark,
    surfaceContainer = IndustrialSurfaceDark,
    surfaceContainerHigh = IndustrialCardDark,
    surfaceContainerHighest = Color(0xFF1E293B),
    outline = IndustrialOutlineDark,
    outlineVariant = IndustrialOutlineDark.copy(alpha = 0.65f),
    error = RedErrorDark,
    onError = ElectricBlueOnDark,
    errorContainer = RedContainerDark,
    onErrorContainer = RedOnContainerDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ElectricBlueLight,
    onPrimary = ElectricBlueOnLight,
    primaryContainer = ElectricBlueContainerLight,
    onPrimaryContainer = ElectricBlueOnContainerLight,
    secondary = CyanHighlightLight,
    onSecondary = CyanOnLight,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = CyanOnContainerLight,
    tertiary = EmeraldSuccessLight,
    onTertiary = ElectricBlueOnLight,
    tertiaryContainer = EmeraldContainerLight,
    onTertiaryContainer = EmeraldOnContainerLight,
    background = IndustrialBgLight,
    onBackground = TextPrimaryLight,
    surface = IndustrialSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = IndustrialCardLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = IndustrialSurfaceLight,
    surfaceContainerLow = IndustrialCardLight,
    surfaceContainer = IndustrialBgLight,
    surfaceContainerHigh = IndustrialSurfaceHighLight,
    surfaceContainerHighest = Color(0xFFDCE4EF),
    outline = IndustrialOutlineLight,
    outlineVariant = IndustrialOutlineLight.copy(alpha = 0.85f),
    error = RedErrorLight,
    onError = ElectricBlueOnLight,
    errorContainer = RedContainerLight,
    onErrorContainer = RedOnContainerLight,
  )

val IndustrialShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(14.dp),
  extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun RtlSynthTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val semanticColors = if (darkTheme) DarkEdaSemantics else LightEdaSemantics

  CompositionLocalProvider(LocalEdaColors provides semanticColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      shapes = IndustrialShapes,
      content = content,
    )
  }
}
