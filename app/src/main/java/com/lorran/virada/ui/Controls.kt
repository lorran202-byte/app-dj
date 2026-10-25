package com.lorran.virada.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorran.virada.ui.theme.C
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun rememberFrameTick(): State<Long> = produceState(0L) {
    while (true) withFrameNanos { value = it }
}

@Composable
fun Knob(
    value: Float,
    onChange: (Float) -> Unit,
    label: String,
    color: Color,
    diameter: Dp,
    modifier: Modifier = Modifier,
    default: Float = 0.5f,
    bipolar: Boolean = true,
) {
    val v by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(default) }) }
                .pointerInput(Unit) {
                    detectDragGestures { ch, drag ->
                        ch.consume()
                        cb((v + (drag.x - drag.y) / (this.size.height * 3f)).coerceIn(0f, 1f))
                    }
                }
        ) {
            val stroke = this.size.minDimension * 0.1f
            val r = this.size.minDimension / 2f
            drawCircle(C.Surface2, radius = r * 0.72f)
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(C.Line, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = 135f + 270f * value
            if (bipolar) {
                val start = 270f
                drawArc(color, min(start, ang), abs(ang - start).coerceAtLeast(1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                drawArc(color, 135f, (270f * value).coerceAtLeast(1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val rad = Math.toRadians(ang.toDouble())
            val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
            drawLine(C.Text, center + dir * (r * 0.15f), center + dir * (r * 0.62f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        }
        Text(label, fontSize = 8.sp, color = C.TextDim, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Fader vertical com arraste relativo (não pula se encostar sem querer). */
@Composable
fun VFader(
    value: Float,
    onChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    centerMark: Boolean = false,
    default: Float = 0.5f,
    sensitivity: Float = 1f,
) {
    val v by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(default) }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, dy ->
                    ch.consume()
                    cb((v - dy / (this.size.height * 0.85f) * sensitivity).coerceIn(0f, 1f))
                }
            }
    ) {
        val pad = 12.dp.toPx()
        val h = this.size.height - pad * 2
        val cx = this.size.width / 2
        drawRoundRect(C.Line, Offset(cx - 2.dp.toPx(), pad), Size(4.dp.toPx(), h), CornerRadius(2.dp.toPx()))
        for (k in 0..10) {
            val y = pad + h * k / 10f
            val w = if (centerMark && k == 5) this.size.width * 0.45f else this.size.width * 0.2f
            drawLine(if (centerMark && k == 5) C.TextDim else C.Line, Offset(cx - w, y), Offset(cx - w * 0.55f, y), 1.dp.toPx())
            drawLine(if (centerMark && k == 5) C.TextDim else C.Line, Offset(cx + w * 0.55f, y), Offset(cx + w, y), 1.dp.toPx())
        }
        val y = pad + (1f - value) * h
        if (!centerMark) drawRoundRect(color.copy(alpha = 0.35f), Offset(cx - 2.dp.toPx(), y), Size(4.dp.toPx(), pad + h - y), CornerRadius(2.dp.toPx()))
        val tw = this.size.width * 0.86f
        val th = 18.dp.toPx()
        drawRoundRect(C.Surface2, Offset(cx - tw / 2, y - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()))
        drawRoundRect(C.Line, Offset(cx - tw / 2, y - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawLine(color, Offset(cx - tw / 2 + 3.dp.toPx(), y), Offset(cx + tw / 2 - 3.dp.toPx(), y), 2.dp.toPx())
    }
}

@Composable
fun HFader(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    leftColor: Color = C.DeckA,
    rightColor: Color = C.DeckB,
    default: Float = 0.5f,
) {
    val v by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(default) }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, dx ->
                    ch.consume()
                    cb((v + dx / (this.size.width * 0.85f)).coerceIn(0f, 1f))
                }
            }
    ) {
        val pad = 14.dp.toPx()
        val w = this.size.width - pad * 2
        val cy = this.size.height / 2
        drawRoundRect(C.Line, Offset(pad, cy - 2.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawLine(C.TextDim, Offset(pad + w / 2, cy - 8.dp.toPx()), Offset(pad + w / 2, cy + 8.dp.toPx()), 1.dp.toPx())
        val x = pad + value * w
        val tw = 20.dp.toPx()
        val th = this.size.height * 0.8f
        drawRoundRect(C.Surface2, Offset(x - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()))
        drawRoundRect(C.Line, Offset(x - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
        val c = if (value < 0.5f) leftColor else if (value > 0.5f) rightColor else C.Text
        drawLine(c, Offset(x, cy - th / 2 + 3.dp.toPx()), Offset(x, cy + th / 2 - 3.dp.toPx()), 2.dp.toPx())
    }
}

/**
 * Botão de pad. [onPress] recebe true ao encostar e false ao soltar (efeitos de segurar).
 */
@Composable
fun PadButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    sub: String? = null,
    fontSize: TextUnit = 11.sp,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onPress: ((Boolean) -> Unit)? = null,
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val lit = active || down
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .background(if (lit) color else C.Surface2, shape)
            .border(1.dp, if (enabled) color.copy(alpha = if (lit) 1f else 0.55f) else C.Line, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        down = true
                        press?.invoke(true)
                        tryAwaitRelease()
                        down = false
                        press?.invoke(false)
                    },
                    onTap = { tap?.invoke() },
                    onLongPress = if (onLongPress != null) ({ _ -> long?.invoke() }) else null,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                color = when { !enabled -> C.Line; lit -> C.OnColor; else -> color },
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                letterSpacing = 0.3.sp,
            )
            if (sub != null) Text(sub, color = if (lit) C.OnColor else C.TextDim, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
fun LevelMeter(level: () -> Float, tick: State<Long>, modifier: Modifier = Modifier, vertical: Boolean = true) {
    Canvas(modifier) {
        tick.value
        val l = level().coerceIn(0f, 1.2f) / 1.2f
        val n = 12
        for (k in 0 until n) {
            val on = l >= (k + 1f) / n
            val c = when {
                k >= n - 2 -> C.Rec
                k >= n - 4 -> C.Accent
                else -> C.DeckA
            }
            if (vertical) {
                val h = this.size.height / n
                drawRect(if (on) c else C.Line, Offset(0f, this.size.height - (k + 1) * h + 1), Size(this.size.width, h - 2))
            } else {
                val w = this.size.width / n
                drawRect(if (on) c else C.Line, Offset(k * w + 1, 0f), Size(w - 2, this.size.height))
            }
        }
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(50)))
}
