package com.lorran.virada.studio

import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Analyzer
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.audio.SR
import com.lorran.virada.audio.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class StudioTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "studio-test").apply { mkdirs() }

    private fun buf(frames: Int, f: (Int) -> Double): ShortBuffer {
        val a = ShortArray(frames * 2)
        for (i in 0 until frames) { val v = (f(i) * 32767).toInt().coerceIn(-32768, 32767).toShort(); a[i * 2] = v; a[i * 2 + 1] = v }
        return ShortBuffer.wrap(a)
    }

    private fun freq(src: SampleSource, from: Int, n: Int): Double {
        var c = 0; var prev = 0f
        for (i in from until from + n) { val v = src.l(i); if (prev <= 0f && v > 0f) c++; prev = v }
        return c * SR.toDouble() / n
    }

    /** Amplitude (0..1) de uma frequência (Goertzel) num bloco de float normalizado. */
    private fun amp(x: FloatArray, n: Int, f: Double): Double {
        val c = 2 * kotlin.math.cos(2 * PI * f / SR)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) { val s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0 }
        return 2 * sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / n
    }

    @Test fun stretchChangesTempoAndPitchIndependently() {
        val frames = SR * 6
        val src = PcmSrc(buf(frames) { sin(2 * PI * 440 * it / SR) * 0.5 }, frames)
        // +2 semitons, 10% mais rápido
        val out = File(tmp, "st.pcm")
        val n = Stretch.toFile(src, frames, 1.1, Music.pitchFactor(2), out)
        assertEquals(frames / 1.1, n.toDouble(), 50.0)
        val r = PcmSrc(com.lorran.virada.audio.Decoder.map(out), n)
        assertEquals(440 * 2.0.pow(2 / 12.0), freq(r, SR, SR * 3), 4.0)
        out.delete()
    }

    @Test fun keyOfMajorTriadAndShift() {
        // acorde de Sol maior (Sol, Si, Ré) + baixo em Sol
        val notes = doubleArrayOf(196.0, 246.94, 293.66, 98.0)
        val frames = SR * 20
        val src = PcmSrc(buf(frames) { i -> notes.sumOf { sin(2 * PI * it * i / SR) } * 0.2 }, frames)
        val k = Music.key(src, frames)
        assertEquals(7, k.majorRoot)
        // Lá menor (relativa de Dó) → Sol maior: sobe 7 = desce 5
        assertEquals(-5, Music.semitoneShift(MusicKey(9, true), MusicKey(7, false)))
        assertEquals(2, Music.semitoneShift(MusicKey(0, false), MusicKey(2, false)))
        assertEquals(0, Music.semitoneShift(MusicKey(9, true), MusicKey(0, false)))
    }

    /** Música sintética: bumbo em cada batida + "voz" (seno) + melodia, com as partes separadas. */
    private fun track(bpm: Double, seconds: Int, vocalHz: Double, melodyHz: Double): LoadedTrack {
        val frames = SR * seconds
        val beat = 60.0 / bpm * SR
        fun kick(i: Int): Double { val t = (i % beat) / SR; return sin(2 * PI * 55 * t) * exp(-t / 0.08) * 0.6 }
        fun voc(i: Int) = if (i > SR * 4) sin(2 * PI * vocalHz * i / SR) * 0.2 else 0.0
        fun mel(i: Int) = sin(2 * PI * melodyHz * i / SR) * 0.15
        val mix = buf(frames) { kick(it) + voc(it) + mel(it) }
        val v = buf(frames) { voc(it) }
        val d = buf(frames) { kick(it) }
        val b = buf(frames) { 0.0 }
        val a = Analyzer.analyze(mix, frames)
        return LoadedTrack(mix, frames, a).also { it.stems = arrayOf(v, d, b) }
    }

    private class Collect : AudioOut {
        val l = ArrayList<Float>()
        override fun write(l: FloatArray, r: FloatArray, count: Int) { for (i in 0 until count) this.l.add(l[i]) }
    }

    @Test fun mashupHasVocalOfAOnInstrumentalOfB() {
        val a = track(120.0, 40, 660.0, 300.0)
        val b = track(126.0, 40, 880.0, 392.0)
        val out = Collect()
        val info = Mashup.render(a, b, tmp, out, { _, _ -> }, { false })
        assertEquals(b.frames, out.l.size)
        assertEquals(126.0 / 120.0, info.tempo, 0.02)
        val x = out.l.subList(SR * 25, SR * 27).toFloatArray()
        assertTrue(x.all { !it.isNaN() && abs(it) <= 1f })
        val shifted = 660.0 * 2.0.pow(info.semitones / 12.0)
        assertTrue("voz de A presente", amp(x, x.size, shifted) > 0.02)
        assertTrue("voz de B removida", amp(x, x.size, 880.0) < 0.01)
        assertTrue("melodia de B presente", amp(x, x.size, 392.0) > 0.02)
        assertTrue("melodia de A ausente", amp(x, x.size, 300.0) < 0.01)
    }

    @Test fun remixHasStructureAndNewBeat() {
        val t = track(118.0, 40, 660.0, 330.0)
        for (style in RemixStyle.entries) {
            val out = Collect()
            val info = Remix.render(t, style, style.bpm.toDouble(), tmp, out, { _, _ -> }, { false })
            assertEquals(info.frames, out.l.size)
            val bar = 60.0 / style.bpm * 4 * SR
            // intro + subida + música + outro
            assertTrue(out.l.size > (20 * bar).toInt())
            val all = out.l.toFloatArray()
            assertTrue(all.all { !it.isNaN() && abs(it) <= 1f })
            // no drop tem melodia (no tom original: keylock) e bateria
            val s = (13 * bar).toInt()
            val x = all.copyOfRange(s, s + SR * 2)
            assertTrue("${style.label}: melodia", amp(x, x.size, 330.0) > 0.01)
            var peak = 0f; for (v in x) peak = maxOf(peak, abs(v))
            assertTrue("${style.label}: som no drop", peak > 0.2f)
            // a subida termina antes do drop com pouca bateria no último tempo
            assertTrue(info.tempo > 0)
        }
    }
}
