package com.lorran.virada

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lorran.virada.audio.Engine
import com.lorran.virada.audio.FxId
import com.lorran.virada.audio.Recorder
import com.lorran.virada.audio.SR
import com.lorran.virada.data.Library
import com.lorran.virada.data.RecordingInfo
import com.lorran.virada.data.RecordingTarget
import com.lorran.virada.data.Recordings
import com.lorran.virada.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class DeckUi(val index: Int) {
    var song by mutableStateOf<Song?>(null)
    var loading by mutableFloatStateOf(-1f)
    var bpm by mutableDoubleStateOf(0.0)
    var pitch by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var sync by mutableStateOf(false)
    var volume by mutableFloatStateOf(0.85f)
    var eqHigh by mutableFloatStateOf(0.5f)
    var eqMid by mutableFloatStateOf(0.5f)
    var eqLow by mutableFloatStateOf(0.5f)
    var filter by mutableFloatStateOf(0.5f)
    var fxAmount by mutableFloatStateOf(0.5f)
    var fxMask by mutableIntStateOf(0)
    val cues = mutableStateListOf(-1.0, -1.0, -1.0, -1.0)
    var mainCue = 0.0
    var loopBeats by mutableFloatStateOf(0f)
    var posSec by mutableDoubleStateOf(0.0)
    var durSec by mutableDoubleStateOf(0.0)
    var tab by mutableIntStateOf(0)
    var level by mutableFloatStateOf(0f)
    var tempo by mutableDoubleStateOf(1.0)
}

enum class Sort { TITLE, ARTIST, BPM }

class DjViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    val lib = Library(app)
    private val recs = Recordings(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val decks = listOf(DeckUi(0), DeckUi(1))

    var songs by mutableStateOf<List<Song>>(emptyList())
    var songsLoaded by mutableStateOf(false)
    var hasPermission by mutableStateOf(false)
    var libraryFor by mutableIntStateOf(-1)
    var search by mutableStateOf("")
    var sort by mutableStateOf(Sort.TITLE)
    var confirmLoad by mutableStateOf<Pair<Int, Song>?>(null)

    var crossfader by mutableFloatStateOf(0f)
    var sharpCurve by mutableStateOf(prefs.getBoolean("sharp", false))
    var pitchRange by mutableFloatStateOf(prefs.getFloat("range", 0.08f))
    var splitCue by mutableStateOf(false)
    var cueDeck by mutableIntStateOf(0)
    var master by mutableFloatStateOf(prefs.getFloat("master", 0.9f))
    var padVolume by mutableFloatStateOf(prefs.getFloat("pads", 0.8f))
    var autoGain by mutableStateOf(prefs.getBoolean("autogain", true))
    var masterLevel by mutableFloatStateOf(0f)

    var showPads by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showRecordings by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var recSeconds by mutableLongStateOf(0L)
    var recList by mutableStateOf(recs.list())
    var message by mutableStateOf<String?>(null)

    private var recTarget: RecordingTarget? = null

    init {
        Engine.start()
        applySettings()
        viewModelScope.launch {
            while (isActive) {
                poll()
                delay(60)
            }
        }
    }

    private fun applySettings() {
        Engine.sharpCurve = sharpCurve
        Engine.master = master
        Engine.sampler.volume = padVolume
        Engine.splitCue = splitCue
        Engine.cueDeck = cueDeck
        Engine.decks.forEach { it.autoGain = autoGain }
        prefs.edit()
            .putBoolean("sharp", sharpCurve).putFloat("range", pitchRange)
            .putFloat("master", master).putFloat("pads", padVolume)
            .putBoolean("autogain", autoGain).apply()
    }

    fun updateSettings(block: DjViewModel.() -> Unit) {
        block()
        applySettings()
        decks.forEach { if (!it.sync) Engine.decks[it.index].tempo = 1.0 + it.pitch * pitchRange }
    }

    private fun poll() {
        for (ui in decks) {
            val d = Engine.decks[ui.index]
            if (ui.playing != d.playing) ui.playing = d.playing
            ui.posSec = d.position / SR
            ui.level = d.level
            if (!d.loopOn && ui.loopBeats != 0f) ui.loopBeats = 0f
            if (ui.sync) applySync(ui.index)
            ui.tempo = d.tempo
        }
        masterLevel = Engine.masterLevel
        Engine.recorder?.let { recSeconds = it.frames / SR }
    }

    // ---------- biblioteca ----------
    fun onPermission(granted: Boolean) {
        hasPermission = granted
        if (granted) refreshSongs()
    }

    fun refreshSongs() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try { lib.query() } catch (e: Exception) { emptyList() }
            }
            songs = list
            songsLoaded = true
        }
    }

    fun visibleSongs(): List<Song> {
        val q = search.trim().lowercase()
        val base = if (q.isEmpty()) songs else songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
        return when (sort) {
            Sort.TITLE -> base
            Sort.ARTIST -> base.sortedBy { it.artist.lowercase() }
            Sort.BPM -> base.sortedBy { lib.bpmOf(it.key).let { b -> if (b <= 0) 999.0 else b } }
        }
    }

    fun requestLoad(i: Int, song: Song) {
        libraryFor = -1
        if (Engine.decks[i].playing) confirmLoad = i to song else load(i, song)
    }

    fun loadDocument(i: Int, uri: Uri) {
        libraryFor = -1
        val s = try { lib.fromDocument(uri) } catch (e: Exception) { null } ?: return
        requestLoad(i, s)
    }

    fun load(i: Int, song: Song) {
        confirmLoad = null
        val ui = decks[i]
        if (ui.loading >= 0f) return
        ui.loading = 0f
        viewModelScope.launch {
            try {
                Engine.decks[i].loadTrack(null)
                var last = 0f
                val t = withContext(Dispatchers.IO) {
                    lib.prepare(song) { p -> if (p - last > 0.02f || p >= 1f) { last = p; ui.loading = p } }
                }
                val d = Engine.decks[i]
                d.loadTrack(t)
                ui.song = song
                ui.bpm = t.analysis.bpm
                ui.durSec = t.frames.toDouble() / SR
                ui.loopBeats = 0f
                ui.sync = false
                val c = lib.loadCues(song.key)
                for (k in 0 until 4) ui.cues[k] = c[k]
                ui.mainCue = c[4].coerceAtLeast(0.0)
                d.seekTo = ui.mainCue
                d.tempo = 1.0 + ui.pitch * pitchRange
            } catch (e: Exception) {
                message = "Não consegui abrir essa música"
            } finally {
                ui.loading = -1f
            }
        }
    }

    // ---------- transporte ----------
    fun togglePlay(i: Int) {
        val d = Engine.decks[i]
        if (d.track == null) { libraryFor = i; return }
        if (d.playing) d.playing = false
        else {
            if (decks[i].sync) alignPhase(i)
            d.playing = true
            PlaybackService.ensure(ctx)
        }
        decks[i].playing = d.playing
    }

    private var previewing = BooleanArray(2)

    fun cueDown(i: Int) {
        val d = Engine.decks[i]
        val ui = decks[i]
        if (d.track == null) return
        if (d.playing) {
            d.playing = false
            d.seekTo = ui.mainCue
        } else if (abs(d.position - ui.mainCue) < SR * 0.02) {
            previewing[i] = true
            d.playing = true
        } else {
            ui.mainCue = snap(i, d.position)
            d.seekTo = ui.mainCue
            saveCues(i)
        }
    }

    fun cueUp(i: Int) {
        if (!previewing[i]) return
        previewing[i] = false
        val d = Engine.decks[i]
        d.playing = false
        d.seekTo = decks[i].mainCue
    }

    fun seekFraction(i: Int, f: Float) {
        val d = Engine.decks[i]
        val t = d.track ?: return
        d.seekTo = (f.coerceIn(0f, 1f) * t.frames).toDouble()
    }

    // ---------- tempo / sync ----------
    fun setPitch(i: Int, v: Float) {
        val ui = decks[i]
        ui.pitch = v
        ui.sync = false
        Engine.decks[i].tempo = 1.0 + v * pitchRange
    }

    fun resetPitch(i: Int) = setPitch(i, 0f)

    private fun syncTempo(i: Int): Double? {
        val me = Engine.decks[i]
        val other = Engine.decks[1 - i]
        val mb = me.track?.analysis?.bpm ?: 0.0
        val ob = other.track?.analysis?.bpm ?: 0.0
        if (mb <= 0 || ob <= 0) return null
        var t = ob * other.tempo / mb
        while (t > 1.45) t /= 2
        while (t < 0.7) t *= 2
        return t
    }

    private fun applySync(i: Int) {
        val t = syncTempo(i) ?: return
        val d = Engine.decks[i]
        if (abs(d.tempo - t) > 1e-6) d.tempo = t
        decks[i].pitch = ((t - 1.0) / pitchRange).toFloat().coerceIn(-1f, 1f)
    }

    private fun alignPhase(i: Int) {
        val me = Engine.decks[i]
        val other = Engine.decks[1 - i]
        if (!other.playing) return
        val ma = me.track?.analysis ?: return
        val oa = other.track?.analysis ?: return
        if (ma.bpm <= 0 || oa.bpm <= 0) return
        val bo = other.beatFrames
        val bm = me.beatFrames
        var po = (other.position - oa.firstBeat) / bo
        po -= floor(po)
        var pm = (me.position - ma.firstBeat) / bm
        pm -= floor(pm)
        var delta = po - pm
        if (delta > 0.5) delta -= 1.0
        if (delta < -0.5) delta += 1.0
        me.seekTo = (me.position + delta * bm).coerceAtLeast(0.0)
    }

    fun toggleSync(i: Int) {
        val ui = decks[i]
        if (ui.sync) { ui.sync = false; return }
        if (syncTempo(i) == null) {
            message = "Carregue músicas nos dois decks para sincronizar"
            return
        }
        decks[1 - i].sync = false
        ui.sync = true
        applySync(i)
        alignPhase(i)
    }

    // ---------- mixer ----------
    fun setVolume(i: Int, v: Float) { decks[i].volume = v; Engine.deckVolume[i] = v }
    fun setEq(i: Int, band: Int, v: Float) {
        val ui = decks[i]; val d = Engine.decks[i]
        when (band) {
            0 -> { ui.eqHigh = v; d.eqHigh = v }
            1 -> { ui.eqMid = v; d.eqMid = v }
            else -> { ui.eqLow = v; d.eqLow = v }
        }
    }
    fun setFilter(i: Int, v: Float) {
        decks[i].filter = v
        val f = (v - 0.5f) * 2f
        Engine.decks[i].filter = if (abs(f) < 0.04f) 0f else f
    }
    fun moveCrossfader(v: Float) { crossfader = v; Engine.crossfader = v }
    fun chooseCueDeck(i: Int) {
        cueDeck = i
        if (!splitCue) splitCue = true
        applySettings()
    }

    // ---------- efeitos ----------
    fun toggleFx(i: Int, fx: Int) {
        val ui = decks[i]
        ui.fxMask = ui.fxMask xor (1 shl fx)
        Engine.decks[i].fxMask = ui.fxMask
    }
    fun holdFx(i: Int, fx: Int, on: Boolean) {
        val ui = decks[i]
        ui.fxMask = if (on) ui.fxMask or (1 shl fx) else ui.fxMask and (1 shl fx).inv()
        Engine.decks[i].fxMask = ui.fxMask
    }
    fun setFxAmount(i: Int, v: Float) { decks[i].fxAmount = v; Engine.decks[i].fxAmount = v }
    fun isMomentary(fx: Int) = fx == FxId.ROLL || fx == FxId.REVERSE || fx == FxId.BRAKE

    // ---------- cues e loops ----------
    private fun snap(i: Int, pos: Double): Double {
        val d = Engine.decks[i]
        val a = d.track?.analysis ?: return pos
        if (a.bpm <= 0) return pos
        val grid = d.beatFrames / 4
        val k = ((pos - a.firstBeat) / grid).roundToInt()
        val s = a.firstBeat + k * grid
        return if (abs(s - pos) < SR * 0.04) s.coerceAtLeast(0.0) else pos
    }

    private fun saveCues(i: Int) {
        val ui = decks[i]
        val key = ui.song?.key ?: return
        lib.saveCues(key, doubleArrayOf(ui.cues[0], ui.cues[1], ui.cues[2], ui.cues[3], ui.mainCue))
    }

    fun hotCue(i: Int, n: Int) {
        val d = Engine.decks[i]
        if (d.track == null) return
        val ui = decks[i]
        if (ui.cues[n] < 0) {
            ui.cues[n] = snap(i, d.position)
            saveCues(i)
        } else {
            d.seekTo = ui.cues[n]
            if (!d.playing) { d.playing = true; PlaybackService.ensure(ctx) }
        }
    }

    fun deleteCue(i: Int, n: Int) {
        decks[i].cues[n] = -1.0
        saveCues(i)
    }

    fun loop(i: Int, beats: Float) {
        val d = Engine.decks[i]
        val a = d.track?.analysis ?: return
        val ui = decks[i]
        if (d.loopOn && ui.loopBeats == beats) {
            d.loopOn = false; ui.loopBeats = 0f; return
        }
        val beat = d.beatFrames
        if (d.loopOn) {
            d.loopEnd = d.loopStart + beats * beat
            ui.loopBeats = beats
            return
        }
        val grid = beat * minOf(beats.toDouble(), 1.0)
        val pos = d.position
        val start = if (a.bpm > 0) a.firstBeat + floor((pos - a.firstBeat) / grid) * grid else pos
        d.loopStart = start.coerceAtLeast(0.0)
        d.loopEnd = d.loopStart + beats * beat
        d.loopOn = true
        ui.loopBeats = beats
    }

    // ---------- prato ----------
    fun scratchStart(i: Int) {
        val d = Engine.decks[i]
        if (d.track == null) return
        d.scratchTarget = d.position
        d.scratching = true
    }

    fun scratchMove(i: Int, radians: Float) {
        val d = Engine.decks[i]
        val t = d.track ?: return
        d.scratchTarget = (d.scratchTarget + radians * FRAMES_PER_RAD).coerceIn(0.0, (t.frames - 2).toDouble())
    }

    fun scratchEnd(i: Int) { Engine.decks[i].scratching = false }

    fun bend(i: Int, v: Float) { Engine.decks[i].bend = v.toDouble() }

    // ---------- pads ----------
    fun pad(i: Int) {
        Engine.sampler.trigger(i)
        PlaybackService.ensure(ctx)
    }

    // ---------- gravação ----------
    fun toggleRecord() {
        if (!recording) {
            try {
                val t = recs.create()
                recTarget = t
                Engine.recorder = Recorder(t.channel)
                recording = true
                recSeconds = 0
                PlaybackService.ensure(ctx)
            } catch (e: Exception) {
                message = "Não consegui começar a gravação"
            }
        } else {
            val r = Engine.recorder
            Engine.recorder = null
            recording = false
            val t = recTarget
            recTarget = null
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        r?.close()
                        t?.finish(ctx)
                        if (t != null && r != null) {
                            recs.add(
                                RecordingInfo(
                                    t.name,
                                    t.uri?.toString() ?: t.file?.absolutePath.orEmpty(),
                                    r.frames / SR,
                                    System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
                recList = recs.list()
                message = "Mixagem salva em Música/Virada DJ"
            }
        }
    }

    fun anyPlaying() = Engine.decks.any { it.playing } || recording

    companion object {
        /** Uma volta do prato = 1,8 s de música (como um vinil a 33 rpm). */
        const val FRAMES_PER_RAD = 1.8 * SR / (2 * kotlin.math.PI)
    }
}
