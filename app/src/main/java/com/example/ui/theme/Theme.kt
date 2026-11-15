package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KindGuideColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = WarmSurface,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TerracottaOnContainer,
    secondary = SageSuccess,
    onSecondary = WarmSurface,
    secondaryContainer = SageContainer,
    onSecondaryContainer = SageOnContainer,
    tertiary = AmberCaution,
    onTertiary = WarmSurface,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberOnContainer,
    error = CrimsonSos,
    onError = WarmSurface,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonOnContainer,
    background = WarmIvory,
    onBackground = EspressoInk,
    surface = WarmSurface,
    onSurface = EspressoInk,
    surfaceVariant = WarmSand,
    onSurfaceVariant = WarmSlate,
    outline = WarmBorder,
    outlineVariant = WarmBorder
)

val KindGuideShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun KindGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KindGuideColorScheme,
        typography = Typography,
        shapes = KindGuideShapes,
        content = content
    )
}
