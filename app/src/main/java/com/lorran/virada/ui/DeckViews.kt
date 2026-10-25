package com.lorran.virada.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorran.virada.DjViewModel
import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Engine
import com.lorran.virada.audio.FxId
import com.lorran.virada.audio.SR
import com.lorran.virada.ui.theme.C
import com.lorran.virada.ui.theme.Mono
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

fun fmtTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtBpm(b: Double) = if (b <= 0) "--.-" else "%.1f".format(b)

@Composable
fun DeckPanel(i: Int, vm: DjViewModel, tick: State<Long>, showStrip: Boolean, modifier: Modifier = Modifier) {
    val ui = vm.decks[i]
    val color = C.deck(i)
    val mirrored = i == 1
    Column(modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        // cabeçalho
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.Surface, RoundedCornerShape(6.dp))
                .clickable { vm.libraryFor = i }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text(if (i == 0) "A" else "B", color = C.OnColor, fontWeight = FontWeight.Black, fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val song = ui.song
                Text(
                    song?.title ?: if (ui.loading >= 0) "Carregando…" else "Toque para escolher uma música",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(song?.artist ?: "", fontSize = 10.sp, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtBpm(ui.bpm * ui.tempo), fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
                Text(
                    "-" + fmtTime(ui.durSec - ui.posSec),
                    fontFamily = Mono, fontSize = 10.sp,
                    color = if (ui.song != null && ui.durSec - ui.posSec < 30) C.Rec else C.TextDim,
                )
            }
        }
        if (ui.loading >= 0f) {
            LinearProgressIndicator(
                progress = { ui.loading },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                color = color, trackColor = C.Line,
            )
        } else {
            Overview(i, vm, tick, color, Modifier.fillMaxWidth().padding(top = 3.dp).height(14.dp))
        }

        Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val pitch: @Composable RowScope.() -> Unit = {
                Column(Modifier.width(34.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = C.TextDim, fontSize = 10.sp)
                    VFader(
                        (ui.pitch + 1f) / 2f, { vm.setPitch(i, it * 2f - 1f) }, color,
                        Modifier.weight(1f).fillMaxWidth(), centerMark = true, default = 0.5f, sensitivity = 0.35f,
                    )
                    Text("−", color = C.TextDim, fontSize = 10.sp)
                    Text("%+.1f%%".format((ui.tempo - 1.0) * 100), fontFamily = Mono, fontSize = 8.sp, color = C.TextDim, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    PadButton(
                        "KEY", C.Accent, Modifier.fillMaxWidth().height(24.dp), active = ui.keyLock,
                        fontSize = 9.sp, onTap = { vm.toggleKeyLock(i) },
                    )
                }
            }
            val jog: @Composable RowScope.() -> Unit = {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    JogWheel(i, vm, tick, color, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
            }
            val buttons: @Composable RowScope.() -> Unit = {
                Column(Modifier.width(62.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PadButton(
                        "SYNC", C.Accent, Modifier.fillMaxWidth().weight(0.8f), active = ui.sync,
                        onTap = { vm.toggleSync(i) },
                    )
                    PadButton(
                        "CUE", color, Modifier.fillMaxWidth().weight(1f),
                        onPress = { if (it) vm.cueDown(i) else vm.cueUp(i) },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .background(if (ui.playing) color else C.Surface2, RoundedCornerShape(6.dp))
                            .pointerInput(Unit) { detectTapGestures(onPress = { vm.togglePlay(i) }) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (ui.playing) "Pausar" else "Tocar",
                            tint = if (ui.playing) C.OnColor else color,
                        )
                    }
                }
            }
            if (!mirrored) { pitch(); Spacer(Modifier.width(4.dp)); jog(); Spacer(Modifier.width(6.dp)); buttons() }
            else { buttons(); Spacer(Modifier.width(6.dp)); jog(); Spacer(Modifier.width(4.dp)); pitch() }
        }
        if (showStrip) ToolStrip(i, vm, color)
    }
}

@Composable
fun ToolStrip(i: Int, vm: DjViewModel, color: Color) {
    val ui = vm.decks[i]
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("CUES", "LOOPS", "FX").forEachIndexed { k, t ->
                Text(
                    t,
                    color = if (ui.tab == k) color else C.TextDim,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { ui.tab = k }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.tab == 2) {
                Text("INTENS.", color = C.TextDim, fontSize = 8.sp)
                HFader(ui.fxAmount, { vm.setFxAmount(i, it) }, Modifier.width(96.dp).height(22.dp), color, color, 0.5f)
            }
        }
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (ui.tab) {
                0 -> for (n in 0 until 4) {
                    val set = ui.cues[n] >= 0
                    PadButton(
                        "${n + 1}", color, Modifier.weight(1f).fillMaxHeight(), active = set,
                        sub = if (set) fmtTime(ui.cues[n] / SR) else "marcar",
                        onTap = { vm.hotCue(i, n) },
                        onLongPress = if (set) ({ vm.deleteCue(i, n) }) else null,
                    )
                }
                1 -> listOf(0.25f to "1/4", 0.5f to "1/2", 1f to "1", 2f to "2", 4f to "4", 8f to "8", 16f to "16").forEach { (b, t) ->
                    PadButton(
                        t, color, Modifier.weight(1f).fillMaxHeight(), active = ui.loopBeats == b,
                        enabled = ui.song != null,
                        onTap = { vm.loop(i, b) },
                    )
                }
                else -> {
                    val names = listOf("ECO", "FLANG", "REVERB", "PHASER", "GATE", "ROLL", "REVERSO", "FREIO")
                    for (fx in 0 until FxId.COUNT) {
                        val on = ui.fxMask and (1 shl fx) != 0
                        if (vm.isMomentary(fx)) {
                            PadButton(
                                names[fx], C.Accent, Modifier.weight(1f).fillMaxHeight(), active = on,
                                fontSize = 8.sp, sub = "segure",
                                onPress = { vm.holdFx(i, fx, it) },
                            )
                        } else {
                            PadButton(
                                names[fx], color, Modifier.weight(1f).fillMaxHeight(), active = on,
                                fontSize = 8.sp,
                                onTap = { vm.toggleFx(i, fx) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Overview(i: Int, vm: DjViewModel, tick: State<Long>, color: Color, modifier: Modifier) {
    Canvas(
        modifier
            .background(C.Surface, RoundedCornerShape(3.dp))
            .pointerInput(Unit) { detectTapGestures { o -> vm.seekFraction(i, o.x / this.size.width) } }
    ) {
        tick.value
        val d = Engine.decks[i]
        val t = d.track ?: return@Canvas
        val a = t.analysis
        val w = this.size.width
        val h = this.size.height
        val cols = (w / 2f).toInt().coerceAtLeast(1)
        val per = a.peaks.size.toFloat() / cols
        for (x in 0 until cols) {
            val s = (x * per).toInt()
            val e = ((x + 1) * per).toInt().coerceAtMost(a.peaks.size)
            var m = 0f
            for (k in s until e) if (a.peaks[k] > m) m = a.peaks[k]
            val bh = (m * a.gain).coerceAtMost(1f) * h * 0.9f
            drawLine(color.copy(alpha = 0.55f), Offset(x * 2f, h / 2 - bh / 2), Offset(x * 2f, h / 2 + bh / 2), 1.5f)
        }
        val px = (d.position / t.frames).toFloat() * w
        drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, Size(px, h))
        drawLine(C.Text, Offset(px, 0f), Offset(px, h), 2f)
        val ui = vm.decks[i]
        for (c in ui.cues) if (c >= 0) {
            val cx = (c / t.frames).toFloat() * w
            drawLine(C.Accent, Offset(cx, 0f), Offset(cx, h * 0.4f), 2f)
        }
    }
}

@Composable
fun Waveform(i: Int, vm: DjViewModel, tick: State<Long>, modifier: Modifier) {
    val color = C.deck(i)
    Canvas(modifier.background(C.Surface)) {
        tick.value
        val w = this.size.width
        val h = this.size.height
        val cy = h / 2
        val d = Engine.decks[i]
        val t = d.track
        if (t == null) {
            drawLine(C.Line, Offset(0f, cy), Offset(w, cy), 1f)
            drawLine(C.Text, Offset(w / 2, 0f), Offset(w / 2, h), 2f)
            return@Canvas
        }
        val a: Analysis = t.analysis
        val pos = d.position
        val window = 7.0 * SR * d.tempo
        val fpp = window / w
        val step = 2.dp.toPx()
        val bpp = max(1, (fpp * step / Analysis.PEAK_BLOCK).toInt())
        val white = Color(0xFFDDE3EE)
        val ui = vm.decks[i]

        if (d.loopOn) {
            val x0 = (w / 2 + (d.loopStart - pos) / fpp).toFloat()
            val x1 = (w / 2 + (d.loopEnd - pos) / fpp).toFloat()
            drawRect(C.Accent.copy(alpha = 0.16f), Offset(x0, 0f), Size(x1 - x0, h))
        }
        if (a.bpm > 0) {
            val bf = d.beatFrames
            val startF = pos - (w / 2) * fpp
            var k = ceil((startF - a.firstBeat) / bf).toLong()
            while (true) {
                val f = a.firstBeat + k * bf
                val x = (w / 2 + (f - pos) / fpp).toFloat()
                if (x > w) break
                val bar = Math.floorMod(k, 4L) == 0L
                drawLine(if (bar) C.TextDim.copy(alpha = 0.7f) else C.Line, Offset(x, if (bar) 0f else h * 0.82f), Offset(x, h), if (bar) 1.5f else 1f)
                k++
            }
        }
        var x = 0f
        while (x < w) {
            val f = pos + (x - w / 2) * fpp
            val idx = (f / Analysis.PEAK_BLOCK).toInt()
            if (idx >= 0 && idx < a.peaks.size) {
                var pk = 0f; var lo = 0f
                val e = (idx + bpp).coerceAtMost(a.peaks.size)
                for (k in idx until e) {
                    if (a.peaks[k] > pk) pk = a.peaks[k]
                    if (a.lows[k] > lo) lo = a.lows[k]
                }
                val v = (pk * a.gain).coerceAtMost(1f)
                val bass = if (pk > 0) (lo / pk * 1.6f).coerceIn(0f, 1f) else 0f
                val c = lerp(white, color, bass)
                val bh = v * h * 0.46f
                val alpha = if (x < w / 2) 0.55f else 1f
                drawLine(c.copy(alpha = alpha), Offset(x, cy - bh), Offset(x, cy + bh), step * 0.75f)
            }
            x += step
        }
        for ((n, c) in ui.cues.withIndex()) if (c >= 0) {
            val cx = (w / 2 + (c - pos) / fpp).toFloat()
            if (cx in 0f..w) {
                drawLine(C.Accent, Offset(cx, 0f), Offset(cx, h), 1.5f)
                drawCircle(C.Accent, 4.dp.toPx(), Offset(cx, 5.dp.toPx()))
            }
        }
        drawLine(C.Text, Offset(w / 2, 0f), Offset(w / 2, h), 2.dp.toPx())
    }
}

@Composable
fun JogWheel(i: Int, vm: DjViewModel, tick: State<Long>, color: Color, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val r0 = hypot(down.position.x - c.x, down.position.y - c.y) / (this.size.width / 2f)
                val bendMode = r0 > 0.8f && Engine.decks[i].playing
                if (!bendMode) vm.scratchStart(i)
                var prevAng = atan2(down.position.y - c.y, down.position.x - c.x)
                var prevT = down.uptimeMillis
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val ang = atan2(ch.position.y - c.y, ch.position.x - c.x)
                    var da = ang - prevAng
                    if (da > PI) da -= (2 * PI).toFloat()
                    if (da < -PI) da += (2 * PI).toFloat()
                    prevAng = ang
                    val dt = (ch.uptimeMillis - prevT).coerceAtLeast(1L)
                    prevT = ch.uptimeMillis
                    if (bendMode) vm.bend(i, (da / dt * 6f).coerceIn(-0.25f, 0.25f))
                    else vm.scratchMove(i, da)
                    ch.consume()
                }
                if (bendMode) vm.bend(i, 0f) else vm.scratchEnd(i)
            }
        }
    ) {
        tick.value
        val d = Engine.decks[i]
        val r = this.size.minDimension / 2f
        drawCircle(C.Surface2, r)
        drawCircle(color.copy(alpha = if (d.playing) 0.9f else 0.35f), r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF20242E), Color(0xFF0D0F14)), center, r * 0.86f),
            r * 0.86f,
        )
        for (k in 1..6) drawCircle(Color.White.copy(alpha = 0.035f), r * (0.3f + k * 0.09f), style = Stroke(1f))
        val turns = d.position / SR / 1.8
        val deg = ((turns - Math.floor(turns)) * 360.0).toFloat()
        rotate(deg) {
            drawLine(color, center + Offset(0f, -r * 0.84f), center + Offset(0f, -r * 0.5f), 4.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(C.Surface, r * 0.22f)
        drawCircle(color, r * 0.22f, style = Stroke(1.5.dp.toPx()))
        val lt = d.track
        if (lt != null) {
            val f = (d.position / lt.frames).toFloat()
            drawArc(color, -90f, 360f * f, false, center - Offset(r * 0.16f, r * 0.16f), Size(r * 0.32f, r * 0.32f), style = Stroke(2.dp.toPx()))
        }
        if (d.scratching) drawCircle(C.Text.copy(alpha = 0.08f), r * 0.86f)
    }
}

@Composable
fun BigMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = C.TextDim, fontSize = 13.sp)
    }
}
