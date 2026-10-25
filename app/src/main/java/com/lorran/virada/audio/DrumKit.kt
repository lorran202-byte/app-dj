package com.lorran.virada.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Sons de bateria e efeitos gerados pelo próprio app (sem samples de terceiros). */
object DrumKit {
    // ---------- síntese ----------
    private fun buf(sec: Double) = FloatArray((sec * SR).toInt())
    private fun saw(ph: Double) = (2.0 * (ph - Math.floor(ph + 0.5))).toFloat()

    fun airHorn(): FloatArray {
        val blasts = listOf(0.0 to 0.16, 0.22 to 0.16, 0.44 to 0.75)
        val out = buf(1.3)
        val freqs = doubleArrayOf(349.0, 352.5, 523.0, 700.0)
        val ph = DoubleArray(freqs.size)
        for (i in out.indices) {
            val t = i.toDouble() / SR
            var env = 0.0
            for ((st, len) in blasts) if (t >= st && t < st + len) {
                val lt = t - st
                env = minOf(1.0, lt / 0.008) * minOf(1.0, (st + len - t) / 0.03)
            }
            if (env <= 0) continue
            val vib = 1.0 + 0.004 * sin(2 * PI * 7 * t)
            var s = 0.0
            for (k in freqs.indices) {
                ph[k] += freqs[k] * vib / SR
                s += saw(ph[k]) * (if (k < 2) 0.5 else 0.25)
            }
            out[i] = (Math.tanh(s * 1.6) * 0.55 * env).toFloat()
        }
        return out
    }

    fun claps(): FloatArray {
        val out = buf(0.4)
        val rnd = Random(7)
        val bp = Biquad().apply { bandPass(1300f, 1.2f) }
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val burst = listOf(0.0, 0.011, 0.022).sumOf { st -> if (t >= st) exp(-(t - st) / 0.006) else 0.0 }
            val env = if (t < 0.03) burst else exp(-(t - 0.03) / 0.09) * 0.9
            out[i] = bp.l((rnd.nextFloat() * 2 - 1) * env.toFloat()) * 2.2f
        }
        return out
    }

    fun kick(): FloatArray {
        val out = buf(0.6)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val f = 45 + 110 * exp(-t / 0.045)
            ph += f / SR
            val env = exp(-t / 0.22)
            val click = if (t < 0.004) (1 - t / 0.004) * 0.4 else 0.0
            out[i] = ((sin(2 * PI * ph) * env + click) * 0.95).toFloat()
        }
        return out
    }

    fun snare(): FloatArray {
        val out = buf(0.35)
        val rnd = Random(11)
        val hp = Biquad().apply { highPass(1400f, 0.7f) }
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            ph += 185.0 / SR
            val tone = sin(2 * PI * ph) * exp(-t / 0.05) * 0.5
            val noise = hp.l(rnd.nextFloat() * 2 - 1) * exp(-t / 0.11).toFloat()
            out[i] = ((tone + noise * 0.8) * 0.8).toFloat()
        }
        return out
    }

    fun siren(): FloatArray {
        val out = buf(2.2)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val lfo = abs(((t * 2.2) % 1.0) * 2 - 1)
            val f = 650 + 750 * lfo
            ph += f / SR
            val sq = Math.tanh(sin(2 * PI * ph) * 3) * 0.35
            val env = minOf(1.0, t / 0.05) * minOf(1.0, (2.2 - t) / 0.15)
            out[i] = (sq * env).toFloat()
        }
        return out
    }

    fun laser(): FloatArray {
        val out = buf(0.75)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val lt = t % 0.25
            val f = 200 + 2800 * exp(-lt / 0.05)
            ph += f / SR
            val env = exp(-lt / 0.12)
            out[i] = (saw(ph) * 0.35 * env).toFloat()
        }
        return out
    }

    fun riser(len: Double = 3.2): FloatArray {
        val out = buf(len)
        val rnd = Random(3)
        val hp = Biquad()
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            val x = t / len
            if (i % 64 == 0) hp.highPass((200 * 30.0.pow(x)).toFloat(), 1.4f)
            val noise = hp.l(rnd.nextFloat() * 2 - 1)
            ph += (180 + 700 * x * x) / SR
            val tone = saw(ph) * 0.15
            val env = x.pow(1.6) * minOf(1.0, (len - t) / 0.02)
            out[i] = ((noise * 0.45 + tone) * env).toFloat()
        }
        return out
    }

    fun impact(): FloatArray {
        val out = buf(2.0)
        val rnd = Random(5)
        val lp = Biquad().apply { lowPass(900f, 0.8f) }
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            ph += (32 + 50 * exp(-t / 0.25)) / SR
            val sub = sin(2 * PI * ph) * exp(-t / 0.7)
            val noise = lp.l(rnd.nextFloat() * 2 - 1) * exp(-t / 0.18).toFloat()
            out[i] = (Math.tanh((sub * 0.9 + noise * 0.8) * 1.4) * 0.8).toFloat()
        }
        return out
    }

    /** Chimbal fechado (ou aberto, com [open]). */
    fun hat(open: Boolean = false): FloatArray {
        val out = buf(if (open) 0.35 else 0.07)
        val rnd = Random(if (open) 21 else 17)
        val hp = Biquad().apply { highPass(7500f, 0.8f) }
        val tau = if (open) 0.12 else 0.018
        for (i in out.indices) {
            val t = i.toDouble() / SR
            out[i] = hp.l(rnd.nextFloat() * 2 - 1) * exp(-t / tau).toFloat() * 0.5f
        }
        return out
    }

    /** Aro da caixa (rimshot curto). */
    fun rim(): FloatArray {
        val out = buf(0.08)
        val bp = Biquad().apply { bandPass(1800f, 3f) }
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            ph += 520.0 / SR
            out[i] = (bp.l((sin(2 * PI * ph) * exp(-t / 0.015)).toFloat()) * 3.5f)
        }
        return out
    }

    /** Tambor grave (usado no funk). */
    fun tom(freq: Double = 110.0): FloatArray {
        val out = buf(0.35)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            ph += (freq + freq * 0.6 * exp(-t / 0.03)) / SR
            out[i] = (sin(2 * PI * ph) * exp(-t / 0.12) * 0.75).toFloat()
        }
        return out
    }

    /** Grave 808 afinado na nota [freq], com leve distorção. */
    fun sub808(freq: Double, seconds: Double = 0.7): FloatArray {
        val out = buf(seconds)
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SR
            ph += (freq * (1 + 0.5 * exp(-t / 0.02))) / SR
            val env = minOf(1.0, t / 0.004) * exp(-t / (seconds * 0.6))
            out[i] = (Math.tanh(sin(2 * PI * ph) * 1.8) * env * 0.8).toFloat()
        }
        return out
    }
}
