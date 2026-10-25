package com.lorran.virada.studio

import com.lorran.virada.audio.SR
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Escreve WAV 16 bits estéreo direto no arquivo (cabeçalho completado no final). */
class WavOut(private val ch: FileChannel) : AudioOut {
    private var frames = 0L
    private var bb = ByteBuffer.allocate(0)

    init {
        ch.write(header(0), 0)
        ch.position(44)
    }

    override fun write(l: FloatArray, r: FloatArray, count: Int) {
        if (bb.capacity() < count * 4) bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (i in 0 until count) {
            bb.putShort((l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            bb.putShort((r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        bb.flip()
        while (bb.hasRemaining()) ch.write(bb)
        frames += count
    }

    val seconds: Long get() = frames / SR

    fun close() {
        ch.write(header(frames * 4), 0)
        ch.force(true)
        ch.close()
    }

    companion object {
        fun header(data: Long): ByteBuffer {
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val d = data.coerceAtMost(0xFFFFFFF0L).toInt()
            b.put("RIFF".toByteArray()); b.putInt(36 + d); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2)
            b.putInt(SR); b.putInt(SR * 4); b.putShort(4); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(d)
            b.flip()
            return b
        }
    }
}
