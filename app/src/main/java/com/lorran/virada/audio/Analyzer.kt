package com.lorran.virada.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

class Analysis(
    val bpm: Double,
    val firstBeat: Double,
    val gain: Float,
    val peaks: FloatArray,
    val lows: FloatArray,
) {
    fun save(f: File) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(2)
            o.writeDouble(bpm); o.writeDouble(firstBeat); o.writeFloat(gain)
            o.writeInt(peaks.size)
            for (v in peaks) o.writeFloat(v)
            for (v in lows) o.writeFloat(v)
        }
    }

    companion object {
        const val PEAK_BLOCK = 441

        fun load(f: File): Analysis? = try {
            DataInputStream(f.inputStream().buffered()).use { i ->
                if (i.readInt() != 2) return null
                val bpm = i.readDouble(); val fb = i.readDouble(); val g = i.readFloat()
                val n = i.readInt()
                val p = FloatArray(n) { i.readFloat() }
                val l = FloatArray(n) { i.readFloat() }
                Analysis(bpm, fb, g, p, l)
            }
        } catch (e: Exception) { null }
    }
}

object Analyzer {
    private const val ENV_BLOCK = 220
    private const val ENV_RATE = SR.toDouble() / ENV_BLOCK

    fun analyze(buf: ShortBuffer, frames: Int): Analysis {
        val nPk = frames / Analysis.PEAK_BLOCK + 1
        val peaks = FloatArray(nPk)
        val lows = FloatArray(nPk)
        val nEnv = frames / ENV_BLOCK + 1
        val energy = FloatArray(nEnv)

        var lp = 0f
        var lp2 = 0f
        var sumSq = 0.0
        var counted = 0L
        for (i in 0 until frames) {
            val m = (buf.get(i * 2) + buf.get(i * 2 + 1)) / 65536f
            lp += (m - lp) * 0.021f
            lp2 += (lp - lp2) * 0.021f
            val am = abs(m)
            val pk = i / Analysis.PEAK_BLOCK
            if (am > peaks[pk]) peaks[pk] = am
            val al = abs(lp2)
            if (al > lows[pk]) lows[pk] = al
            val e = i / ENV_BLOCK
            energy[e] += lp2 * lp2 * 6f + m * m
            if (am > 0.002f) { sumSq += (m * m).toDouble(); counted++ }
        }

        // fluxo de energia (onde as batidas começam)
        val onset = FloatArray(nEnv)
        var prev = 0f
        for (k in 0 until nEnv) {
            val v = ln(1f + energy[k] * 200f)
            val d = v - prev
            onset[k] = if (d > 0) d else 0f
            prev = v
        }
        var mean = 0.0
        for (v in onset) mean += v
        mean /= nEnv.coerceAtLeast(1)

        val rms = if (counted > 0) sqrt(sumSq / counted).toFloat() else 0f
        val gain = if (rms > 1e-4f) (0.16f / rms).coerceIn(0.6f, 1.8f) else 1f

        var bpm = 0.0
        var firstBeat = 0.0
        if (nEnv > ENV_RATE * 20) {
            val from = (nEnv * 0.08).toInt()
            val to = (from + (ENV_RATE * 150).toInt()).coerceAtMost(nEnv)
            val minLag = (ENV_RATE * 60 / 190).toInt()
            val maxLag = (ENV_RATE * 60 / 62).toInt()
            var best = -1.0
            var bestLag = 0
            for (lag in minLag..maxLag) {
                var s = 0.0
                var k = from
                while (k + lag < to) {
                    s += (onset[k] - mean) * (onset[k + lag] - mean)
                    k++
                }
                val b = 60 * ENV_RATE / lag
                val oct = ln(b / 122.0) / ln(2.0)
                val w = exp(-0.5 * (oct / 0.55) * (oct / 0.55))
                val sc = s * w
                if (sc > best) { best = sc; bestLag = lag }
            }
            if (best > 0 && bestLag > 0) {
                var b0 = 60 * ENV_RATE / bestLag
                while (b0 < 72) b0 *= 2
                while (b0 > 185) b0 /= 2
                val r = refine(onset, b0)
                bpm = r.first
                if (abs(bpm - round(bpm)) < 0.07) {
                    val snapped = round(bpm)
                    val ph = bestPhase(onset, snapped)
                    bpm = snapped
                    firstBeat = ph.first
                } else firstBeat = r.second
                val beat = 60.0 / bpm * SR
                firstBeat *= ENV_BLOCK
                while (firstBeat - beat >= 0) firstBeat -= beat
            }
        }
        return Analysis(round(bpm * 100) / 100, firstBeat, gain, peaks, lows)
    }

    /** Retorna (fase em amostras do envelope, pontuação). */
    private fun bestPhase(onset: FloatArray, bpm: Double): Pair<Double, Double> {
        val period = 60 * ENV_RATE / bpm
        var bestScore = -1.0
        var bestPh = 0.0
        val steps = period.toInt().coerceAtLeast(1)
        for (ph in 0 until steps) {
            var s = 0.0
            var t = ph.toDouble()
            while (t < onset.size - 1) {
                val i = t.toInt()
                s += onset[i] + onset[i + 1] * 0.5
                t += period
            }
            if (s > bestScore) { bestScore = s; bestPh = ph.toDouble() }
        }
        return bestPh to bestScore
    }

    private fun refine(onset: FloatArray, b0: Double): Pair<Double, Double> {
        var bestBpm = b0
        var bestScore = -1.0
        var bestPh = 0.0
        var b = b0 - 1.5
        while (b <= b0 + 1.5) {
            val (ph, sc) = bestPhase(onset, b)
            if (sc > bestScore) { bestScore = sc; bestBpm = b; bestPh = ph }
            b += 0.02
        }
        return bestBpm to bestPh
    }
}
