package com.lorran.virada.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.lorran.virada.audio.Analysis
import com.lorran.virada.audio.Analyzer
import com.lorran.virada.audio.Decoder
import com.lorran.virada.audio.LoadedTrack
import java.io.File

data class Song(
    val key: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

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
        )
        val sel = "${MediaStore.Audio.Media.DURATION} >= 20000"
        ctx.contentResolver.query(uri, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iA)?.takeIf { it != "<unknown>" } ?: "Artista desconhecido"
                out += Song(
                    key = "m${id}_${c.getLong(iS)}_${c.getLong(iM)}",
                    uri = ContentUris.withAppendedId(uri, id),
                    title = c.getString(iT) ?: "Sem título",
                    artist = artist,
                    durationMs = c.getLong(iD),
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
        if (analysis.bpm > 0) bpms.edit().putString(song.key, analysis.bpm.toString()).apply()
        val buf = Decoder.map(pcm)
        onProgress(1f)
        return LoadedTrack(buf, buf.limit() / 2, analysis)
    }

    private fun prune() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(15).forEach { f ->
            f.delete()
            File(f.path.removeSuffix(".pcm") + ".ana").delete()
        }
        cacheDir.listFiles { f -> f.name.endsWith(".src") }?.forEach { it.delete() }
    }
}
