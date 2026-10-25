package com.lorran.virada

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.audio.SR
import com.lorran.virada.data.Library
import com.lorran.virada.data.Recordings
import com.lorran.virada.data.Song
import com.lorran.virada.studio.Mashup
import com.lorran.virada.studio.Remix
import com.lorran.virada.studio.RemixStyle
import com.lorran.virada.studio.WavOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Estúdio IA: mashup de duas músicas e remix de uma, salvos em Música/Virada DJ/IA. */
class StudioController(private val vm: DjViewModel, private val lib: Library, private val recs: Recordings) {
    var show by mutableStateOf(false)
    /** 0 = mashup, 1 = remix */
    var mode by mutableIntStateOf(0)
    var vocal by mutableStateOf<Song?>(null)
    var inst by mutableStateOf<Song?>(null)
    var remixSong by mutableStateOf<Song?>(null)
    var style by mutableStateOf(RemixStyle.HOUSE)
    var bpm by mutableIntStateOf(RemixStyle.HOUSE.bpm)
    /** Qual campo o seletor de música está preenchendo: 0 voz, 1 instrumental, 2 remix; -1 fechado */
    var picking by mutableIntStateOf(-1)

    var running by mutableStateOf(false)
    var step by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var result by mutableStateOf<Song?>(null)
    var resultInfo by mutableStateOf("")
    private var job: Job? = null

    fun pickStyle(s: RemixStyle) { style = s; bpm = s.bpm }

    fun pick(s: Song) {
        when (picking) {
            0 -> vocal = s
            1 -> inst = s
            2 -> remixSong = s
        }
        picking = -1
    }

    fun canStart(): Boolean = !running && when (mode) {
        0 -> vocal != null && inst != null && vocal?.key != inst?.key
        else -> remixSong != null
    }

    fun cancel() { job?.cancel() }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60)

    /** Abre a música e separa as partes (usa o cache se já tiver separado antes). */
    private fun prepared(song: Song, label: String, from: Float, to: Float, cancelled: () -> Boolean): LoadedTrack {
        val span = to - from
        step = "Abrindo \"$label\""
        val t = lib.prepare(song) { progress = from + span * 0.15f * it }
        if (t.stems == null) {
            t.stems = lib.separate(song.key, t, {
                step = "Separando voz e instrumentos de \"$label\" (IA)"
                progress = from + span * (0.15f + 0.85f * it)
            }, cancelled)
        }
        progress = to
        return t
    }

    fun start() {
        if (!canStart()) return
        running = true
        result = null
        resultInfo = ""
        progress = 0f
        PlaybackService.ensure(vm.ctxPublic)
        job = vm.viewModelScope.launch {
            val tmp = File(vm.ctxPublic.cacheDir, "estudio").apply { mkdirs() }
            var target: com.lorran.virada.data.RecordingTarget? = null
            try {
                val song = withContext(Dispatchers.Default) {
                    val me = coroutineContext[Job]
                    val cancelled = { me?.isActive == false }
                    val prio = android.os.Process.getThreadPriority(android.os.Process.myTid())
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    try {
                        if (mode == 0) {
                            val v = vocal!!; val i = inst!!
                            val a = prepared(v, v.title, 0f, 0.35f, cancelled)
                            val b = prepared(i, i.title, 0.35f, 0.7f, cancelled)
                            val name = safe("Mashup - ${v.title} x ${i.title}") + ".wav"
                            val tg = recs.create(name, "IA"); target = tg
                            val w = WavOut(tg.channel)
                            val info = Mashup.render(a, b, tmp, w, { s, p -> step = s; progress = 0.7f + 0.3f * p }, cancelled)
                            w.close(); tg.finish(vm.ctxPublic)
                            val sh = if (info.semitones == 0) "mesmo tom" else "voz %+d semitons".format(info.semitones)
                            resultInfo = "Voz em ${info.keyVocal.name()} → instrumental em ${info.keyInst.name()} ($sh), " +
                                "BPM da voz ×%.2f".format(info.tempo)
                            song(tg, name, info.frames)
                        } else {
                            val s = remixSong!!
                            val t = prepared(s, s.title, 0f, 0.5f, cancelled)
                            val name = safe("${s.title} (Remix ${style.label} $bpm)") + ".wav"
                            val tg = recs.create(name, "IA"); target = tg
                            val w = WavOut(tg.channel)
                            val info = Remix.render(t, style, bpm.toDouble(), tmp, w, { st, p -> step = st; progress = 0.5f + 0.5f * p }, cancelled)
                            w.close(); tg.finish(vm.ctxPublic)
                            resultInfo = "${style.label} em $bpm BPM · tom ${info.key.name()}" +
                                if (style == RemixStyle.TRAP) " (808 afinado no tom)" else ""
                            song(tg, name, info.frames)
                        }
                    } finally {
                        android.os.Process.setThreadPriority(prio)
                    }
                }
                result = song
                vm.refreshSongs()
                vm.message = "Pronto: ${song.title}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                target?.discard(vm.ctxPublic)
                step = "Cancelado"
            } catch (e: OutOfMemoryError) {
                target?.discard(vm.ctxPublic)
                step = "Faltou memória. Feche outros apps e tente de novo."
            } catch (e: Throwable) {
                target?.discard(vm.ctxPublic)
                step = "Não deu certo: ${e.message ?: "erro"}"
            } finally {
                running = false
                tmp.listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun song(tg: com.lorran.virada.data.RecordingTarget, name: String, frames: Int): Song {
        val uri: Uri = tg.uri ?: Uri.fromFile(tg.file)
        return Song(
            key = "ia" + (name + System.currentTimeMillis()).hashCode().toUInt(),
            uri = uri,
            title = name.removeSuffix(".wav"),
            artist = "Virada IA",
            durationMs = frames * 1000L / SR,
            folder = "Music/Virada DJ/IA",
        )
    }
}
