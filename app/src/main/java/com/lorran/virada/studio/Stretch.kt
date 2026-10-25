package com.lorran.virada.studio

import com.lorran.virada.audio.KeyLock
import com.lorran.virada.audio.SampleSource
import com.lorran.virada.stems.PcmSink
import java.io.File
import java.nio.ShortBuffer
import java.util.concurrent.CancellationException

/** Fontes de áudio usadas no estúdio (valores na escala de 16 bits, como o PCM). */
class PcmSrc(private val buf: ShortBuffer, val frames: Int) : SampleSource {
    override fun l(f: Int): Float = if (f in 0 until frames) buf.get(f * 2).toFloat() else 0f
    override fun r(f: Int): Float = if (f in 0 until frames) buf.get(f * 2 + 1).toFloat() else 0f
}

/** Soma/subtrai fontes: a·x + b·y + … */
class MixSrc(private val parts: List<Pair<Float, SampleSource>>) : SampleSource {
    override fun l(f: Int): Float { var s = 0f; for ((g, x) in parts) s += g * x.l(f); return s }
    override fun r(f: Int): Float { var s = 0f; for ((g, x) in parts) s += g * x.r(f); return s }
}

/** Lê [src] com a velocidade multiplicada por [rate] (muda tom e tempo juntos, como um vinil). */
class ResampledSrc(private val src: SampleSource, private val rate: Double, srcFrames: Int) : SampleSource {
    val frames = (srcFrames / rate).toInt()
    override fun l(f: Int): Float { val p = f * rate; val i = p.toInt(); val t = (p - i).toFloat(); return src.l(i) + (src.l(i + 1) - src.l(i)) * t }
    override fun r(f: Int): Float { val p = f * rate; val i = p.toInt(); val t = (p - i).toFloat(); return src.r(i) + (src.r(i + 1) - src.r(i)) * t }
}

object Stretch {
    /**
     * Muda o tempo por [tempo] (1,1 = 10% mais rápido) e o tom por [pitch] (fator; 2^(n/12) para n semitons),
     * independentes um do outro. Grava PCM 16 bits em [out] e devolve o número de quadros.
     */
    fun toFile(
        src: SampleSource, frames: Int, tempo: Double, pitch: Double, out: File,
        progress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false },
    ): Int {
        val rs = if (pitch == 1.0) null else ResampledSrc(src, pitch, frames)
        val s: SampleSource = rs ?: src
        val sFrames = rs?.frames ?: frames
        val rate = tempo / pitch
        val outFrames = (sFrames / rate).toInt()
        val kl = KeyLock()
        val block = 4096
        val l = FloatArray(block); val r = FloatArray(block)
        PcmSink(out).use { sink ->
            var o = 0
            while (o < outFrames) {
                if (isCancelled()) throw CancellationException()
                val n = minOf(block, outFrames - o)
                for (i in 0 until n) {
                    val pos = (o + i) * rate
                    kl.next(s, sFrames, pos, 1f)
                    l[i] = kl.outL; r[i] = kl.outR
                }
                sink.write(l, r, 0, n)
                o += n
                if ((o / block) % 16 == 0) progress(o.toFloat() / outFrames)
            }
        }
        progress(1f)
        return outFrames
    }
}
