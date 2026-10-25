package com.lorran.virada

import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.SR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoDjTest {
    /** Análise falsa: silêncio em [0, silentStart), som até soundEnd, fade baixinho até o fim. */
    private fun analysis(bpm: Double, frames: Int, silentStart: Int, soundEnd: Int, firstBeat: Double = 0.0): Analysis {
        val n = frames / Analysis.PEAK_BLOCK + 1
        val p = FloatArray(n) { i ->
            val f = i * Analysis.PEAK_BLOCK
            when { f < silentStart -> 0.001f; f < soundEnd -> 0.8f; else -> 0.05f }
        }
        return Analysis(bpm, firstBeat, 1f, p, p.copyOf())
    }

    @Test fun planSyncsSimilarBpmAndEndsAtLastLoudPart() {
        val frames = SR * 200
        val out = analysis(128.0, frames, 0, SR * 185, firstBeat = 1000.0)
        val inn = analysis(125.0, SR * 180, SR * 2, SR * 170)
        val p = MixPlanner.plan(out, frames, 1.0, SR * 30.0, inn, 16)
        assertTrue(p.sync)
        assertEquals(128.0 / 125.0, p.inTempo, 1e-6)
        val bo = 60.0 / 128 * SR
        assertEquals(16 * bo, p.length, 1.0)
        // começa no início de um compasso
        val bars = (p.start - 1000.0) / (bo * 4)
        assertEquals(Math.round(bars).toDouble(), bars, 1e-6)
        // termina perto do fim do som forte (antes do fade)
        assertTrue(p.start + p.length <= SR * 185.0 + 1)
        assertTrue(p.start + p.length > SR * 185.0 - bo * 4)
        // a entrada pula o silêncio do começo e cai numa batida
        assertTrue(p.inStart >= SR * 2.0 - 60.0 / 125 * SR && p.inStart <= SR * 2.0)
    }

    @Test fun halfTimeCountsAsMatch() {
        val t = MixPlanner.matchTempo(140.0, 1.0, 70.0)!!
        assertEquals(1.0, t, 1e-9)
        val t2 = MixPlanner.matchTempo(128.0, 1.0, 64.5)!!
        assertTrue(abs(t2 - 1.0) < 0.01)
    }

    @Test fun veryDifferentBpmUsesShortEchoOut() {
        val frames = SR * 200
        val out = analysis(128.0, frames, 0, SR * 190)
        val inn = analysis(95.0, SR * 180, 0, SR * 170)
        val p = MixPlanner.plan(out, frames, 1.0, 0.0, inn, 32)
        assertFalse(p.sync)
        assertEquals(1.0, p.inTempo, 0.0)
        assertTrue(p.length <= 8 * 60.0 / 128 * SR + 1)
    }

    @Test fun lateStartBeginsOnNextBeat() {
        val frames = SR * 60
        val out = analysis(120.0, frames, 0, SR * 58)
        val inn = analysis(120.0, SR * 60, 0, SR * 58)
        val pos = SR * 57.3
        val p = MixPlanner.plan(out, frames, 1.0, pos, inn, 16)
        assertTrue(p.start >= pos)
        assertTrue(p.start - pos <= 60.0 / 120 * SR)
        assertTrue(p.start + p.length <= frames)
    }

    @Test fun automationSwapsBassAndCrossfades() {
        var prevXf = -1f
        for (k in 0..100) {
            val x = k / 100f
            val a = MixPlanner.automation(x, true)
            assertTrue(a.crossfader >= prevXf)
            prevXf = a.crossfader
            // nunca os dois graves cheios ao mesmo tempo
            assertFalse(a.outLow > 0.3f && a.inLow > 0.3f)
        }
        assertEquals(0f, MixPlanner.automation(0f, true).crossfader, 0f)
        assertEquals(1f, MixPlanner.automation(1f, true).crossfader, 0f)
        assertTrue(MixPlanner.automation(1f, false).outEcho)
        assertEquals(1f, MixPlanner.automation(1f, false).crossfader, 0f)
    }
}
