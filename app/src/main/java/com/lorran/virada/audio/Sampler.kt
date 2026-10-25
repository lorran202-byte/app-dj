package com.lorran.virada.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** 8 pads com sons gerados pelo próprio app. */
class Sampler {
    val names = listOf("Buzina", "Palmas", "Kick", "Caixa", "Sirene", "Laser", "Subida", "Impacto")
    private val sounds: Array<FloatArray> = arrayOf(
        DrumKit.airHorn(), DrumKit.claps(), DrumKit.kick(), DrumKit.snare(),
        DrumKit.siren(), DrumKit.laser(), DrumKit.riser(), DrumKit.impact()
    )
    private val pos = IntArray(sounds.size) { -1 }
    @Volatile private var triggers = 0
    @Volatile var volume = 0.8f

    fun trigger(i: Int) { synchronized(this) { triggers = triggers or (1 shl i) } }

    fun stop(i: Int) { synchronized(this) { triggers = triggers or (1 shl (i + 16)) } }

    val active: Boolean get() = triggers != 0 || pos.any { it >= 0 }

    fun isPlaying(i: Int) = pos[i] >= 0

    /** Soma os pads em [out] (estéreo intercalado). */
    fun mix(out: FloatArray, n: Int) {
        val t: Int
        synchronized(this) { t = triggers; triggers = 0 }
        if (t != 0) for (i in sounds.indices) {
            if (t and (1 shl i) != 0) pos[i] = 0
            if (t and (1 shl (i + 16)) != 0) pos[i] = -1
        }
        val v = volume
        for (s in sounds.indices) {
            var p = pos[s]
            if (p < 0) continue
            val snd = sounds[s]
            for (i in 0 until n) {
                if (p >= snd.size) { p = -1; break }
                val x = snd[p++] * v
                out[i * 2] += x
                out[i * 2 + 1] += x
            }
            pos[s] = p
        }
    }
}
