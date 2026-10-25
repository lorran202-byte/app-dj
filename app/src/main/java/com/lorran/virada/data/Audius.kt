package com.lorran.virada.data

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Músicas do Audius (plataforma aberta de streaming: os artistas publicam e liberam para apps de fora).
 * API pública, sem chave: https://api.audius.co
 */
object Audius {
    private const val BASE = "https://api.audius.co/v1"
    private const val APP = "ViradaDJ"

    val genres = listOf(
        "Todos" to null, "Eletrônica" to "Electronic", "House" to "House", "Techno" to "Techno",
        "Hip-Hop" to "Hip-Hop/Rap", "Dubstep" to "Dubstep", "Trap" to "Trap", "Drum & Bass" to "Drum & Bass",
        "Pop" to "Pop", "Latina" to "Latin", "Funk" to "Funk", "Lo-fi" to "Lo-Fi",
    )

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) error("Audius respondeu ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: String): List<Song> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Song>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (!t.optBoolean("is_streamable", true)) continue
            val access = t.optJSONObject("access")
            if (access != null && !access.optBoolean("stream", true)) continue
            val dur = t.optInt("duration", 0)
            if (dur < 20 || dur > 20 * 60) continue // mixes muito longos pesam demais no celular
            val id = t.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out += Song(
                key = "a$id",
                uri = Uri.EMPTY,
                title = t.optString("title", "Sem título"),
                artist = t.optJSONObject("user")?.optString("name") ?: "Audius",
                durationMs = dur * 1000L,
                streamId = id,
                hintBpm = t.optDouble("bpm", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                artwork = t.optJSONObject("artwork")?.optString("150x150")?.takeIf { it.startsWith("http") },
            )
        }
        return out
    }

    fun trending(genre: String?): List<Song> {
        val g = if (genre != null) "&genre=" + URLEncoder.encode(genre, "UTF-8") else ""
        return parse(get("$BASE/tracks/trending?time=week$g&limit=50&app_name=$APP"))
    }

    fun search(query: String): List<Song> =
        parse(get("$BASE/tracks/search?query=" + URLEncoder.encode(query, "UTF-8") + "&limit=50&app_name=$APP"))

    /** Baixa a música para [dest] (o app precisa do arquivo inteiro para analisar BPM e onda). */
    fun download(id: String, dest: File, onProgress: (Float) -> Unit) {
        var url = URL("$BASE/tracks/$id/stream?app_name=$APP")
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Redirecionamento sem destino")
                c.disconnect()
                url = URL(url, loc)
                if (++hops > 5) error("Muitos redirecionamentos")
                continue
            }
            if (code !in 200..299) { c.disconnect(); error("Não consegui baixar ($code)") }
            break
        }
        try {
            val total = c.contentLengthLong
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val b = ByteArray(1 shl 16)
                    var got = 0L
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        got += n
                        if (total > 0) onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        } finally {
            c.disconnect()
        }
    }
}
