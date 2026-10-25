package com.lorran.virada.stems

import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Deck
import com.lorran.virada.audio.Engine
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.audio.SR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StemsTest {

    /** Modelo falso: cada faixa recebe uma fração fixa do espectro. */
    private class Fixed(val w: FloatArray) : MaskModel {
        override val stemCount = w.size
        override fun run(input: FloatArray, outputs: Array<FloatArray>) {
            for (s in w.indices) for (k in input.indices) outputs[s][k] = input[k] * w[s]
        }
    }

    private class Mem(n: Int) : StemSink {
        val l = FloatArray(n); val r = FloatArray(n); var at = 0
        override fun write(left: FloatArray, right: FloatArray, offset: Int, count: Int) {
            System.arraycopy(left, offset, l, at, count); System.arraycopy(right, offset, r, at, count); at += count
        }
    }

    private fun music(seconds: Int): ShortBuffer {
        val n = seconds * SR
        val a = ShortArray(n * 2)
        for (i in 0 until n) {
            a[i * 2] = (sin(2 * PI * 220 * i / SR) * 8000 + sin(2 * PI * 1250 * i / SR) * 3000).toInt().toShort()
            a[i * 2 + 1] = (sin(2 * PI * 330 * i / SR) * 7000 + sin(2 * PI * 90 * i / SR) * 5000).toInt().toShort()
        }
        return ShortBuffer.wrap(a)
    }

    @Test fun stemsSumToMixAndKeepLength() {
        val seconds = 14
        val buf = music(seconds)
        val n = seconds * SR
        val sinks = List(4) { Mem(n) }
        SpleeterSeparator(Fixed(floatArrayOf(0.5f, 1f, 0.3f, 0.8f))).separate(PcmSource(buf, n.toLong()), sinks)
        for (s in sinks) assertEquals(n, s.at)
        var maxErr = 0f
        for (i in 0 until n) {
            val l = sinks.sumOf { it.l[i].toDouble() }.toFloat()
            val r = sinks.sumOf { it.r[i].toDouble() }.toFloat()
            maxErr = maxOf(maxErr, abs(l - buf.get(i * 2) / 32768f), abs(r - buf.get(i * 2 + 1) / 32768f))
        }
        assertTrue("faixas não somam a música: $maxErr", maxErr < 1e-3f)
    }

    @Test fun pcmSinkRoundTrip() {
        val f = java.io.File.createTempFile("stem", ".pcm")
        PcmSink(f).use { it.write(floatArrayOf(0.5f, -0.25f), floatArrayOf(-1f, 2f), 0, 2) }
        val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(16384, b.get(0).toInt()); assertEquals(-32768, b.get(1).toInt())
        assertEquals(-8192, b.get(2).toInt()); assertEquals(32767, b.get(3).toInt())
        f.delete()
    }

    private fun tone(freq: Double, amp: Double, frames: Int) = ShortBuffer.wrap(ShortArray(frames * 2) {
        (sin(2 * PI * freq * (it / 2) / SR) * amp).toInt().toShort()
    })

    /** Deck com "voz" = 440 Hz, "bateria" = 100 Hz, baixo = silêncio, melodia = 1000 Hz. */
    private fun stemDeck(keyLock: Boolean): Pair<Deck, Array<ShortBuffer>> {
        val frames = SR * 10
        val v = tone(440.0, 6000.0, frames); val d = tone(100.0, 6000.0, frames)
        val b = tone(60.0, 0.0, frames); val o = tone(1000.0, 6000.0, frames)
        val mix = ShortBuffer.wrap(ShortArray(frames * 2) { (v.get(it) + d.get(it) + o.get(it)).toShort() })
        val t = LoadedTrack(mix, frames, Analysis(120.0, 0.0, 1f, FloatArray(frames / 441 + 1), FloatArray(frames / 441 + 1)))
        t.stems = arrayOf(v, d, b)
        val deck = Deck(0)
        deck.autoGain = false
        deck.keyLock = keyLock
        deck.loadTrack(t)
        return deck to arrayOf(v, d, o)
    }

    private fun corr(a: FloatArray, ref: ShortBuffer, startFrame: Int): Double {
        var s = 0.0
        for (i in 0 until a.size / 2) s += a[i * 2] * (ref.get((startFrame + i) * 2) / 32768.0)
        return s / (a.size / 2)
    }

    @Test fun mutingVocalsRemovesOnlyVocals() {
        val (d, refs) = stemDeck(false)
        val out = FloatArray(Engine.BLOCK * 2)
        d.render(out, Engine.BLOCK)
        d.playing = true
        d.stemGain[0] = 0f
        repeat(200) { d.render(out, Engine.BLOCK) }
        val pos = d.position.toInt()
        d.render(out, Engine.BLOCK)
        val power = (6000.0 / 32768) * (6000.0 / 32768) / 2
        assertTrue("voz continua: ${corr(out, refs[0], pos)}", abs(corr(out, refs[0], pos)) < power * 0.05)
        assertEquals("bateria sumiu", power, corr(out, refs[1], pos), power * 0.1)
        assertEquals("melodia sumiu", power, corr(out, refs[2], pos), power * 0.1)
    }

    @Test fun acapellaWithKeyLockStaysClean() {
        val (d, _) = stemDeck(true)
        val out = FloatArray(Engine.BLOCK * 2)
        d.render(out, Engine.BLOCK)
        d.tempo = 1.06
        d.playing = true
        d.stemGain[1] = 0f; d.stemGain[2] = 0f; d.stemGain[3] = 0f
        repeat(300) { d.render(out, Engine.BLOCK) }
        // só a voz (440 Hz) deve sobrar, no tom original
        var crossings = 0; var prev = 0f; var peak = 0f
        repeat(400) {
            d.render(out, Engine.BLOCK)
            for (i in 0 until Engine.BLOCK) {
                val v = out[i * 2]
                if (prev <= 0f && v > 0f) crossings++
                prev = v; peak = maxOf(peak, abs(v))
            }
        }
        val f = crossings / (400.0 * Engine.BLOCK / SR)
        assertEquals("tom da voz", 440.0, f, 5.0)
        assertEquals("volume da voz", 6000.0 / 32768, peak.toDouble(), 0.03)
    }
}
