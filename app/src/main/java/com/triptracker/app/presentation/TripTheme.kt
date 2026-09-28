package com.triptracker.app.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF14263D)
private val Muted = Color(0xFF526477)
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
        lineHeight = line.sp, letterSpacing = spacing.sp)

@Composable
fun TripTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF006C67), onPrimary = Color.White,
            primaryContainer = Color(0xFFE0F3EE), onPrimaryContainer = Color(0xFF00534E),
            secondary = Ink, onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5EDF5), onSecondaryContainer = Ink,
            background = Color(0xFFF2F5F9), onBackground = Ink,
            surface = Color.White, onSurface = Ink, onSurfaceVariant = Muted,
            surfaceVariant = Color(0xFFEDF2F7), surfaceContainer = Color.White,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFC),
            surfaceContainerHigh = Color(0xFFEDF2F7), surfaceContainerHighest = Color(0xFFE4EBF2),
            outline = Color(0xFF8C9DAE), outlineVariant = Color(0xFFD6DFE8),
            error = Color(0xFFAF283A), onError = Color.White,
            errorContainer = Color(0xFFFFECEE), onErrorContainer = Color(0xFF851C2A),
        ),
        typography = Typography(
            displayLarge = type(48, 56, FontWeight.SemiBold, -1.5),
            headlineLarge = type(32, 40, FontWeight.SemiBold, -0.8),
            headlineMedium = type(28, 36, FontWeight.SemiBold, -0.6),
            headlineSmall = type(25, 34, FontWeight.Bold, -0.3),
            titleLarge = type(21, 29, FontWeight.SemiBold, -0.4),
            titleMedium = type(17, 26, FontWeight.SemiBold),
            titleSmall = type(14, 22, FontWeight.Medium),
            bodyLarge = type(16, 26), bodyMedium = type(15, 24), bodySmall = type(13, 21),
            labelLarge = type(14, 22, FontWeight.SemiBold),
            labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content,
    )
}

enum class Mark { HOME, HISTORY, COUNTS, PLUS, DOWN, UP, NEXT, BACK, PULSE, BREAKER, LOCATION, SEARCH, MORE }

/** Small native vector marks; labels on the surrounding controls provide accessibility. */
@Composable
fun MarkIcon(mark: Mark, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.7f * u, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x * u, y * u),
            Size(w * u, h * u), CornerRadius(1.5f * u), style = Stroke(1.7f * u))
        when (mark) {
            Mark.MORE -> listOf(5f, 12f, 19f).forEach { y -> drawCircle(color, 1.7f * u, Offset(12f * u, y * u)) }
            Mark.BREAKER -> { box(4f, 3f, 16f, 18f); line(8f, 8f, 16f, 8f); line(9f, 8f, 9f, 12f); line(8f, 16f, 16f, 16f); line(15f, 12f, 15f, 16f) }
            Mark.LOCATION -> {
                drawCircle(color, 6f * u, Offset(12f * u, 9f * u), style = Stroke(1.7f * u))
                drawCircle(color, 2f * u, Offset(12f * u, 9f * u), style = Stroke(1.7f * u))
                line(7f, 13f, 12f, 21f); line(12f, 21f, 17f, 13f)
            }
            Mark.SEARCH -> {
                drawCircle(color, 6f * u, Offset(10f * u, 10f * u), style = Stroke(1.7f * u))
                line(14.5f, 14.5f, 20f, 20f)
            }
            Mark.HOME -> { box(4f, 4f, 6f, 6f); box(14f, 4f, 6f, 6f); box(4f, 14f, 6f, 6f); box(14f, 14f, 6f, 6f) }
            Mark.HISTORY -> { box(5f, 3f, 14f, 18f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f) }
            Mark.COUNTS -> { line(5f, 20f, 5f, 13f); line(12f, 20f, 12f, 5f); line(19f, 20f, 19f, 9f) }
            Mark.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Mark.DOWN -> { line(7f, 9f, 12f, 14f); line(12f, 14f, 17f, 9f) }
            Mark.UP -> { line(7f, 14f, 12f, 9f); line(12f, 9f, 17f, 14f) }
            Mark.NEXT -> { line(9f, 7f, 14f, 12f); line(14f, 12f, 9f, 17f) }
            Mark.BACK -> { line(14f, 6f, 8f, 12f); line(8f, 12f, 14f, 18f) }
            Mark.PULSE -> { line(2f, 12f, 7f, 12f); line(7f, 12f, 10f, 5f); line(10f, 5f, 14f, 19f); line(14f, 19f, 17f, 12f); line(17f, 12f, 22f, 12f) }
        }
    }
}
