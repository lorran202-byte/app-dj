package com.lorran.virada.studio

import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.SR
import com.lorran.virada.audio.SampleSource
import com.lorran.virada.stems.Fft
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tonalidade: nota (0 = Dó … 11 = Si) e se é menor. */
data class MusicKey(val root: Int, val minor: Boolean) {
    /** Tônica da relativa maior (Lá menor → Dó maior), para comparar tons. */
    val majorRoot: Int get() = if (minor) (root + 3) % 12 else root

    fun name(): String = NAMES[root] + if (minor) " menor" else " maior"

    companion object {
        val NAMES = arrayOf("Dó", "Dó#", "Ré", "Mi♭", "Mi", "Fá", "Fá#", "Sol", "Lá♭", "Lá", "Si♭", "Si")
    }
}

object Music {
    private val MAJ = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MIN = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    /** Tonalidade pelo perfil de Krumhansl sobre o cromagrama (até ~200 janelas espalhadas na música). */
    fun key(src: SampleSource, frames: Int): MusicKey {
        val n = 8192
        val fft = Fft(n)
        val win = Fft.hann(n)
        val pc = IntArray(n / 2 + 1) { -1 }
        for (k in pc.indices) {
            val f = k.toDouble() * SR / n
            if (f in 55.0..2000.0) pc[k] = (((12 * log2(f / 440.0)).roundToInt() + 69) % 12 + 12) % 12
        }
        val tot = FloatArray(12)
        val windows = ((frames - n) / n).coerceIn(0, 200)
        if (windows == 0) return MusicKey(0, false)
        val step = (frames - n) / windows
        val re = FloatArray(n); val im = FloatArray(n)
        for (w in 0 until windows) {
            val o = w * step
            for (k in 0 until n) { re[k] = (src.l(o + k) + src.r(o + k)) * win[k]; im[k] = 0f }
            fft.transform(re, im)
            val c = FloatArray(12)
            for (k in pc.indices) {
                val p = pc[k]; if (p < 0) continue
                c[p] += re[k] * re[k] + im[k] * im[k]
            }
            for (j in 0 until 12) tot[j] += ln(1f + 10f * sqrt(c[j]) / 32768f)
        }
        var best = -2f; var br = 0; var bm = false
        for (r in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MIN else MAJ
            val rot = FloatArray(12) { prof[((it - r) % 12 + 12) % 12] }
            val c = corr(tot, rot)
            if (c > best) { best = c; br = r; bm = minor }
        }
        return MusicKey(br, bm)
    }

    private fun corr(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat(); val mb = b.average().toFloat()
        var num = 0f; var da = 0f; var db = 0f
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
        return num / (sqrt(da * db) + 1e-9f)
    }

    /** Quantos semitons subir/descer [from] para combinar com [to] (de −5 a +6, o menor caminho). */
    fun semitoneShift(from: MusicKey, to: MusicKey): Int {
        var s = ((to.majorRoot - from.majorRoot) % 12 + 12) % 12
        if (s > 6) s -= 12
        return s
    }

    fun pitchFactor(semitones: Int): Double = 2.0.pow(semitones / 12.0)

    fun beatFrames(a: Analysis) = if (a.bpm > 0) 60.0 / a.bpm * SR else SR * 0.5

    /**
     * Qual batida (0–3) é o "um" do compasso: a que tem mais grave (bumbo) somando a música toda.
     * Devolve a posição do primeiro "um".
     */
    fun firstDownbeat(a: Analysis): Double {
        if (a.bpm <= 0) return 0.0
        val b = beatFrames(a)
        val sum = DoubleArray(4)
        var k = 0
        while (true) {
            val f = a.firstBeat + k * b
            val i = (f / Analysis.PEAK_BLOCK).toInt()
            if (i >= a.lows.size) break
            if (i >= 0) {
                var m = 0f
                for (j in i until minOf(i + 4, a.lows.size)) if (a.lows[j] > m) m = a.lows[j]
                sum[k % 4] += m.toDouble()
            }
            k++
        }
        val phase = sum.indices.maxBy { sum[it] }
        return a.firstBeat + phase * b
    }

    /** Primeira batida (contada a partir de [grid0], de 4 em 4) em que [src] tem som forte. */
    fun firstLoudBar(src: SampleSource, frames: Int, grid0: Double, beat: Double, threshold: Float = 0.25f): Int {
        val bar = beat * 4
        val nBars = ((frames - grid0) / bar).toInt()
        if (nBars <= 0) return 0
        val e = FloatArray(nBars)
        for (k in 0 until nBars) {
            val s = (grid0 + k * bar).toInt()
            var acc = 0.0
            var j = 0
            while (j < bar.toInt()) {
                val f = s + j
                if (f in 0 until frames) { val v = src.l(f) + src.r(f); acc += (v * v).toDouble() }
                j += 64
            }
            e[k] = sqrt(acc).toFloat()
        }
        val max = e.maxOrNull() ?: 0f
        for (k in 0 until nBars) if (e[k] >= max * threshold) return k
        return 0
    }

    /** Arredonda para o compasso anterior. */
    fun floorBar(pos: Double, grid0: Double, beat: Double): Double = grid0 + floor((pos - grid0) / (beat * 4)) * beat * 4
}
