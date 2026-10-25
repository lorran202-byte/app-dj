package com.lorran.virada.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Keylock por WSOLA: muda a velocidade sem mudar o tom.
 *
 * O deck continua andando na linha do tempo na velocidade escolhida ([next] recebe a posição atual).
 * Em vez de tocar o áudio "esticado", a cada [HOP] amostras escolhemos um pedaço da música perto
 * da posição atual que encaixa melhor na continuação do pedaço anterior e somamos com janela Hann.
 * Como os pedaços são tocados na velocidade original, o tom não muda.
 */
/** De onde o keylock lê as amostras (já com as partes da música misturadas). */
interface SampleSource {
    fun l(f: Int): Float
    fun r(f: Int): Float
}

class KeyLock {
    companion object {
        const val HOP = 1024            // ~23 ms
        const val WIN = HOP * 2         // ~46 ms
        const val SEEK = 512            // busca ±~11,6 ms
        private const val CMP = 768     // trecho comparado
        private const val CMP_STEP = 4
        private val window = FloatArray(WIN) { (0.5 - 0.5 * cos(2 * PI * it / WIN)).toFloat() }
    }

    private val tail = FloatArray(HOP * 2)   // 2ª metade do pedaço anterior, já com janela
    private val hop = FloatArray(HOP * 2)    // amostras prontas para tocar
    private var idx = HOP                    // posição dentro de [hop]; HOP = precisa de pedaço novo
    private var natural = 0                  // continuação natural do pedaço anterior
    private var primed = false

    /** Esquece o estado (depois de pular, carregar música ou sair do keylock). */
    fun reset() { primed = false; idx = HOP }

    private fun mono(buf: SampleSource, f: Int): Float = buf.l(f) + buf.r(f)

    private fun prime(buf: SampleSource, frames: Int, pos: Int) {
        // finge que o pedaço anterior começou em pos-HOP: a cauda é exatamente o áudio a partir de pos
        for (k in 0 until HOP) {
            val f = (pos + k).coerceIn(0, frames - 1)
            val w = window[HOP + k] / 32768f
            tail[k * 2] = buf.l(f) * w
            tail[k * 2 + 1] = buf.r(f) * w
        }
        natural = pos
        primed = true
    }

    private fun bestStart(buf: SampleSource, frames: Int, nominal: Int): Int {
        val lo = (nominal - SEEK).coerceAtLeast(0)
        val hi = (nominal + SEEK).coerceAtMost(frames - CMP - 1)
        val nat = natural.coerceIn(0, frames - CMP - 1)
        if (hi <= lo) return nominal.coerceIn(0, (frames - WIN - 1).coerceAtLeast(0))
        // busca grossa (passo 4) e depois fina (passo 1)
        var best = lo
        var bestScore = -Double.MAX_VALUE
        var c = lo
        while (c <= hi) {
            val sc = score(buf, nat, c)
            if (sc > bestScore) { bestScore = sc; best = c }
            c += 4
        }
        val fineLo = (best - 3).coerceAtLeast(lo)
        val fineHi = (best + 3).coerceAtMost(hi)
        for (cc in fineLo..fineHi) {
            val sc = score(buf, nat, cc)
            if (sc > bestScore) { bestScore = sc; best = cc }
        }
        return best
    }

    /** Correlação normalizada entre a continuação natural e o candidato. */
    private fun score(buf: SampleSource, nat: Int, cand: Int): Double {
        var xy = 0.0
        var yy = 1e-3
        var k = 0
        while (k < CMP) {
            val x = mono(buf, nat + k)
            val y = mono(buf, cand + k)
            xy += x * y
            yy += y * y
            k += CMP_STEP
        }
        return xy / sqrt(yy)
    }

    private fun nextGrain(buf: SampleSource, frames: Int, nominal: Int, gain: Float) {
        val s = bestStart(buf, frames, nominal)
        val g = gain / 32768f
        for (k in 0 until HOP) {
            val f1 = (s + k).coerceAtMost(frames - 1)
            val w1 = window[k] * g
            hop[k * 2] = tail[k * 2] * gain + buf.l(f1) * w1
            hop[k * 2 + 1] = tail[k * 2 + 1] * gain + buf.r(f1) * w1
            val f2 = (s + HOP + k).coerceAtMost(frames - 1)
            val w2 = window[HOP + k] / 32768f
            tail[k * 2] = buf.l(f2) * w2
            tail[k * 2 + 1] = buf.r(f2) * w2
        }
        natural = s + HOP
        idx = 0
    }

    var outL = 0f
    var outR = 0f

    /** Próxima amostra estéreo, com a música na posição [pos] da linha do tempo. */
    fun next(buf: SampleSource, frames: Int, pos: Double, gain: Float) {
        if (frames < WIN * 2) { outL = 0f; outR = 0f; return }
        val p = pos.roundToInt()
        if (!primed) prime(buf, frames, p)
        if (idx >= HOP) nextGrain(buf, frames, p, gain)
        outL = hop[idx * 2]
        outR = hop[idx * 2 + 1]
        idx++
    }
}
