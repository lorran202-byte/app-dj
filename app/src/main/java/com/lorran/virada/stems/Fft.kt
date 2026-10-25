package com.lorran.virada.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complexa radix-2, in-place. A inversa já divide por n. */
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "n precisa ser potência de 2" }
        for (i in 0 until n / 2) {
            val a = 2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var r = 0
            var x = i
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) {
                re[i] *= s
                im[i] *= s
            }
        }
    }

    companion object {
        /** Janela de Hann periódica (igual à do Spleeter / numpy hanning(n+1)[:-1]). */
        fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    }
}
