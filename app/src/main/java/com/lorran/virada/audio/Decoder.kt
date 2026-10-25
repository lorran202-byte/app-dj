package com.lorran.virada.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

object Decoder {

    fun map(file: File): ShortBuffer =
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    /** Decodifica para PCM 16 bits estéreo 44,1 kHz em [out]. Lança exceção se falhar. */
    fun decode(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) { ex.release(); error("Arquivo sem áudio") }
        ex.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val raw = File(out.path + ".src")
        val os = BufferedOutputStream(FileOutputStream(raw), 1 shl 17)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var bytes = ByteArray(0)
        var guard = 0
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                            if (durUs > 0 && ex.sampleTime > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f) * 0.85f)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    guard = 0
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val c = ch.coerceAtLeast(1)
                        val frames = if (isFloat) info.size / (4 * c) else info.size / (2 * c)
                        if (bytes.size < frames * 4) bytes = ByteArray(frames * 4)
                        var w = 0
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            for (k in 0 until frames) {
                                val l = (fb.get(k * c) * 32767f).toInt().coerceIn(-32768, 32767)
                                val r = if (c > 1) (fb.get(k * c + 1) * 32767f).toInt().coerceIn(-32768, 32767) else l
                                bytes[w++] = l.toByte(); bytes[w++] = (l shr 8).toByte()
                                bytes[w++] = r.toByte(); bytes[w++] = (r shr 8).toByte()
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            for (k in 0 until frames) {
                                val l = sb.get(k * c).toInt()
                                val r = if (c > 1) sb.get(k * c + 1).toInt() else l
                                bytes[w++] = l.toByte(); bytes[w++] = (l shr 8).toByte()
                                bytes[w++] = r.toByte(); bytes[w++] = (r shr 8).toByte()
                            }
                        }
                        os.write(bytes, 0, w)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (inDone) {
                    if (++guard > 500) outDone = true // decodificador travado: usa o que já saiu
                }
            }
        } finally {
            os.close()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }

        if (srcRate == SR) {
            if (out.exists()) out.delete()
            if (!raw.renameTo(out)) { raw.copyTo(out, true); raw.delete() }
        } else {
            resample(raw, out, srcRate)
            raw.delete()
        }
        onProgress(0.9f)
    }

    private fun resample(src: File, dst: File, srcRate: Int) {
        val s = map(src)
        val inFrames = s.limit() / 2
        if (inFrames < 2) { src.copyTo(dst, true); return }
        val outFrames = (inFrames.toLong() * SR / srcRate).toInt()
        val step = srcRate.toDouble() / SR
        BufferedOutputStream(FileOutputStream(dst), 1 shl 17).use { os ->
            val chunk = ByteArray(4096 * 4)
            var w = 0
            var pos = 0.0
            for (o in 0 until outFrames) {
                val i = pos.toInt().coerceAtMost(inFrames - 2)
                val f = (pos - i).toFloat()
                val l0 = s.get(i * 2); val r0 = s.get(i * 2 + 1)
                val l1 = s.get(i * 2 + 2); val r1 = s.get(i * 2 + 3)
                val l = (l0 + (l1 - l0) * f).toInt()
                val r = (r0 + (r1 - r0) * f).toInt()
                chunk[w++] = l.toByte(); chunk[w++] = (l shr 8).toByte()
                chunk[w++] = r.toByte(); chunk[w++] = (r shr 8).toByte()
                if (w == chunk.size) { os.write(chunk); w = 0 }
                pos += step
            }
            if (w > 0) os.write(chunk, 0, w)
        }
    }
}
