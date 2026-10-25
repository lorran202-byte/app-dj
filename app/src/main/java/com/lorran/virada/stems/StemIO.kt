package com.lorran.virada.stems

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ShortBuffer

/** Ordem das faixas na separação (a mesma dos modelos). */
object StemId {
    const val VOCALS = 0
    const val DRUMS = 1
    const val BASS = 2
    const val OTHER = 3
    const val COUNT = 4
    /** Arquivos salvos: voz, bateria e baixo. "Melodia" = música − as três (as faixas somam a mistura). */
    val SAVED = intArrayOf(VOCALS, DRUMS, BASS)
    val SUFFIX = arrayOf("v", "d", "b")
}

interface StemSource {
    val frames: Long
    /** Lê em float por canal, com silêncio fora da música. */
    fun readFloat(startFrame: Long, count: Int, left: FloatArray, right: FloatArray)
}

interface StemSink {
    fun write(left: FloatArray, right: FloatArray, offset: Int, count: Int)
}

class PcmSource(private val buf: ShortBuffer, override val frames: Long) : StemSource {
    override fun readFloat(startFrame: Long, count: Int, left: FloatArray, right: FloatArray) {
        val k = 1f / 32768f
        for (i in 0 until count) {
            val f = startFrame + i
            if (f < 0 || f >= frames) { left[i] = 0f; right[i] = 0f }
            else {
                val a = (f * 2).toInt()
                left[i] = buf.get(a) * k
                right[i] = buf.get(a + 1) * k
            }
        }
    }
}

/** Grava PCM 16 bits estéreo cru (o mesmo formato do cache das músicas). */
class PcmSink(file: File) : StemSink, Closeable {
    private val os = BufferedOutputStream(FileOutputStream(file), 1 shl 17)
    private var bytes = ByteArray(0)

    override fun write(left: FloatArray, right: FloatArray, offset: Int, count: Int) {
        if (bytes.size < count * 4) bytes = ByteArray(count * 4)
        var w = 0
        for (i in 0 until count) {
            val l = (left[offset + i] * 32768f).toInt().coerceIn(-32768, 32767)
            val r = (right[offset + i] * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[w++] = l.toByte(); bytes[w++] = (l shr 8).toByte()
            bytes[w++] = r.toByte(); bytes[w++] = (r shr 8).toByte()
        }
        os.write(bytes, 0, w)
    }

    override fun close() = os.close()
}

/** Descarta a faixa (a "melodia" não precisa ser salva). */
object NullSink : StemSink {
    override fun write(left: FloatArray, right: FloatArray, offset: Int, count: Int) {}
}
