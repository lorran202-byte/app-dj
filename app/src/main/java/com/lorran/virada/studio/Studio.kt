package com.lorran.virada.studio

import com.lorran.virada.MixPlanner
import com.lorran.virada.audio.Biquad
import com.lorran.virada.audio.DrumKit
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.audio.SR
import com.lorran.virada.audio.SampleSource
import com.lorran.virada.audio.softClip
import com.lorran.virada.audio.Decoder
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Para onde vai o áudio pronto (float −1..1, estéreo separado). */
fun interface AudioOut {
    fun write(l: FloatArray, r: FloatArray, count: Int)
}

/** Passo atual e progresso (0..1) do trabalho. */
fun interface Report {
    fun step(text: String, progress: Float)
}

private fun rmsOf(src: SampleSource, frames: Int, from: Int = 0, to: Int = frames): Double {
    var acc = 0.0; var n = 0
    var f = from.coerceAtLeast(0)
    val end = to.coerceAtMost(frames)
    while (f < end) { val v = (src.l(f) + src.r(f)) * 0.5; acc += v * v; n++; f += 97 }
    return if (n == 0) 0.0 else sqrt(acc / n)
}

/** Lê um trecho [start, start+len) de outra fonte. */
private class SegmentSrc(private val src: SampleSource, private val start: Int) : SampleSource {
    override fun l(f: Int) = src.l(start + f)
    override fun r(f: Int) = src.r(start + f)
}

data class MashupInfo(val keyVocal: MusicKey, val keyInst: MusicKey, val semitones: Int, val tempo: Double, val frames: Int)

/**
 * Mashup: voz da música A em cima do instrumental da música B.
 * A IA (separação) tira a voz de A e a voz de B; aqui acertamos tempo, tom e o lugar onde a voz entra.
 */
object Mashup {
    fun render(
        a: LoadedTrack, b: LoadedTrack, tmp: File, out: AudioOut, report: Report, isCancelled: () -> Boolean,
    ): MashupInfo {
        val aStems = a.stems ?: error("Música da voz sem separação")
        val bStems = b.stems ?: error("Música do instrumental sem separação")
        val aMix = PcmSrc(a.pcm, a.frames)
        val aV = PcmSrc(aStems[0], a.frames)
        val bMix = PcmSrc(b.pcm, b.frames)
        val bV = PcmSrc(bStems[0], b.frames)
        val aInst = MixSrc(listOf(1f to aMix, -1f to aV))
        val bInst = MixSrc(listOf(1f to bMix, -1f to bV))

        report.step("Descobrindo o tom das músicas", 0f)
        val keyA = Music.key(aInst, a.frames)
        val keyB = Music.key(bInst, b.frames)
        val shift = Music.semitoneShift(keyA, keyB)
        val tempo = MixPlanner.matchTempo(b.analysis.bpm, 1.0, a.analysis.bpm) ?: 1.0

        report.step("Ajustando BPM e tom da voz", 0.05f)
        val vFile = File(tmp, "mashup_voz.pcm")
        try {
            val vf = Stretch.toFile(aV, a.frames, tempo, Music.pitchFactor(shift), vFile,
                { report.step("Ajustando BPM e tom da voz", 0.05f + it * 0.6f) }, isCancelled)
            val voc = PcmSrc(Decoder.map(vFile), vf)

            // grade de compassos: voz esticada e instrumental
            val beatA = Music.beatFrames(a.analysis) / tempo
            val downA = Music.firstDownbeat(a.analysis) / tempo
            val beatB = Music.beatFrames(b.analysis)
            val downB = Music.firstDownbeat(b.analysis)
            val vocBar = Music.firstLoudBar(voc, vf, downA, beatA, 0.3f)
            val vocStart = downA + vocBar * beatA * 4
            // a voz entra onde a voz original de B entrava (ou 4 compassos depois do começo do som)
            val bVocBar = Music.firstLoudBar(bV, b.frames, downB, beatB, 0.3f)
            val bStartBar = ceil((MixPlanner.effectiveStart(b.analysis) - downB) / (beatB * 4)).toInt().coerceAtLeast(0)
            val instBar = if (bVocBar >= 2) bVocBar else bStartBar + 4
            val offset = (downB + instBar * beatB * 4 - vocStart).toInt()

            // volume: a voz nova fica no mesmo nível da voz original de B
            val rv = rmsOf(voc, vf)
            val rbv = rmsOf(bV, b.frames)
            val vGain = (if (rv > 1.0 && rbv > 1.0) (rbv / rv) else 1.0).coerceIn(0.5, 2.0).toFloat()
            val g = b.analysis.gain * 0.9f / 32768f

            report.step("Mixando", 0.7f)
            val n = b.frames
            val fade = SR * 4
            val block = 4096
            val l = FloatArray(block); val r = FloatArray(block)
            var o = 0
            while (o < n) {
                if (isCancelled()) throw CancellationException()
                val c = minOf(block, n - o)
                for (i in 0 until c) {
                    val f = o + i
                    val vfi = f - offset
                    val fo = if (n - f < fade) (n - f).toFloat() / fade else 1f
                    var vl = 0f; var vr = 0f
                    if (vfi in 0 until vf) {
                        val tail = minOf(1f, (vf - vfi).toFloat() / (SR / 2))
                        vl = voc.l(vfi) * vGain * tail; vr = voc.r(vfi) * vGain * tail
                    }
                    l[i] = softClip((bInst.l(f) + vl) * g * fo)
                    r[i] = softClip((bInst.r(f) + vr) * g * fo)
                }
                out.write(l, r, c)
                o += c
                if ((o / block) % 32 == 0) report.step("Mixando", 0.7f + 0.3f * o / n)
            }
            return MashupInfo(keyA, keyB, shift, tempo, n)
        } finally {
            vFile.delete()
        }
    }
}

enum class RemixStyle(val label: String, val bpm: Int, val pump: Float) {
    HOUSE("House", 124, 0.35f),
    TECHNO("Techno", 130, 0.30f),
    FUNK("Funk", 130, 0.15f),
    TRAP("Trap", 140, 0f),
    REGGAETON("Reggaeton", 95, 0.10f),
}

data class RemixInfo(val key: MusicKey, val tempo: Double, val frames: Int)

/**
 * Remix: tira a bateria original (separação por IA), acerta a música no BPM do estilo sem mudar o tom
 * e monta: intro (8 compassos) → subida (4) → drop com a música inteira e bateria nova → outro (8).
 */
object Remix {
    private const val INTRO = 8
    private const val BUILD = 4
    private const val OUTRO = 8

    private class Hit(val step: Int, val sound: Int, val gain: Float)

    // sons: 0 kick, 1 palma, 2 caixa, 3 chimbal fechado, 4 chimbal aberto, 5 aro, 6 tambor, 7 808
    private fun pattern(style: RemixStyle, bar: Int): List<Hit> {
        val h = ArrayList<Hit>()
        fun add(sound: Int, gain: Float, vararg steps: Int) { for (s in steps) h += Hit(s, sound, gain) }
        when (style) {
            RemixStyle.HOUSE -> {
                add(0, 1f, 0, 4, 8, 12); add(1, 0.6f, 4, 12); add(4, 0.35f, 2, 6, 10, 14)
                add(3, 0.15f, 0, 1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 15)
            }
            RemixStyle.TECHNO -> {
                add(0, 1f, 0, 4, 8, 12); add(4, 0.35f, 2, 6, 10, 14); add(1, 0.45f, 4, 12)
                add(5, 0.25f, 3, 7, 11, 13, 15); add(3, 0.12f, 1, 5, 9, 13)
            }
            RemixStyle.FUNK -> {
                add(0, 1f, 0, 3, 8, 11); add(1, 0.6f, 4, 12); add(6, 0.55f, 6, 7, 14)
                if (bar % 2 == 1) add(6, 0.45f, 15)
                add(3, 0.18f, 0, 2, 4, 6, 8, 10, 12, 14)
            }
            RemixStyle.TRAP -> {
                add(0, 1f, 0, 7, 10); add(7, 0.9f, 0, 7, 10); add(1, 0.65f, 8); add(2, 0.3f, 8)
                add(3, 0.2f, 0, 2, 4, 6, 8, 10)
                if (bar % 2 == 1) add(3, 0.2f, 12, 13, 14, 15) else add(3, 0.2f, 12, 14)
            }
            RemixStyle.REGGAETON -> {
                add(0, 1f, 0, 4, 8, 12); add(2, 0.5f, 3, 6, 11, 14); add(3, 0.18f, 0, 2, 4, 6, 8, 10, 12, 14)
            }
        }
        return h
    }

    private class Voice(val data: FloatArray, var pos: Int, val gain: Float)

    fun render(
        t: LoadedTrack, style: RemixStyle, bpm: Double, tmp: File, out: AudioOut, report: Report, isCancelled: () -> Boolean,
    ): RemixInfo {
        val stems = t.stems ?: error("Música sem separação")
        val mix = PcmSrc(t.pcm, t.frames)
        val drums = PcmSrc(stems[1], t.frames)
        val bass = PcmSrc(stems[2], t.frames)
        val voc = PcmSrc(stems[0], t.frames)
        val noDrums = MixSrc(if (style == RemixStyle.TRAP) listOf(1f to mix, -1f to drums, -1f to bass) else listOf(1f to mix, -1f to drums))
        val melody = MixSrc(listOf(1f to mix, -1f to drums, -1f to bass, -1f to voc))

        report.step("Descobrindo o tom", 0f)
        val key = Music.key(MixSrc(listOf(1f to mix, -1f to drums)), t.frames)
        val tempo = if (t.analysis.bpm > 0) MixPlanner.matchTempo(bpm, 1.0, t.analysis.bpm) ?: 1.0 else 1.0

        val bodyFile = File(tmp, "remix_corpo.pcm")
        val introFile = File(tmp, "remix_intro.pcm")
        try {
            report.step("Acertando a música em ${bpm.toInt()} BPM", 0.03f)
            val bf = Stretch.toFile(noDrums, t.frames, tempo, 1.0, bodyFile,
                { report.step("Acertando a música em ${bpm.toInt()} BPM", 0.03f + it * 0.6f) }, isCancelled)
            val body = PcmSrc(Decoder.map(bodyFile), bf)

            // trecho de melodia para a intro: 8 compassos a partir do primeiro compasso com som
            val beatO = Music.beatFrames(t.analysis)
            val downO = Music.firstDownbeat(t.analysis)
            val melBar = Music.firstLoudBar(melody, t.frames, downO, beatO, 0.3f)
            val segStart = (downO + melBar * beatO * 4).toInt().coerceAtLeast(0)
            report.step("Preparando a intro", 0.65f)
            val introLen = Stretch.toFile(SegmentSrc(melody, segStart), (8 * beatO * 4).toInt().coerceAtMost(t.frames - segStart),
                tempo, 1.0, introFile, {}, isCancelled)
            val intro = PcmSrc(Decoder.map(introFile), introLen)

            val step = 60.0 / bpm / 4 * SR
            val barLen = step * 16
            val downBody = downO / tempo
            val bodyStart = (INTRO + BUILD) * barLen
            val bodyBars = ceil((bf - downBody) / barLen).toInt().coerceAtLeast(1)
            val outroStart = bodyStart + bodyBars * barLen
            val total = (outroStart + OUTRO * barLen).toInt()
            val bodyOffset = bodyStart - downBody   // quadro de saída onde o corpo começa a tocar

            // sons
            val root = key.root
            val subFreq = 55.0 * 2.0.pow(((root - 9 + 12) % 12) / 12.0)   // Lá1 = 55 Hz
            val sounds = arrayOf(
                DrumKit.kick(), DrumKit.claps(), DrumKit.snare(), DrumKit.hat(false), DrumKit.hat(true),
                DrumKit.rim(), DrumKit.tom(), DrumKit.sub808(subFreq, 60.0 / bpm * 1.6),
            )
            val riser = DrumKit.riser(BUILD * barLen / SR)
            val impact = DrumKit.impact()
            val contentGain = t.analysis.gain * 0.85f / 32768f
            val drumGain = 0.55f

            val voices = ArrayList<Voice>()
            val lp = Biquad(); val hp = Biquad()
            var lastKick = -1e9
            val block = 4096
            val l = FloatArray(block); val r = FloatArray(block)
            var o = 0
            var nextStep = 0
            report.step("Montando o remix", 0.7f)
            while (o < total) {
                if (isCancelled()) throw CancellationException()
                val c = minOf(block, total - o)
                for (i in 0 until c) {
                    val f = o + i
                    // dispara a bateria no começo de cada 1/16
                    while (nextStep * step <= f) {
                        val s = nextStep
                        val bar = s / 16
                        val inBar = s % 16
                        when {
                            bar < INTRO -> for (h in pattern(style, bar)) {
                                if (h.step != inBar) continue
                                val okIntro = h.sound == 0 || h.sound == 3 || h.sound == 4 || h.sound == 7 || bar >= INTRO / 2
                                if (okIntro) voices += Voice(sounds[h.sound], 0, h.gain)
                            }
                            bar < INTRO + BUILD -> {
                                val b = bar - INTRO
                                // caixa acelerando: semínima → colcheia → semicolcheia
                                val every = when (b) { 0 -> 4; 1 -> 2; else -> 1 }
                                val lastBeat = b == BUILD - 1 && inBar >= 12
                                if (inBar % every == 0 && !lastBeat) voices += Voice(sounds[2], 0, 0.25f + 0.35f * (s - INTRO * 16) / (BUILD * 16f))
                                if (b == 0 && inBar == 0) voices += Voice(riser, 0, 0.5f)
                            }
                            bar < bodyBars + INTRO + BUILD -> {
                                if (bar == INTRO + BUILD && inBar == 0) voices += Voice(impact, 0, 0.6f)
                                for (h in pattern(style, bar)) if (h.step == inBar) {
                                    if (h.sound == 0) lastKick = s * step
                                    voices += Voice(sounds[h.sound], 0, h.gain)
                                }
                            }
                            else -> {
                                val ob = bar - (bodyBars + INTRO + BUILD)
                                for (h in pattern(style, bar)) if (h.step == inBar) {
                                    if (ob >= OUTRO / 2 && h.sound != 0 && h.sound != 3) continue
                                    if (h.sound == 0) lastKick = s * step
                                    voices += Voice(sounds[h.sound], 0, h.gain)
                                }
                            }
                        }
                        if (bar < INTRO && pattern(style, bar).any { it.sound == 0 && it.step == inBar }) lastKick = s * step
                        nextStep++
                    }
                    var dl = 0f
                    val iter = voices.iterator()
                    while (iter.hasNext()) {
                        val v = iter.next()
                        if (v.pos >= v.data.size) { iter.remove(); continue }
                        dl += v.data[v.pos++] * v.gain
                    }
                    dl *= drumGain

                    // conteúdo musical
                    var cl = 0f; var cr = 0f
                    val bar = (f / barLen).toInt()
                    if (bar < INTRO + BUILD) {
                        // intro: melodia abrindo o filtro; subida: mesma melodia (4 últimos compassos) com passa-alta subindo
                        val x: Double
                        val idx: Int
                        if (bar < INTRO) { idx = f; x = f / (INTRO * barLen) }
                        else { idx = (f - INTRO * barLen + 4 * barLen).toInt(); x = (f - INTRO * barLen) / (BUILD * barLen) }
                        if (idx in 0 until introLen) { cl = intro.l(idx); cr = intro.r(idx) }
                        if (f % 64 == 0) {
                            if (bar < INTRO) lp.lowPass((300.0 * (40.0).pow(x)).toFloat(), 0.9f)
                            else hp.highPass((40.0 * (30.0).pow(x)).toFloat(), 0.9f)
                        }
                        if (bar < INTRO) { cl = lp.l(cl); cr = lp.r(cr) } else { cl = hp.l(cl); cr = hp.r(cr) }
                        // corpo começando antes do "um" (anacruse) entra por baixo no fim da subida
                    }
                    val bi = (f - bodyOffset).toInt()
                    if (bi in 0 until bf && f >= bodyStart - downBody.coerceAtMost(barLen)) {
                        cl += body.l(bi); cr += body.r(bi)
                    }
                    // pump do sidechain
                    val since = (f - lastKick) / SR
                    val pump = if (style.pump > 0 && since >= 0) 1f - style.pump * exp(-since / 0.12).toFloat() else 1f
                    // fade no fim
                    val fo = if (total - f < barLen * 4) ((total - f) / (barLen * 4)).toFloat() else 1f
                    l[i] = softClip((cl * contentGain * pump + dl) * fo)
                    r[i] = softClip((cr * contentGain * pump + dl) * fo)
                }
                out.write(l, r, c)
                o += c
                if ((o / block) % 32 == 0) report.step("Montando o remix", 0.7f + 0.3f * o / total)
            }
            return RemixInfo(key, tempo, total)
        } finally {
            bodyFile.delete(); introFile.delete()
        }
    }
}
