package com.lorran.virada.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Grava o master em WAV 16 bits estéreo numa thread separada da thread de áudio. */
class Recorder(private val channel: FileChannel) {
    private val queue = ArrayBlockingQueue<ShortArray>(2048)
    @Volatile private var open = true
    @Volatile var frames = 0L
        private set
    private var dataBytes = 0L

    private val thread = Thread({ loop() }, "virada-rec").apply { start() }

    init {
        channel.write(header(0), 0)
        channel.position(44)
    }

    /** Chamado pela thread de áudio. */
    fun push(src: FloatArray, n: Int) {
        val s = ShortArray(n * 2)
        for (i in 0 until n * 2) s[i] = (src[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        queue.offer(s)
    }

    private fun loop() {
        val bb = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
        while (open || queue.isNotEmpty()) {
            val s = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            bb.clear()
            if (bb.capacity() < s.size * 2) continue
            for (v in s) bb.putShort(v)
            bb.flip()
            while (bb.hasRemaining()) channel.write(bb)
            dataBytes += s.size * 2
            frames += s.size / 2
        }
    }

    fun close() {
        open = false
        thread.join(5000)
        channel.write(header(dataBytes), 0)
        channel.force(true)
        channel.close()
    }

    private fun header(data: Long): ByteBuffer {
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
