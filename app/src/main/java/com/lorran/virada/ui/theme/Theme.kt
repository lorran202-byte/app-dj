package com.lorran.virada.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0A0C10)
    val Surface = Color(0xFF13161D)
    val Surface2 = Color(0xFF1C2029)
    val Line = Color(0xFF2B303C)
    val Text = Color(0xFFECEFF4)
    val TextDim = Color(0xFF8C95A8)
    val DeckA = Color(0xFF2EE6D6)
    val DeckB = Color(0xFFFF5C8A)
    val Accent = Color(0xFFFFC247)
    val Rec = Color(0xFFFF4040)
    val OnColor = Color(0xFF0A0C10)

    fun deck(i: Int) = if (i == 0) DeckA else DeckB
}

/** Espaçamentos em múltiplos de 4 dp. */
object S {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}

val Mono = FontFamily.Monospace

private val type = Typography(
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text),
    bodyMedium = TextStyle(fontSize = 13.sp, color = C.Text),
    bodySmall = TextStyle(fontSize = 11.sp, color = C.TextDim),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun ViradaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.OnColor,
            secondary = C.DeckA,
            tertiary = C.DeckB,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Surface2,
            onSurfaceVariant = C.TextDim,
            outline = C.Line,
            error = C.Rec,
        ),
        typography = type,
        content = content,
    )
}
