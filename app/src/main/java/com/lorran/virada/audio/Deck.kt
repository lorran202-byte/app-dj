package com.lorran.virada.audio

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class LoadedTrack(val pcm: ShortBuffer, val frames: Int, val analysis: Analysis) {
    /** Voz, bateria e baixo separados (null até separar). A melodia é a música menos as três. */
    @Volatile var stems: Array<ShortBuffer>? = null
}

object FxId {
    const val ECHO = 0
    const val FLANGER = 1
    const val REVERB = 2
    const val PHASER = 3
    const val GATE = 4
    const val ROLL = 5
    const val REVERSE = 6
    const val BRAKE = 7
    const val COUNT = 8
}

/**
 * Um deck. Só a thread de áudio escreve em [position]; a interface manda comandos
 * (seekTo, scratch, loop) por campos voláteis.
 */
class Deck(val index: Int) {
    @Volatile var track: LoadedTrack? = null
    @Volatile var position = 0.0
        private set
    @Volatile var playing = false
    @Volatile var tempo = 1.0
    @Volatile var bend = 0.0
    @Volatile var trim = 1f
    @Volatile var autoGain = true
    /** Keylock: mudar a velocidade sem mudar o tom. */
    @Volatile var keyLock = false

    @Volatile var eqLow = 0.5f
    @Volatile var eqMid = 0.5f
    @Volatile var eqHigh = 0.5f
    @Volatile var filter = 0f          // -1 (passa-baixa) .. 1 (passa-alta)
    @Volatile var fxAmount = 0.5f
    @Volatile var fxMask = 0

    @Volatile var seekTo = -1.0
    @Volatile var scratching = false
    @Volatile var scratchTarget = 0.0

    @Volatile var loopOn = false
    @Volatile var loopStart = 0.0
    @Volatile var loopEnd = 0.0

    @Volatile var level = 0f
        private set
    @Volatile var ended = false

    private var curRate = 0.0
    private var lastFilter = 99f
    private val lpf = Biquad()
    private val hpf = Biquad()
    private val iso = ThreeBand()
    private val echo = Echo()
    private val flanger = Flanger()
    private val reverb = Reverb()
    private val phaser = Phaser()
    private val kl = KeyLock()

    /** Volume de cada parte: voz, bateria, baixo, melodia (0 = mudo, 1 = normal). */
    val stemGain = FloatArray(4) { 1f }
    private val sgFrom = FloatArray(4) { 1f }
    private val sgTo = FloatArray(4) { 1f }
    private var cur: ShortBuffer? = null
    private var stV: ShortBuffer? = null
    private var stD: ShortBuffer? = null
    private var stB: ShortBuffer? = null
    private var useStems = false
    private var cMix = 1f; private var cV = 0f; private var cD = 0f; private var cB = 0f

    /** Lê a amostra já com as partes misturadas: melodia·música + (g−melodia)·cada parte. */
    private val src = object : SampleSource {
        override fun l(f: Int): Float = sample(f * 2)
        override fun r(f: Int): Float = sample(f * 2 + 1)
    }

    private fun sample(a: Int): Float {
        val m = cur!!.get(a).toFloat()
        if (!useStems) return m
        return cMix * m + cV * stV!!.get(a) + cD * stD!!.get(a) + cB * stB!!.get(a)
    }

    private fun setCoefs(i: Int, n: Int) {
        val t = (i + 1).toFloat() / n
        val gv = sgFrom[0] + (sgTo[0] - sgFrom[0]) * t
        val gd = sgFrom[1] + (sgTo[1] - sgFrom[1]) * t
        val gb = sgFrom[2] + (sgTo[2] - sgFrom[2]) * t
        val go = sgFrom[3] + (sgTo[3] - sgFrom[3]) * t
        cMix = go; cV = gv - go; cD = gd - go; cB = gb - go
    }
    private var klMix = 0f

    private var gLow = 1f; private var gMid = 1f; private var gHigh = 1f
    private var gate = 1f
    private var brakeMul = 1.0
    private var slipActive = false
    private var slipPos = 0.0
    private var rollOn = false
    private var rollStart = 0.0
    private var reverseOn = false

    val beatFrames: Double
        get() {
            val b = track?.analysis?.bpm ?: 0.0
            return if (b > 1.0) 60.0 / b * SR else SR * 0.5
        }

    val isActive: Boolean
        get() = playing || scratching || abs(curRate) > 1e-5 || echo.active || reverb.active

    fun loadTrack(t: LoadedTrack?) {
        playing = false
        kl.reset()
        klMix = 0f
        loopOn = false
        track = t
        seekTo = 0.0
        ended = false
    }

    private fun eqGain(v: Float): Float =
        if (v <= 0.5f) (v / 0.5f).pow(2) else 1f + (v - 0.5f) / 0.5f

    private fun updateParams() {
        val f = filter
        if (f != lastFilter) {
            lastFilter = f
            val a = abs(f)
            if (f < -0.03f) lpf.lowPass((18000.0 * (80.0 / 18000.0).pow(a.toDouble())).toFloat(), 1.1f)
            else if (f > 0.03f) hpf.highPass((25.0 * (9000.0 / 25.0).pow(a.toDouble())).toFloat(), 1.1f)
            else { lpf.reset(); hpf.reset() }
        }
        gLow = eqGain(eqLow); gMid = eqGain(eqMid); gHigh = eqGain(eqHigh)
    }

    /** Gera [n] quadros estéreo intercalados em [out] (antes do fader). */
    fun render(out: FloatArray, n: Int) {
        val t = track
        if (t == null) {
            java.util.Arrays.fill(out, 0, n * 2, 0f)
            level = 0f
            curRate = 0.0
            return
        }
        val buf = t.pcm
        cur = buf
        val stems = t.stems
        var ramping = false
        for (k in 0 until 4) {
            sgFrom[k] = sgTo[k]
            val target = if (stems == null) 1f else stemGain[k]
            sgTo[k] = sgFrom[k] + (target - sgFrom[k]) * 0.35f
            if (abs(sgTo[k] - target) < 0.002f) sgTo[k] = target
            if (sgTo[k] != sgFrom[k]) ramping = true
        }
        useStems = stems != null && (ramping || sgTo.any { it != 1f })
        if (useStems) { stV = stems!![0]; stD = stems[1]; stB = stems[2] }
        if (!useStems) { cMix = 1f; cV = 0f; cD = 0f; cB = 0f }
        val maxF = (t.frames - 2).coerceAtLeast(0).toDouble()
        val gain = trim * (if (autoGain) t.analysis.gain else 1f)
        val bf = beatFrames
        val beatSec = (bf / SR).toFloat()
        val fb = t.analysis.firstBeat

        val s = seekTo
        if (s >= 0) { position = s.coerceIn(0.0, maxF); seekTo = -1.0; ended = false; slipPos = position }

        updateParams()
        val mask = fxMask
        echo.on = mask and (1 shl FxId.ECHO) != 0
        flanger.on = mask and (1 shl FxId.FLANGER) != 0
        reverb.on = mask and (1 shl FxId.REVERB) != 0
        phaser.on = mask and (1 shl FxId.PHASER) != 0
        val gateOn = mask and (1 shl FxId.GATE) != 0
        val rollNow = mask and (1 shl FxId.ROLL) != 0
        val revNow = mask and (1 shl FxId.REVERSE) != 0
        val brakeNow = mask and (1 shl FxId.BRAKE) != 0
        val amt = fxAmount

        var p = position
        // entrada/saída do modo "slip" (roll e reverso não perdem o lugar da música)
        if ((rollNow || revNow) && !slipActive) { slipActive = true; slipPos = p }
        if (rollNow && !rollOn) { rollOn = true; rollStart = p }
        if (!rollNow) rollOn = false
        reverseOn = revNow
        if (!rollNow && !revNow && slipActive) { slipActive = false; p = slipPos.coerceIn(0.0, maxF) }
        if (!brakeNow) brakeMul = 1.0
        val rollLen = bf * when {
            amt < 0.25f -> 0.5
            amt < 0.5f -> 0.25
            amt < 0.75f -> 0.125
            else -> 0.0625
        }
        val brakeStep = 1.0 / ((0.4 + amt * 2.6) * SR)

        val f = filter
        val useLp = f < -0.03f
        val useHp = f > 0.03f
        var peak = 0f
        val loopActive = loopOn
        val ls = loopStart
        val le = loopEnd

        for (i in 0 until n) {
            if (useStems) setCoefs(i, n)
            if (scratching) {
                val diff = scratchTarget - p
                curRate = (diff / 220.0).coerceIn(-8.0, 8.0)
            } else {
                var target = if (playing) tempo * (1.0 + bend) else 0.0
                if (brakeNow && playing) {
                    brakeMul = (brakeMul - brakeStep).coerceAtLeast(0.0)
                    target *= brakeMul
                }
                if (reverseOn) target = -target
                curRate += (target - curRate) * 0.01
            }

            // keylock só vale tocando para frente; scratch, reverso e freio mudam o tom de propósito
            val wantKl = keyLock && !scratching && !reverseOn && !brakeNow && curRate > 0.3
            klMix += ((if (wantKl) 1f else 0f) - klMix) * 0.004f
            if (!wantKl && klMix < 0.001f) { if (klMix > 0f) kl.reset(); klMix = 0f }

            var l = 0f
            var r = 0f
            if (klMix < 0.999f) {
                val i0 = p.toInt()
                val fr = (p - i0).toFloat()
                val a = i0 * 2
                val l0 = sample(a); val r0 = sample(a + 1)
                val l1 = sample(a + 2); val r1 = sample(a + 3)
                l = (l0 + (l1 - l0) * fr) * (gain / 32768f)
                r = (r0 + (r1 - r0) * fr) * (gain / 32768f)
            }
            if (klMix > 0f) {
                kl.next(src, t.frames, p, gain)
                l = l * (1f - klMix) + kl.outL * klMix
                r = r * (1f - klMix) + kl.outR * klMix
            }

            val prev = p
            p += curRate
            if (slipActive && playing) slipPos += tempo
            if (rollOn && p >= rollStart + rollLen) p -= rollLen
            if (loopActive && curRate > 0 && prev < le && p >= le) p -= (le - ls)
            if (p >= maxF) {
                p = maxF
                if (!scratching && playing) { playing = false; ended = true }
            }
            if (p < 0) p = 0.0

            // isolador de 3 bandas (Linkwitz-Riley: volume plano com tudo no meio, corte total no zero)
            iso.process(l, r, gLow, gMid, gHigh)
            l = iso.outL; r = iso.outR

            if (useLp) { l = lpf.l(l); r = lpf.r(r) }
            else if (useHp) { l = hpf.l(l); r = hpf.r(r) }

            phaser.run(l, r, amt, beatSec); l = phaser.outL; r = phaser.outR
            flanger.run(l, r, amt, beatSec); l = flanger.outL; r = flanger.outR
            echo.run(l, r, amt, beatSec); l = echo.outL; r = echo.outR
            reverb.run(l, r, amt, beatSec); l = reverb.outL; r = reverb.outR

            if (gateOn || gate < 0.999f) {
                val div = when { amt < 0.33f -> 2.0; amt < 0.66f -> 4.0; else -> 8.0 }
                var ph = (p - fb) / bf * div
                ph -= floor(ph)
                val gt = if (!gateOn || ph < 0.5) 1f else 0.05f
                gate += (gt - gate) * 0.006f
                l *= gate; r *= gate
            }

            out[i * 2] = l
            out[i * 2 + 1] = r
            val m = if (abs(l) > abs(r)) abs(l) else abs(r)
            if (m > peak) peak = m
        }
        position = p
        level = if (peak > level) peak else level * 0.85f + peak * 0.15f
    }
}
