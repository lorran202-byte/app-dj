package com.lorran.virada.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class AudioEngineTest {

    /** Faixa sintética: bumbo em cada batida + chimbal no contratempo. */
    private fun beatTrack(bpm: Double, seconds: Double, offsetSec: Double): ShortBuffer {
        val frames = (seconds * SR).toInt()
        val arr = ShortArray(frames * 2)
        val beat = 60.0 / bpm
        val rnd = Random(1)
        for (i in 0 until frames) {
            val t = i.toDouble() / SR
            var s = 0.0
            val tb = t - offsetSec
            if (tb >= 0) {
                val inBeat = tb % beat
                s += sin(2 * PI * (50 + 80 * exp(-inBeat / 0.03)) * inBeat) * exp(-inBeat / 0.12) * 0.8
                val off = (tb + beat / 2) % beat
                s += (rnd.nextDouble() * 2 - 1) * exp(-off / 0.01) * 0.15
            }
            s += sin(2 * PI * 220 * t) * 0.05
            val v = (s * 32767 * 0.7).toInt().coerceIn(-32768, 32767).toShort()
            arr[i * 2] = v; arr[i * 2 + 1] = v
        }
        return ShortBuffer.wrap(arr)
    }

    private fun checkBpm(bpm: Double, offset: Double) {
        val buf = beatTrack(bpm, 70.0, offset)
        val a = Analyzer.analyze(buf, buf.limit() / 2)
        assertEquals("BPM detectado para $bpm", bpm, a.bpm, 0.3)
        val beatFrames = 60.0 / bpm * SR
        val expected = (offset * SR) % beatFrames
        var diff = abs(a.firstBeat - expected)
        diff = minOf(diff, beatFrames - diff)
        assertTrue("primeira batida fora ($diff quadros) para $bpm", diff < SR * 0.03)
    }

    @Test fun bpm128() = checkBpm(128.0, 0.35)
    @Test fun bpm100() = checkBpm(100.0, 0.10)
    @Test fun bpm140() = checkBpm(140.0, 0.62)
    @Test fun bpm92() = checkBpm(92.0, 0.0)

    private fun deckWith(buf: ShortBuffer, bpm: Double = 120.0): Deck {
        val d = Deck(0)
        val frames = buf.limit() / 2
        d.loadTrack(LoadedTrack(buf, frames, Analysis(bpm, 0.0, 1f, FloatArray(frames / 441 + 1), FloatArray(frames / 441 + 1))))
        return d
    }

    private fun run(d: Deck, blocks: Int): FloatArray {
        val out = FloatArray(Engine.BLOCK * 2)
        repeat(blocks) { d.render(out, Engine.BLOCK) }
        return out
    }

    @Test fun playAdvancesAtTempo() {
        val d = deckWith(beatTrack(120.0, 20.0, 0.0))
        run(d, 1)
        d.playing = true
        d.tempo = 1.08
        run(d, 400)
        val expected = 400 * Engine.BLOCK * 1.08
        assertEquals(expected, d.position, 300.0)
    }

    @Test fun loopStaysInside() {
        val d = deckWith(beatTrack(120.0, 20.0, 0.0))
        run(d, 1)
        d.loopStart = 1000.0; d.loopEnd = 23050.0; d.loopOn = true
        d.playing = true
        var minP = Double.MAX_VALUE; var maxP = 0.0
        val out = FloatArray(Engine.BLOCK * 2)
        repeat(2000) {
            d.render(out, Engine.BLOCK)
            if (d.position < minP) minP = d.position
            if (d.position > maxP) maxP = d.position
        }
        assertTrue("loop passou do fim: $maxP", maxP < 23050.0 + 2)
        assertTrue("loop não voltou: $minP", minP < 23050.0)
        assertTrue(d.playing)
    }

    @Test fun reverseKeepsPlace() {
        val d = deckWith(beatTrack(120.0, 30.0, 0.0))
        run(d, 1)
        d.playing = true
        run(d, 300)
        val before = d.position
        d.fxMask = 1 shl FxId.REVERSE
        run(d, 200)
        assertTrue("reverso não voltou", d.position < before)
        d.fxMask = 0
        run(d, 1)
        val expected = before + 201 * Engine.BLOCK
        assertEquals(expected, d.position, Engine.BLOCK * 2.0)
    }

    @Test fun stopsAtEnd() {
        val d = deckWith(beatTrack(120.0, 2.0, 0.0))
        run(d, 1)
        d.playing = true
        run(d, 600)
        assertTrue(!d.playing)
        assertTrue(d.ended)
    }

    @Test fun flatEqIsTransparent() {
        val frames = SR * 5
        val arr = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 300 * i / SR) * 0.3 * 32767).toInt().toShort()
            arr[i * 2] = v; arr[i * 2 + 1] = v
        }
        val buf = ShortBuffer.wrap(arr)
        val d = deckWith(buf)
        d.autoGain = false
        run(d, 1)
        d.playing = true
        run(d, 100)
        val out = FloatArray(Engine.BLOCK * 2)
        d.render(out, Engine.BLOCK)
        var maxDiff = 0f
        val start = d.position - Engine.BLOCK
        for (i in 0 until Engine.BLOCK) {
            val src = buf.get(((start + i).toInt()) * 2) / 32768f
            maxDiff = maxOf(maxDiff, abs(out[i * 2] - src))
        }
        assertTrue("EQ no meio altera o som: $maxDiff", maxDiff < 0.02f)
    }

    @Test fun killLowRemovesBass() {
        val frames = SR * 3
        val arr = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 60 * i / SR) * 16000).toInt().toShort()
            arr[i * 2] = v; arr[i * 2 + 1] = v
        }
        val d = deckWith(ShortBuffer.wrap(arr))
        d.autoGain = false
        d.eqLow = 0f
        run(d, 1)
        d.playing = true
        val out = run(d, 200)
        assertTrue("grave não sumiu: ${rms(out)}", rms(out) < 0.06f)
    }

    @Test fun effectsStayStable() {
        val rnd = Random(9)
        for (fx in 0 until FxId.COUNT) {
            val frames = SR * 12
            val arr = ShortArray(frames * 2) { (rnd.nextGaussian() * 6000).toInt().coerceIn(-32768, 32767).toShort() }
            val d = deckWith(ShortBuffer.wrap(arr), 128.0)
            d.fxAmount = 1f
            d.filter = if (fx % 2 == 0) -0.95f else 0.95f
            run(d, 1)
            d.playing = true
            d.fxMask = 1 shl fx
            val out = FloatArray(Engine.BLOCK * 2)
            var peak = 0f
            repeat(1500) {
                d.render(out, Engine.BLOCK)
                for (v in out) {
                    assertTrue("efeito $fx gerou NaN", !v.isNaN())
                    if (abs(v) > peak) peak = abs(v)
                }
            }
            assertTrue("efeito $fx estourou: $peak", peak < 6f)
        }
    }

    @Test fun samplerPadsSound() {
        val s = Sampler()
        for (i in 0 until 8) {
            s.trigger(i)
            val out = FloatArray(Engine.BLOCK * 2)
            var peak = 0f
            repeat(620) {
                java.util.Arrays.fill(out, 0f)
                s.mix(out, Engine.BLOCK)
                for (v in out) if (abs(v) > peak) peak = abs(v)
            }
            assertTrue("pad $i mudo", peak > 0.05f)
            assertTrue("pad $i alto demais: $peak", peak < 1.3f)
            s.stop(i)
            s.mix(FloatArray(Engine.BLOCK * 2), Engine.BLOCK)
        }
    }

    @Test fun recorderWritesValidWav() {
        val f = java.io.File.createTempFile("mix", ".wav")
        val ch = java.io.RandomAccessFile(f, "rw").channel
        val r = Recorder(ch)
        val block = FloatArray(Engine.BLOCK * 2) { sin(it * 0.05).toFloat() * 0.5f }
        repeat(100) { r.push(block, Engine.BLOCK) }
        r.close()
        val bytes = f.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(100 * Engine.BLOCK * 4, dataSize)
        assertEquals(44 + dataSize, bytes.size)
        f.delete()
    }
}
