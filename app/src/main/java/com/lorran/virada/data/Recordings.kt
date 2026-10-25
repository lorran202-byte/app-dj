package com.lorran.virada.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingInfo(val name: String, val uri: String, val seconds: Long, val date: Long)

class RecordingTarget(
    val name: String,
    val uri: Uri?,
    val file: File?,
    val channel: FileChannel,
    private val pfd: ParcelFileDescriptor?,
) {
    /** Apaga o arquivo (trabalho cancelado ou com erro). */
    fun discard(ctx: Context) {
        try { channel.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        uri?.let { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
        file?.delete()
    }

    fun finish(ctx: Context) {
        try { pfd?.close() } catch (_: Exception) {}
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, v, null, null)
        }
    }
}

class Recordings(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recordings", Context.MODE_PRIVATE)

    fun create(): RecordingTarget {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return create("Mix_$stamp.wav", "")
    }

    /** Cria um WAV em Música/Virada DJ/[sub] (visível na biblioteca e em outros apps). */
    fun create(name: String, sub: String): RecordingTarget {
        val folder = if (sub.isEmpty()) "Virada DJ" else "Virada DJ/$sub"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + folder)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = ctx.contentResolver.insert(col, v) ?: error("Não consegui criar o arquivo")
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("Não consegui abrir o arquivo")
            val ch = FileOutputStream(pfd.fileDescriptor).channel
            return RecordingTarget(name, uri, null, ch, pfd)
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), folder).apply { mkdirs() }
        val f = File(dir, name)
        val ch = RandomAccessFile(f, "rw").channel
        return RecordingTarget(name, null, f, ch, null)
    }

    fun add(info: RecordingInfo) {
        val all = list().toMutableList()
        all.add(0, info)
        val s = all.take(50).joinToString("\n") { "${it.name}\t${it.uri}\t${it.seconds}\t${it.date}" }
        prefs.edit().putString("list", s).apply()
    }

    fun list(): List<RecordingInfo> {
        val s = prefs.getString("list", null) ?: return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null else RecordingInfo(p[0], p[1], p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0)
        }
    }
}
