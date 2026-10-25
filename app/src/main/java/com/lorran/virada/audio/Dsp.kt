package com.lorran.virada.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

const val SR = 44100
const val SRF = 44100f

/** Biquad estéreo (forma direta II transposta), coeficientes do RBJ cookbook. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1L = 0f
    private var z2L = 0f
    private var z1R = 0f
    private var z2R = 0f

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun lowPass(f: Float, q: Float) {
        val w = 2.0 * PI * f.coerceIn(10f, 20000f) / SR
        val c = cos(w); val al = sin(w) / (2.0 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun highPass(f: Float, q: Float) {
        val w = 2.0 * PI * f.coerceIn(10f, 20000f) / SR
        val c = cos(w); val al = sin(w) / (2.0 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun bandPass(f: Float, q: Float) {
        val w = 2.0 * PI * f.coerceIn(10f, 20000f) / SR
        val c = cos(w); val al = sin(w) / (2.0 * q)
        set(al, 0.0, -al, 1 + al, -2 * c, 1 - al)
    }

    fun reset() { z1L = 0f; z2L = 0f; z1R = 0f; z2R = 0f }

    fun l(x: Float): Float {
        val y = b0 * x + z1L
        z1L = b1 * x - a1 * y + z2L
        z2L = b2 * x - a2 * y
        return y
    }

    fun r(x: Float): Float {
        val y = b0 * x + z1R
        z1R = b1 * x - a1 * y + z2R
        z2R = b2 * x - a2 * y
        return y
    }
}

fun softClip(x: Float): Float {
    val a = abs(x)
    if (a < 0.8f) return x
    val y = 0.8f + 0.2f * tanh(((a - 0.8f) / 0.2f).toDouble()).toFloat()
    return if (x < 0) -y else y
}

/** Efeito estéreo com "cauda" (continua soando um pouco depois de desligar). */
abstract class Fx(private val tailSeconds: Float) {
    var on = false
    var outL = 0f
    var outR = 0f
    private var tail = 0

    val active: Boolean get() = on || tail > 0

    fun run(l: Float, r: Float, amt: Float, beatSec: Float) {
        if (on) tail = (tailSeconds * SR).toInt()
        else if (tail > 0) tail-- else { outL = l; outR = r; return }
        process(l, r, amt, beatSec)
    }

    protected abstract fun process(l: Float, r: Float, amt: Float, beatSec: Float)
}

class Echo : Fx(5f) {
    private val size = SR * 3
    private val bl = FloatArray(size)
    private val br = FloatArray(size)
    private var w = 0

    override fun process(l: Float, r: Float, amt: Float, beatSec: Float) {
        val d = (beatSec * 0.5f * SRF).toInt().coerceIn(200, size - 1)
        var ri = w - d
        if (ri < 0) ri += size
        val dl = bl[ri]; val dr = br[ri]
        val fb = 0.3f + 0.5f * amt
        val inL = if (on) l else 0f
        val inR = if (on) r else 0f
        bl[w] = inL * 0.6f + dr * fb
        br[w] = inR * 0.6f + dl * fb
        w++; if (w >= size) w = 0
        val wet = 0.5f + 0.4f * amt
        outL = l + dl * wet
        outR = r + dr * wet
    }
}

class Flanger : Fx(0.2f) {
    private val size = 2048
    private val bl = FloatArray(size)
    private val br = FloatArray(size)
    private var w = 0
    private var phase = 0.0

    private fun read(buf: FloatArray, delay: Float): Float {
        var p = w - delay
        while (p < 0) p += size
        val i = p.toInt()
        val f = p - i
        val a = buf[i % size]
        val b = buf[(i + 1) % size]
        return a + (b - a) * f
    }

    override fun process(l: Float, r: Float, amt: Float, beatSec: Float) {
        val rate = 1.0 / (beatSec * 8.0)
        phase += rate / SR
        if (phase >= 1.0) phase -= 1.0
        val m = (0.5 + 0.5 * sin(2 * PI * phase)).toFloat()
        val m2 = (0.5 + 0.5 * cos(2 * PI * phase)).toFloat()
        val dL = (0.0008f + 0.005f * m) * SRF
        val dR = (0.0008f + 0.005f * m2) * SRF
        val yl = read(bl, dL)
        val yr = read(br, dR)
        val fb = 0.55f
        bl[w] = (if (on) l else 0f) + yl * fb
        br[w] = (if (on) r else 0f) + yr * fb
        w++; if (w >= size) w = 0
        val mix = 0.4f + 0.6f * amt
        outL = (l + yl * mix) * 0.75f
        outR = (r + yr * mix) * 0.75f
    }
}

class Reverb : Fx(4f) {
    private class Comb(n: Int) {
        val buf = FloatArray(n); var i = 0; var store = 0f
        fun p(x: Float, fb: Float, damp: Float): Float {
            val y = buf[i]
            store = y * (1 - damp) + store * damp
            buf[i] = x + store * fb
            i++; if (i >= buf.size) i = 0
            return y
        }
    }
    private class AllPass(n: Int) {
        val buf = FloatArray(n); var i = 0
        fun p(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            i++; if (i >= buf.size) i = 0
            return b - x
        }
    }

    private val cl = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491).map { Comb(it) }
    private val cr = intArrayOf(1139, 1211, 1300, 1379, 1445, 1514).map { Comb(it) }
    private val al = intArrayOf(556, 441, 341).map { AllPass(it) }
    private val ar = intArrayOf(579, 464, 364).map { AllPass(it) }

    override fun process(l: Float, r: Float, amt: Float, beatSec: Float) {
        val input = if (on) (l + r) * 0.02f else 0f
        val fb = 0.80f + 0.15f * amt
        var sl = 0f; var sr = 0f
        for (c in cl) sl += c.p(input, fb, 0.25f)
        for (c in cr) sr += c.p(input, fb, 0.25f)
        for (a in al) sl = a.p(sl)
        for (a in ar) sr = a.p(sr)
        val wet = 1.2f + 1.5f * amt
        outL = l + sl * wet
        outR = r + sr * wet
    }
}

class Phaser : Fx(0.1f) {
    private val stages = 6
    private val xl = FloatArray(stages); private val yl = FloatArray(stages)
    private val xr = FloatArray(stages); private val yr = FloatArray(stages)
    private var phase = 0.0
    private var coefL = 0f
    private var coefR = 0f
    private var count = 0
    private var fbL = 0f
    private var fbR = 0f

    private fun coef(f: Double): Float {
        val t = tan(PI * f / SR)
        return ((t - 1) / (t + 1)).toFloat()
    }

    override fun process(l: Float, r: Float, amt: Float, beatSec: Float) {
        if (count++ % 32 == 0) {
            phase += 32.0 / (beatSec * 4.0 * SR)
            if (phase >= 1.0) phase -= 1.0
            val mL = 0.5 + 0.5 * sin(2 * PI * phase)
            val mR = 0.5 + 0.5 * sin(2 * PI * phase + 1.3)
            coefL = coef(250.0 * Math.pow(16.0, mL))
            coefR = coef(250.0 * Math.pow(16.0, mR))
        }
        var sl = l + fbL * 0.5f
        var sr = r + fbR * 0.5f
        for (s in 0 until stages) {
            val y = coefL * sl + xl[s] - coefL * yl[s]
            xl[s] = sl; yl[s] = y; sl = y
            val y2 = coefR * sr + xr[s] - coefR * yr[s]
            xr[s] = sr; yr[s] = y2; sr = y2
        }
        fbL = sl; fbR = sr
        val mix = 0.5f + 0.5f * amt
        outL = (l + sl * mix) * 0.7f
        outR = (r + sr * mix) * 0.7f
    }
}

fun rms(a: FloatArray): Float {
    var s = 0.0
    for (v in a) s += (v * v).toDouble()
    return sqrt(s / a.size.coerceAtLeast(1)).toFloat()
}
