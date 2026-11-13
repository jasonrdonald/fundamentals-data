package com.fundamentalanalysis.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4F8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF00696B),
    background = Color(0xFFFCFCFB),
    surface = Color(0xFFFCFCFB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF004788),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBCC7DC),
    tertiary = Color(0xFF4CDADA),
    background = Color(0xFF1A1A19),
    surface = Color(0xFF1A1A19),
)

/**
 * Chart colors are fixed (not dynamic) so a series keeps its identity everywhere.
 * Categorical slots 1-3 are a CVD-validated set, stepped separately for light and dark surfaces.
 */
@Immutable
data class ChartColors(
    val series: List<Color>,
    /** Gain/loss: blue/red rather than green/red so the pair survives red-green colour blindness. */
    val gain: Color,
    val loss: Color,
    val grid: Color,
    /** Heat-table cell tint opacity, weakest to strongest; dark surfaces need a wider range. */
    val heatMin: Float = 0.06f,
    val heatMax: Float = 0.40f,
) {
    /** Tint opacity for a magnitude in [0, 1]. */
    fun heatAlpha(fraction: Double): Float = heatMin + (heatMax - heatMin) * fraction.toFloat().coerceIn(0f, 1f)
}

private val LightChart = ChartColors(
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    gain = Color(0xFF1F63B5),
    loss = Color(0xFFC93A39),
    grid = Color(0x1F000000),
)

private val DarkChart = ChartColors(
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    gain = Color(0xFF6AA6F0),
    loss = Color(0xFFF07C7B),
    grid = Color(0x29FFFFFF),
    heatMin = 0.10f,
    heatMax = 0.70f,
)

val LocalChartColors = staticCompositionLocalOf { LightChart }

@Composable
fun FundamentalsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) DarkChart else LightChart,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
