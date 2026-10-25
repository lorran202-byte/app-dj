package com.lorran.virada.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Engine {
    const val BLOCK = 256

    val decks = arrayOf(Deck(0), Deck(1))
    val sampler by lazy { Sampler() }

    @Volatile var crossfader = 0f      // -1 = só A, 1 = só B
    @Volatile var sharpCurve = false
    @Volatile var master = 0.9f
    @Volatile var deckVolume = floatArrayOf(0.85f, 0.85f)
    @Volatile var splitCue = false
    @Volatile var cueDeck = 0
    @Volatile var recorder: Recorder? = null
    @Volatile var masterLevel = 0f
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "virada-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun gains(): Pair<Float, Float> {
        val x = (crossfader + 1f) / 2f
        return if (sharpCurve) {
            val a = if (x < 0.92f) 1f else ((1f - x) / 0.08f).coerceIn(0f, 1f)
            val b = if (x > 0.08f) 1f else (x / 0.08f).coerceIn(0f, 1f)
            a to b
        } else {
            // potência constante, mas sem buraco no meio
            val a = cos(x * PI / 2).toFloat() * 1.41f
            val b = sin(x * PI / 2).toFloat() * 1.41f
            a.coerceAtMost(1f) to b.coerceAtMost(1f)
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val size = maxOf(min, BLOCK * 8 * 3) * 2
        val b = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = buildTrack()
        val a = FloatArray(BLOCK * 2)
        val b = FloatArray(BLOCK * 2)
        val out = FloatArray(BLOCK * 2)
        var idle = 0
        var paused = true
        while (running) {
            val busy = decks[0].isActive || decks[1].isActive || sampler.active || recorder != null
            if (busy) idle = 0 else idle++
            if (idle > 700) {
                if (!paused) { track.pause(); track.flush(); paused = true }
                masterLevel = 0f
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
                continue
            }
            if (paused) { track.play(); paused = false }

            decks[0].render(a, BLOCK)
            decks[1].render(b, BLOCK)
            val (ga, gb) = gains()
            val va = deckVolume[0].let { it * it }
            val vb = deckVolume[1].let { it * it }
            val ma = ga * va
            val mb = gb * vb
            for (i in 0 until BLOCK * 2) out[i] = a[i] * ma + b[i] * mb
            sampler.mix(out, BLOCK)
            val m = master
            var peak = 0f
            for (i in 0 until BLOCK * 2) {
                val v = softClip(out[i] * m)
                out[i] = v
                val av = if (v < 0) -v else v
                if (av > peak) peak = av
            }
            masterLevel = if (peak > masterLevel) peak else masterLevel * 0.85f + peak * 0.15f
            recorder?.push(out, BLOCK)

            if (splitCue) {
                val cue = if (cueDeck == 0) a else b
                for (i in 0 until BLOCK) {
                    val ml = (out[i * 2] + out[i * 2 + 1]) * 0.5f
                    val cl = softClip((cue[i * 2] + cue[i * 2 + 1]) * 0.5f * m)
                    out[i * 2] = cl
                    out[i * 2 + 1] = ml
                }
            }
            track.write(out, 0, BLOCK * 2, AudioTrack.WRITE_BLOCKING)
        }
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }
}
