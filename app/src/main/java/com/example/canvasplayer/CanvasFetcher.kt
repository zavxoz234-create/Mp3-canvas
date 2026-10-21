package com.example.canvasplayer

import android.content.Context
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Settings for the automatic canvas feature and the selected source. */
object CanvasPrefs {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private fun p(c: Context) = c.getSharedPreferences("canvas", Context.MODE_PRIVATE)

    const val AUTO = "auto"
    val sources = listOf(AUTO, "echo", "apple", "convx", "spotify")
    fun label(source: String) = when (source) {
        "echo" -> "Echo Music"
        "apple" -> "Apple Music"
        "convx" -> "Convx"
        "spotify" -> "Spotify"
        else -> "Auto"
    }

    fun enabled(c: Context) = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    fun source(c: Context) = p(c).getString("source", AUTO) ?: AUTO
    fun setSource(c: Context, v: String) = p(c).edit().putString("source", v).apply()

    // Separate retry bookkeeping from VideoPrefs so a canvas miss never blocks the official-video fallback.
    private fun retryKey(id: Long) = "retry_$id"
    fun canTry(c: Context, trackId: Long) = System.currentTimeMillis() >= p(c).getLong(retryKey(trackId), 0L)
    fun markMissed(c: Context, trackId: Long) = p(c).edit().putLong(retryKey(trackId), System.currentTimeMillis() + 7 * DAY).apply()
    fun markFailed(c: Context, trackId: Long) = p(c).edit().putLong(retryKey(trackId), System.currentTimeMillis() + 6 * HOUR).apply()
    fun clear(c: Context, trackId: Long) = p(c).edit().remove(retryKey(trackId)).apply()
}

/**
 * Queries the canvas databases and downloads the mp4 into the track's companion-video slot.
 *
 * Lookups are multi-strategy because tag quality varies: the same song may be indexed under a
 * clean title, a remastered title, or just its album. Each provider is tried with
 * title+artist, then album+artist, then title alone, then album alone — first hit wins.
 */
object CanvasFinder {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "CanvasPlayer/0.8.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    /** Walks any JSON shape and keeps values that look like canvas video URLs. */
    private fun collectUrls(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                val value = node.opt(key)
                if (value is String && (key.contains("url", true) || key.contains("uri", true)) &&
                    (value.endsWith(".mp4", true) || value.contains("canvas", true))) out += value
                else collectUrls(value, out)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) collectUrls(node.opt(i), out)
        }
    }

    private fun urlsFromJson(body: String): List<String> {
        val out = mutableListOf<String>()
        runCatching { collectUrls(JSONObject(body), out) }
        return out.distinct()
    }

    // --- query variants -----------------------------------------------------

    /** Strips the noise that usually breaks exact matching: (feat. X), [Remastered], " - Live", etc. */
    private fun clean(s: String): String {
        var v = s
        v = v.replace(Regex("(?i)\\(feat\\.?[^)]*\\)"), "")
        v = v.replace(Regex("(?i)\\(with [^)]*\\)"), "")
        v = v.replace(Regex("(?i)\\[(feat|with|remaster(?:ed)?|live|acoustic|instrumental|radio|edit|single|version|bonus|deluxe|explicit|clean)[^]]*\\]"), "")
        v = v.replace(Regex("(?i)\\s*[-–—]\\s*(remaster(?:ed)?|live|acoustic|instrumental|radio|edit|single|version|bonus|deluxe).*"), "")
        return v.replace(Regex("\\s+"), " ").trim()
    }

    private fun firstArtist(artist: String) =
        artist.substringBefore(",").substringBefore("/").substringBefore("&").substringBefore(" feat").trim()

    /** Ordered lookup candidates: title+artist, album+artist, title alone, album alone. */
    private fun queriesFor(track: Track): List<Pair<String, String>> {
        val title = clean(track.title).substringBefore("(").trim()
        val album = clean(track.album).trim()
        val artist = clean(firstArtist(track.artist))
        return listOfNotNull(
            (title to artist).takeIf { title.isNotBlank() },
            (album to artist).takeIf { album.isNotBlank() },
            (title to "").takeIf { title.isNotBlank() },
            (album to "").takeIf { album.isNotBlank() },
        ).distinct()
    }

    private fun tryQueries(track: Track, query: (String, String) -> List<String>): List<String> {
        for ((song, artist) in queriesFor(track)) {
            val urls = runCatching { query(song, artist) }.getOrElse { emptyList() }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    // --- providers ----------------------------------------------------------

    private fun queryEcho(title: String, artist: String): List<String> {
        val body = get("https://api.echo.wesley.sh/canvases?song=${enc(title)}&artist=${enc(artist)}") ?: return emptyList()
        return urlsFromJson(body)
    }

    private fun queryConvx(title: String, artist: String): List<String> {
        val body = get("https://api.convx.xyz/canvases?title=${enc(title)}&artist=${enc(artist)}") ?: return emptyList()
        return urlsFromJson(body)
    }

    // Verified public API (github.com/m8tec/apple-music-animated-artworks). HLS-only results are
    // skipped because companion videos must be local mp4 files.
    private fun queryApple(album: String, artist: String): List<String> {
        if (album.isBlank()) return emptyList()
        val body = get("https://artwork.m8tec.top/api/v1/artwork/search?artist=${enc(artist)}&album=${enc(album)}") ?: return emptyList()
        return urlsFromJson(body).filter { it.endsWith(".mp4", true) }
    }

    private fun tryApple(track: Track): List<String> {
        val album = clean(track.album)
        if (album.isBlank()) return emptyList()
        val artist = clean(firstArtist(track.artist))
        for (a in listOf(artist, "").distinct()) {
            val urls = runCatching { queryApple(album, a) }.getOrElse { emptyList() }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private fun spotifyIdToGid(id: String): String {
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(62)
        for (ch in id) {
            val d = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> 0
            }
            n = n.multiply(base).add(BigInteger.valueOf(d.toLong()))
        }
        return n.toString(16).padStart(32, '0')
    }

    /** Runs a Spotify search query and pulls the canvas URL from the first few results. */
    private fun spotifySearchCanvas(query: String, auth: Map<String, String>): List<String> {
        val body = get("https://api.spotify.com/v1/search?q=${enc(query)}&type=track&limit=3", auth) ?: return emptyList()
        val urls = mutableListOf<String>()
        runCatching {
            val items = JSONObject(body).getJSONObject("tracks").getJSONArray("items")
            for (i in 0 until minOf(items.length(), 3)) {
                val id = items.getJSONObject(i).getString("id")
                val meta = get("https://spclient.wgc.spotify.net/metadata/4/track/${spotifyIdToGid(id)}?market=from_token", auth) ?: continue
                runCatching { urls += JSONObject(meta).getJSONObject("canvas").getString("url") }
            }
        }
        return urls.distinct()
    }

    private fun trySpotify(track: Track): List<String> {
        val token = get("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            ?.let { runCatching { JSONObject(it).getString("accessToken") }.getOrNull() } ?: return emptyList()
        val auth = mapOf("Authorization" to "Bearer $token")
        val title = clean(track.title).substringBefore("(").trim()
        val album = clean(track.album)
        val artist = clean(firstArtist(track.artist))
        val queries = listOfNotNull(
            "track:$title artist:$artist".takeIf { title.isNotBlank() && artist.isNotBlank() },
            "album:$album artist:$artist".takeIf { album.isNotBlank() && artist.isNotBlank() },
            "album:$album".takeIf { album.isNotBlank() },
            "$artist $title".takeIf { title.isNotBlank() && artist.isNotBlank() },
        ).distinct()
        for (q in queries) {
            val urls = runCatching { spotifySearchCanvas(q, auth) }.getOrElse { emptyList() }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    // --- public entry ---------------------------------------------------------

    /** Direct mp4 URLs for the selected source. Auto = Echo -> Apple -> Convx -> Spotify (as in Musify's Auto). */
    fun findUrls(context: Context, track: Track): List<String> {
        return when (CanvasPrefs.source(context)) {
            "echo" -> tryQueries(track, ::queryEcho)
            "apple" -> tryApple(track)
            "convx" -> tryQueries(track, ::queryConvx)
            "spotify" -> trySpotify(track)
            else -> tryQueries(track, ::queryEcho)
                .ifEmpty { tryApple(track) }
                .ifEmpty { tryQueries(track, ::queryConvx) }
                .ifEmpty { trySpotify(track) }
        }
    }

    fun download(url: String, out: File, maxBytes: Long = 40L * 1024 * 1024): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "CanvasPlayer/0.8.1")
        }
        val ok = try {
            if (conn.responseCode !in 200..299) return false
            out.parentFile?.mkdirs()
            conn.inputStream.use { input -> out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    total += read
                    if (total > maxBytes) return false
                    output.write(buf, 0, read)
                }
                total > 0
            } }
        } catch (_: Exception) { false } finally {
            conn.disconnect()
            if (out.exists() && out.length() == 0L) out.delete()
        }
        return ok
    }
}

/** Looks the track up on the selected canvas source and stores the mp4 as its companion video. */
suspend fun fetchCanvasVideo(context: Context, track: Track, status: MutableState<String?>): FetchOutcome =
    withContext(Dispatchers.IO) {
        if (!CanvasPrefs.canTry(context, track.id)) return@withContext FetchOutcome.NotFound
        status.value = "Looking for a canvas…"
        val urls = runCatching { CanvasFinder.findUrls(context, track) }.getOrElse { emptyList() }
        if (urls.isEmpty()) { CanvasPrefs.markMissed(context, track.id); return@withContext FetchOutcome.NotFound }
        status.value = "Downloading canvas…"
        val out = Store.appFile(context, track, false)
        if (urls.any { CanvasFinder.download(it, out) }) {
            CanvasPrefs.clear(context, track.id)
            FetchOutcome.Saved
        } else {
            CanvasPrefs.markFailed(context, track.id)
            FetchOutcome.Failed("Canvas download failed")
        }
    }
    
