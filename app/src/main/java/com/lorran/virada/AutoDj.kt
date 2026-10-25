package com.lorran.virada

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Engine
import com.lorran.virada.audio.FxId
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.audio.SR
import com.lorran.virada.data.Song
import kotlin.math.abs
import kotlin.math.floor

/** Plano de uma transição entre a música que está tocando (saída) e a próxima (entrada). */
data class MixPlan(
    /** Posição da saída onde a transição começa (sempre no início de um compasso). */
    val start: Double,
    /** Duração em quadros da música de saída. */
    val length: Double,
    /** Onde a entrada começa (numa batida, no começo do som de verdade). */
    val inStart: Double,
    /** Batidas sincronizadas? Se as músicas forem muito diferentes, faz saída com eco e filtro. */
    val sync: Boolean,
    /** Velocidade da entrada durante a transição. */
    val inTempo: Double,
)

object MixPlanner {
    private fun beatFrames(bpm: Double) = 60.0 / bpm * SR

    /** Último ponto com som forte (ignora final em fade/silêncio). */
    fun effectiveEnd(a: Analysis, frames: Int): Double {
        val max = a.peaks.maxOrNull() ?: return frames.toDouble()
        if (max <= 0f) return frames.toDouble()
        var i = a.peaks.size - 1
        while (i > 0 && a.peaks[i] < max * 0.25f) i--
        return minOf(frames.toDouble(), (i + 1).toDouble() * Analysis.PEAK_BLOCK)
    }

    /** Primeiro ponto com som (pula silêncio do começo), encaixado numa batida. */
    fun effectiveStart(a: Analysis): Double {
        val max = a.peaks.maxOrNull() ?: return 0.0
        var i = 0
        while (i < a.peaks.size - 1 && a.peaks[i] < max * 0.15f) i++
        val f = i.toDouble() * Analysis.PEAK_BLOCK
        if (a.bpm <= 0) return f
        val b = beatFrames(a.bpm)
        val k = floor((f - a.firstBeat) / b)
        return (a.firstBeat + k * b).coerceAtLeast(0.0)
    }

    /** Velocidade para a entrada bater com a saída (considera dobro/metade do BPM). */
    fun matchTempo(outBpm: Double, outTempo: Double, inBpm: Double): Double? {
        if (outBpm <= 0 || inBpm <= 0) return null
        var t = outBpm * outTempo / inBpm
        while (t > 1.45) t /= 2
        while (t < 0.7) t *= 2
        return t
    }

    fun plan(out: Analysis, outFrames: Int, outTempo: Double, outPos: Double, inn: Analysis, beats: Int): MixPlan {
        val t = matchTempo(out.bpm, outTempo, inn.bpm)
        val sync = t != null && abs(t - 1.0) <= 0.12
        val bo = if (out.bpm > 0) beatFrames(out.bpm) else SR * 0.5
        val lenBeats = if (sync) beats else minOf(beats, 8)
        val length = lenBeats * bo
        val end = effectiveEnd(out, outFrames)
        var start = end - length
        if (out.bpm > 0) {
            val bar = bo * 4
            start = out.firstBeat + floor((start - out.firstBeat) / bar) * bar
        }
        // se já passou do ponto, começa na próxima batida
        if (start < outPos) {
            start = if (out.bpm > 0) out.firstBeat + (floor((outPos - out.firstBeat) / bo) + 1) * bo else outPos
        }
        val maxLen = (outFrames - start).coerceAtLeast(bo)
        return MixPlan(start, minOf(length, maxLen), effectiveStart(inn), sync, if (sync) t!! else 1.0)
    }

    /** Valores dos controles em cada ponto da transição (x de 0 a 1). */
    data class Automation(
        val crossfader: Float,   // 0 = só saída, 1 = só entrada
        val outLow: Float,
        val inLow: Float,
        val outFilter: Float,    // 0.5 = sem filtro; >0.5 passa-alta; <0.5 passa-baixa
        val outEcho: Boolean,
        val startIn: Boolean,
    )

    fun automation(x: Float, sync: Boolean): Automation =
        if (sync) Automation(
            crossfader = x,
            outLow = if (x < 0.5f) 0.5f else 0.12f,
            inLow = if (x < 0.5f) 0.12f else 0.5f,
            outFilter = if (x < 0.75f) 0.5f else 0.5f + (x - 0.75f) / 0.25f * 0.3f,
            outEcho = false,
            startIn = true,
        ) else Automation(
            crossfader = ((x - 0.25f) / 0.6f).coerceIn(0f, 1f),
            outLow = 0.5f,
            inLow = 0.5f,
            outFilter = 0.5f - x * 0.35f,
            outEcho = x > 0.4f,
            startIn = x >= 0.25f,
        )
}

/** Auto DJ: toca a fila sozinho, emendando as músicas com transições. */
class AutoDj(private val vm: DjViewModel) {
    var enabled by mutableStateOf(false)
    var beats by mutableIntStateOf(16)
    var continueAlone by mutableStateOf(true)
    /** 0..1 durante a transição; -1 fora dela (para mostrar na tela). */
    var progress by mutableFloatStateOf(-1f)

    private var spent = arrayOfNulls<LoadedTrack>(2)
    private val wasPlayed = BooleanArray(2)
    private val played = HashSet<String>()
    private var prepared: Song? = null
    private var preparedDeck = -1

    private var plan: MixPlan? = null
    private var planFor: Pair<LoadedTrack, LoadedTrack>? = null
    private var outDeck = -1
    private var inStarted = false
    private var glideDeck = -1

    fun toggle() {
        enabled = !enabled
        if (enabled) {
            vm.message = if (vm.queue.isEmpty() && !continueAlone) "Auto DJ ligado: adicione músicas à fila"
            else "Auto DJ ligado"
            PlaybackService.ensure(vm.ctxPublic)
        } else abort()
    }

    /** O usuário mexeu no crossfader ou no filtro: ele assume a mixagem. */
    fun userTookOver() {
        if (progress >= 0f) {
            abort()
            vm.message = "Você assumiu a transição"
        }
    }

    private fun abort() {
        plan = null; planFor = null; outDeck = -1; inStarted = false; progress = -1f
    }

    private fun xfFor(deck: Int, toward: Float): Float =
        if (deck == 0) -1f + 2f * toward else 1f - 2f * toward

    private fun liveDeck(): Int {
        val a = Engine.decks[0].playing
        val b = Engine.decks[1].playing
        return when {
            a && b -> if (vm.crossfader <= 0f) 0 else 1
            a -> 0
            b -> 1
            else -> -1
        }
    }

    private fun isReady(i: Int): Boolean {
        val d = Engine.decks[i]
        val t = d.track ?: return false
        return !d.playing && vm.decks[i].loading < 0f && t !== spent[i]
    }

    fun onLoaded(i: Int) {
        // música nova no deck: deixa de estar "usada"
        spent[i] = null
        wasPlayed[i] = false
    }

    fun tick() {
        // volta suave da velocidade depois da transição
        if (glideDeck >= 0) {
            val ui = vm.decks[glideDeck]
            if (!Engine.decks[glideDeck].playing || ui.sync) glideDeck = -1
            else {
                val step = 0.0006f / vm.pitchRange
                val p = ui.pitch
                if (abs(p) <= step) { vm.setPitch(glideDeck, 0f); glideDeck = -1 }
                else vm.setPitch(glideDeck, p - step * kotlin.math.sign(p))
            }
        }
        if (!enabled) return
        for (i in 0..1) {
            val d = Engine.decks[i]
            if (d.playing) wasPlayed[i] = true
            if (d.ended && d.track != null) spent[i] = d.track
        }
        val live = liveDeck()
        if (plan != null && outDeck >= 0) { runTransition(); return }

        if (live < 0) {
            // o usuário pausou no meio de uma música: espera ele dar play
            if ((0..1).any { wasPlayed[it] && Engine.decks[it].track != null && Engine.decks[it].track !== spent[it] }) return
            val ready = (0..1).firstOrNull { isReady(it) }
            if (ready != null) {
                vm.moveCrossfaderAuto(xfFor(ready, 0f))
                start(ready)
                return
            }
            if (vm.decks.any { it.loading >= 0f }) return
            val next = nextSong(null) ?: return
            val deck = if (Engine.decks[0].track == null || spent[0] != null) 0 else 1
            vm.load(deck, next)
            return
        }

        val other = 1 - live
        if (!isReady(other)) {
            if (vm.decks[other].loading < 0f && !Engine.decks[other].playing) {
                val next = nextSong(live) ?: return
                vm.load(other, next)
            }
            return
        }
        val outT = Engine.decks[live].track ?: return
        val inT = Engine.decks[other].track ?: return
        if (planFor?.first !== outT || planFor?.second !== inT) {
            val d = Engine.decks[live]
            plan = MixPlanner.plan(outT.analysis, outT.frames, d.tempo, d.position, inT.analysis, beats)
            planFor = outT to inT
            outDeck = -1
        }
        val p = plan ?: return
        if (Engine.decks[live].position >= p.start) {
            outDeck = live
            inStarted = false
            runTransition()
        }
    }

    private fun start(i: Int) {
        val d = Engine.decks[i]
        val ui = vm.decks[i]
        ui.song?.let { played.add(it.key) }
        if (d.position < 1.0) d.seekTo = MixPlanner.effectiveStart(d.track!!.analysis)
        vm.playAuto(i)
    }

    private fun runTransition() {
        val p = plan ?: return
        val o = outDeck
        val n = 1 - o
        val od = Engine.decks[o]
        val nd = Engine.decks[n]
        if (od.track == null || nd.track == null || !od.playing) { finish(o, n, false); return }
        if (od.position < p.start - SR) { abort(); return }
        val x = ((od.position - p.start) / p.length).toFloat().coerceIn(0f, 1f)
        val a = MixPlanner.automation(x, p.sync)
        progress = x

        if (a.startIn && !inStarted) {
            inStarted = true
            if (p.sync) {
                // entra no compasso certo, compensando o atraso do relógio
                val beatsLate = (od.position - p.start) / od.beatFrames
                nd.seekTo = p.inStart + beatsLate.coerceAtLeast(0.0) * nd.beatFrames
                vm.setSyncAuto(n, true)
            } else {
                nd.seekTo = p.inStart
            }
            vm.setEqAuto(n, a.inLow)
            vm.decks[n].song?.let { played.add(it.key) }
            vm.playAuto(n)
        }
        vm.moveCrossfaderAuto(xfFor(o, a.crossfader))
        vm.setEqAuto(o, a.outLow)
        if (inStarted) vm.setEqAuto(n, a.inLow)
        vm.setFilterAuto(o, a.outFilter)
        if (a.outEcho != (vm.decks[o].fxMask and (1 shl FxId.ECHO) != 0)) vm.toggleFx(o, FxId.ECHO)
        if (x >= 1f) finish(o, n, true)
    }

    private fun finish(o: Int, n: Int, ok: Boolean) {
        val od = Engine.decks[o]
        od.playing = false
        spent[o] = od.track
        vm.setEqAuto(o, 0.5f)
        vm.setFilterAuto(o, 0.5f)
        if (vm.decks[o].fxMask and (1 shl FxId.ECHO) != 0) vm.toggleFx(o, FxId.ECHO)
        if (ok) {
            vm.moveCrossfaderAuto(xfFor(o, 1f))
            vm.setEqAuto(n, 0.5f)
            vm.setSyncAuto(n, false)
            if (abs(Engine.decks[n].tempo - 1.0) < 0.12) glideDeck = n
        }
        abort()
    }

    /** Próxima música: a da fila ou, se acabou, uma parecida da biblioteca. */
    private fun nextSong(live: Int?): Song? {
        if (vm.queue.isNotEmpty()) return vm.queue.removeAt(0)
        if (!continueAlone) return null
        val pool = vm.visibleSongs().filter { it.key !in played && vm.decks.none { d -> d.song?.key == it.key } }
        if (pool.isEmpty()) return null
        val ref = live?.let { Engine.decks[it].track?.analysis?.bpm?.times(Engine.decks[it].tempo) } ?: 0.0
        if (ref > 0) {
            val close = pool.mapNotNull { s ->
                val b = vm.lib.bpmOf(s.key)
                val t = MixPlanner.matchTempo(ref, 1.0, b) ?: return@mapNotNull null
                if (abs(t - 1.0) <= 0.06) s to abs(t - 1.0) else null
            }.sortedBy { it.second }
            if (close.isNotEmpty()) return close.take(5).random().first
        }
        return pool.random()
    }
}
