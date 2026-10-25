package com.lorran.virada.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Analyzer
import com.lorran.virada.audio.Decoder
import com.lorran.virada.audio.LoadedTrack
import com.lorran.virada.stems.NullSink
import com.lorran.virada.stems.OrtMaskModel
import com.lorran.virada.stems.PcmSink
import com.lorran.virada.stems.PcmSource
import com.lorran.virada.stems.SpleeterSeparator
import com.lorran.virada.stems.StemId
import java.nio.ShortBuffer
import java.io.File

data class Song(
    val key: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** Pasta onde o arquivo está, ex.: "Music/Funk" ("" = fora de pasta conhecida). */
    val folder: String = "",
)

/** A música está em alguma das [folders] (ou em subpasta delas)? Lista vazia = todas. */
fun inFolders(folder: String, folders: Set<String>): Boolean =
    folders.isEmpty() || folders.any { folder == it || folder.startsWith("$it/") }

/** Normaliza caminhos do MediaStore: "Music/Funk/" e "/storage/emulated/0/Music/Funk/x.mp3" viram "Music/Funk". */
fun normalizeFolder(relativePath: String?, data: String?): String {
    if (!relativePath.isNullOrBlank()) return relativePath.trim('/')
    if (data.isNullOrBlank()) return ""
    val dir = data.substringBeforeLast('/', "")
    val m = Regex("^/storage/(emulated/\\d+|[^/]+)/?").find(dir)
    return (if (m != null) dir.substring(m.range.last + 1) else dir).trim('/')
}

class Library(private val ctx: Context) {
    private val cues = ctx.getSharedPreferences("cues", Context.MODE_PRIVATE)
    private val bpms = ctx.getSharedPreferences("bpm", Context.MODE_PRIVATE)
    private val cacheDir = File(ctx.cacheDir, "pcm").apply { mkdirs() }

    fun query(): List<Song> {
        val out = ArrayList<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA,
        )
        val sel = "${MediaStore.Audio.Media.DURATION} >= 20000"
        ctx.contentResolver.query(uri, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iP = c.getColumnIndexOrThrow(proj.last())
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iA)?.takeIf { it != "<unknown>" } ?: "Artista desconhecido"
                out += Song(
                    key = "m${id}_${c.getLong(iS)}_${c.getLong(iM)}",
                    uri = ContentUris.withAppendedId(uri, id),
                    title = c.getString(iT) ?: "Sem título",
                    artist = artist,
                    durationMs = c.getLong(iD),
                    folder = if (Build.VERSION.SDK_INT >= 29) normalizeFolder(c.getString(iP), null)
                    else normalizeFolder(null, c.getString(iP)),
                )
            }
        }
        return out
    }

    fun fromDocument(uri: Uri): Song {
        var name = "Arquivo"
        var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (s >= 0) size = c.getLong(s)
            }
        }
        val title = name.substringBeforeLast('.')
        return Song("d${(uri.toString() + size).hashCode().toUInt()}", uri, title, "Arquivo", 0)
    }

    fun bpmOf(key: String): Double = bpms.getString(key, null)?.toDoubleOrNull() ?: 0.0

    private fun stemFile(key: String, i: Int) = File(cacheDir, "$key.${StemId.SUFFIX[i]}.pcm")

    /** Partes já separadas no cache (voz, bateria, baixo), ou null. */
    fun loadStems(key: String, frames: Int): Array<ShortBuffer>? {
        val files = StemId.SAVED.indices.map { stemFile(key, it) }
        if (files.any { !it.exists() || it.length() != frames.toLong() * 4 }) return null
        return Array(files.size) { Decoder.map(files[it]) }
    }

    /** Separa voz, bateria, baixo e melodia no próprio celular e guarda no cache. */
    fun separate(key: String, t: LoadedTrack, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Array<ShortBuffer> {
        loadStems(key, t.frames)?.let { return it }
        val tid = android.os.Process.myTid()
        val oldPrio = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try { return separateNow(key, t, onProgress, isCancelled) }
        finally { android.os.Process.setThreadPriority(oldPrio) }
    }

    private fun separateNow(key: String, t: LoadedTrack, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Array<ShortBuffer> {
        val parts = StemId.SAVED.indices.map { File(stemFile(key, it).path + ".tmp") }
        val sinks = parts.map { PcmSink(it) }
        try {
            OrtMaskModel.open(ctx).use { model ->
                SpleeterSeparator(model).separate(
                    PcmSource(t.pcm.duplicate(), t.frames.toLong()),
                    sinks + NullSink,
                    onProgress, isCancelled,
                )
            }
        } catch (e: Throwable) {
            sinks.forEach { runCatching { it.close() } }
            parts.forEach { it.delete() }
            throw e
        }
        sinks.forEach { it.close() }
        parts.forEachIndexed { i, f -> f.renameTo(stemFile(key, i)) }
        return loadStems(key, t.frames) ?: error("Partes incompletas")
    }

    fun loadCues(key: String): DoubleArray {
        val s = cues.getString(key, null) ?: return DoubleArray(5) { -1.0 }
        val p = s.split(',').mapNotNull { it.toDoubleOrNull() }
        return DoubleArray(5) { p.getOrNull(it) ?: -1.0 }
    }

    fun saveCues(key: String, c: DoubleArray) {
        cues.edit().putString(key, c.joinToString(",")).apply()
    }

    /** Decodifica (ou pega do cache) e analisa. Roda fora da thread principal. */
    fun prepare(song: Song, onProgress: (Float) -> Unit): LoadedTrack {
        val pcm = File(cacheDir, "${song.key}.pcm")
        val ana = File(cacheDir, "${song.key}.ana")
        var analysis: Analysis? = null
        if (pcm.exists() && ana.exists()) analysis = Analysis.load(ana)
        if (analysis == null || !pcm.exists()) {
            prune()
            Decoder.decode(ctx, song.uri, pcm, onProgress)
            val buf = Decoder.map(pcm)
            analysis = Analyzer.analyze(buf, buf.limit() / 2)
            analysis.save(ana)
        }
        pcm.setLastModified(System.currentTimeMillis())
        stemFile(song.key, 0).takeIf { it.exists() }?.setLastModified(System.currentTimeMillis())
        if (analysis.bpm > 0) bpms.edit().putString(song.key, analysis.bpm.toString()).apply()
        val buf = Decoder.map(pcm)
        onProgress(1f)
        val t = LoadedTrack(buf, buf.limit() / 2, analysis)
        t.stems = loadStems(song.key, t.frames)
        return t
    }

    private fun prune() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".pcm") && f.name.count { it == '.' } == 1 }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(15).forEach { f ->
            val key = f.name.removeSuffix(".pcm")
            f.delete()
            File(cacheDir, "$key.ana").delete()
            StemId.SAVED.indices.forEach { stemFile(key, it).delete() }
        }
        // partes separadas ocupam bastante: guarda só as das 6 músicas mais recentes
        val withStems = cacheDir.listFiles { f -> f.name.endsWith(".${StemId.SUFFIX[0]}.pcm") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        withStems.drop(6).forEach { f ->
            val key = f.name.removeSuffix(".${StemId.SUFFIX[0]}.pcm")
            StemId.SAVED.indices.forEach { stemFile(key, it).delete() }
        }
        cacheDir.listFiles { f ->
            f.name.endsWith(".src") || f.name.endsWith(".tmp")
        }?.forEach { it.delete() }
    }
}
