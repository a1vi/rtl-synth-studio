package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = CyanPrimaryDark,
    onPrimary = CyanOnPrimaryDark,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = CyanOnContainerDark,
    secondary = AmberSecondaryDark,
    onSecondary = AmberOnSecondaryDark,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = AmberOnContainerDark,
    tertiary = EmeraldPassDark,
    onTertiary = CyanOnPrimaryDark,
    tertiaryContainer = EmeraldContainerDark,
    onTertiaryContainer = EmeraldPassDark,
    background = SiliconBgDark,
    onBackground = TextPrimaryDark,
    surface = SiliconSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SiliconSurfaceElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = SiliconOutlineDark,
    error = ErrorCoralDark,
    errorContainer = ErrorContainerDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CyanPrimaryLight,
    onPrimary = CyanOnPrimaryLight,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = CyanOnContainerLight,
    secondary = AmberSecondaryLight,
    onSecondary = AmberOnSecondaryLight,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = AmberOnContainerLight,
    tertiary = EmeraldPassLight,
    onTertiary = CyanOnPrimaryLight,
    background = BlueprintBgLight,
    onBackground = TextPrimaryLight,
    surface = BlueprintSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = BlueprintSurfaceElevatedLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BlueprintOutlineLight,
    error = ErrorCoralLight,
  )

val SiliconShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(18.dp),
  extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun RtlSynthTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = SiliconShapes,
    content = content,
  )
}
