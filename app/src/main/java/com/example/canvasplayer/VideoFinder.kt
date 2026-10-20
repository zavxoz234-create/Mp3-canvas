package com.example.canvasplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.MutableState
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

data class VideoCandidate(val id: String, val title: String, val channel: String, val durationSec: Long, val score: Int) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

sealed class FetchOutcome {
    object Saved : FetchOutcome()
    object NotFound : FetchOutcome()
    object Busy : FetchOutcome()
    data class Failed(val message: String) : FetchOutcome()
}

/** Settings and "don't ask again for a while" bookkeeping for automatic video lookup. */
object VideoPrefs {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private fun p(c: Context) = c.getSharedPreferences("video_search", Context.MODE_PRIVATE)

    fun autoEnabled(c: Context) = p(c).getBoolean("auto", true)
    fun setAutoEnabled(c: Context, value: Boolean) = p(c).edit().putBoolean("auto", value).apply()
    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, value: Boolean) = p(c).edit().putBoolean("wifi_only", value).apply()

    fun canTry(c: Context, trackId: Long) = System.currentTimeMillis() >= p(c).getLong("retry_$trackId", 0L)
    private fun retryAfter(c: Context, trackId: Long, millis: Long) =
        p(c).edit().putLong("retry_$trackId", System.currentTimeMillis() + millis).apply()
    /** Search worked but nothing official exists: look again in a week. */
    fun markMissed(c: Context, trackId: Long) = retryAfter(c, trackId, 7 * DAY)
    /** Network / yt-dlp problem: try again in a few hours. */
    fun markFailed(c: Context, trackId: Long) = retryAfter(c, trackId, 6 * HOUR)
    /** The user removed the video on purpose, so do not fetch it again automatically. */
    fun markRemoved(c: Context, trackId: Long) = retryAfter(c, trackId, 3650 * DAY)
    fun clear(c: Context, trackId: Long) = p(c).edit().remove("retry_$trackId").apply()
}

object VideoFinder {
    /** H.264 up to 720p so every phone can hardware-decode it (AV1/VP9 are a common cause of stutter). */
    const val AUTO_FORMAT = "bv*[height<=720][vcodec^=avc1][ext=mp4]+ba[ext=m4a]/b[height<=720][vcodec^=avc1][ext=mp4]/b[height<=720][ext=mp4]/b[height<=720]"

    /** Only one search/download at a time so two tracks never write the same temp file. */
    val fetchLock = Mutex()

    private val rejectPhrases = listOf(
        "lyric", "lyrics", "lyric video", "live", "cover", "karaoke", "instrumental", "reaction", "tutorial",
        "slowed", "sped up", "speed up", "nightcore", "8d", "reverb", "bass boosted", "mashup", "remix",
        "acoustic", "piano", "fan made", "fanmade", "behind the scenes", "making of", "interview", "teaser",
        "trailer", "shorts", "1 hour", "10 hours", "full album", "audio", "official audio"
    )
    private val videoMarkers = listOf("official video", "official music video", "official mv", "official m v", "official hd video", "music video")
    private val visualizerMarkers = listOf("visualizer", "visualiser", "official canvas", "canvas")

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun has(text: String, phrase: String) = " $text ".contains(" $phrase ")

    private val brackets = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")
    private fun cleanTitle(title: String) = title.replace(brackets, " ").replace(Regex("(?i)\\b(feat|ft|featuring)\\b.*$"), " ").trim()
    private fun primaryArtist(artist: String): String {
        val a = artist.trim()
        if (a.isBlank() || a.equals("<unknown>", true) || a.equals("unknown artist", true)) return ""
        return a.split(Regex("\\s*(?:,|&|;|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b)\\s*", RegexOption.IGNORE_CASE)).first().trim()
    }

    private fun channelOwned(channel: String, artists: List<String>): Boolean {
        val c = norm(channel)
        if (c.isBlank()) return false
        val cs = c.replace(" ", "")
        return artists.any { a ->
            val s = a.replace(" ", "")
            c == a || cs == s || cs == s + "official" || cs == "official$s" || cs == s + "vevo" || cs == s + "music"
        }
    }

    /**
     * Picks the best *official* upload from yt-dlp search results. A result must (1) contain the song title,
     * (2) not be a lyric/live/cover/remix/audio-only/fan upload, (3) come from the artist's own channel, a VEVO
     * channel, or a verified channel that names the artist and calls itself official, and (4) have a plausible duration.
     */
    fun pickBest(items: List<JSONObject>, track: Track): VideoCandidate? {
        val trackCore = norm(cleanTitle(track.title))
        if (trackCore.isBlank()) return null
        val trackNorm = norm(track.title)
        val artists = listOf(norm(track.artist.takeUnless { it.equals("<unknown>", true) }.orEmpty()), norm(primaryArtist(track.artist)))
            .filter { it.isNotBlank() }.distinct()
        val trackSec = track.durationMs / 1000
        var best: VideoCandidate? = null
        for (o in items) {
            val id = o.str("id")
            val title = o.str("title")
            if (id.length != 11 || title.isBlank()) continue
            val channel = o.str("channel").ifBlank { o.str("uploader") }
            val durRaw = o.optDouble("duration", 0.0)
            val dur = if (durRaw.isNaN()) 0L else durRaw.toLong()
            val verified = o.optBoolean("channel_is_verified", false)
            val views = o.optLong("view_count", 0L)
            val t = norm(title)
            if (norm(channel).endsWith(" topic")) continue            // auto-generated audio-only uploads
            if (!has(t, trackCore)) continue
            if (rejectPhrases.any { has(t, it) && !has(trackNorm, it) }) continue
            if (dur > 15 * 60) continue
            if (dur > 0 && trackSec > 0 && (dur < trackSec * 0.5 || dur > trackSec * 2.2 + 90)) continue
            val artistInTitle = artists.any { has(t, it) }
            val owned = channelOwned(channel, artists)
            val marker = videoMarkers.any { has(t, it) }
            val visualizer = visualizerMarkers.any { has(t, it) }
            if (!(owned || (verified && artistInTitle && (marker || visualizer)))) continue
            var score = if (owned) 60 else 45
            if (marker) score += 30 else if (visualizer) score += 18
            if (verified) score += 5
            if (artistInTitle) score += 5
            if (dur > 0 && trackSec > 0) {
                val diff = abs(dur - trackSec)
                score += when { diff <= 10 -> 15; diff <= 60 -> 8; else -> 0 }
            }
            if (views > 1_000_000) score += 3
            if (best == null || score > best.score) best = VideoCandidate(id, title, channel, dur, score)
        }
        return best
    }

    private fun runSearch(context: Context, query: String, limit: Int, processId: String, retryWithUpdate: Boolean): List<JSONObject> {
        fun go(): String {
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--no-warnings")
                addOption("--socket-timeout", "10")
            }
            return YoutubeDL.getInstance().execute(request, processId).out
        }
        val out = try { go() } catch (e: Exception) {
            if (!retryWithUpdate) throw e
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            go()
        }
        return out.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
    }

    /** Ok(null) means the search worked but nothing official was found. */
    suspend fun findOfficial(context: Context, track: Track, retryWithUpdate: Boolean): Result<VideoCandidate?> {
        val pid = "search-${track.id}-${System.nanoTime()}"
        val artist = primaryArtist(track.artist)
        val title = cleanTitle(track.title)
        val queries = listOf("$artist $title official video", "$artist $title visualizer").map { it.trim() }
        return runCatchingKill(pid) {
            for (q in queries) {
                val best = pickBest(runSearch(context, q, 8, pid, retryWithUpdate), track)
                if (best != null) return@runCatchingKill best
            }
            null
        }
    }

    private suspend fun <T> runCatchingKill(processId: String, block: () -> T?): Result<T?> = withContext(Dispatchers.IO) {
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        try { runCatching(block) } finally { handle?.dispose() }
    }

    fun networkAllowed(context: Context, ignoreWifiOnly: Boolean = false): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return ignoreWifiOnly || !VideoPrefs.wifiOnly(context) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

suspend fun ensureYtDlp(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
    }
}

/** Search, pick an official upload, download it and store it as the track's companion video. */
suspend fun fetchOfficialVideo(context: Context, track: Track, status: MutableState<String?>, manual: Boolean = false): FetchOutcome {
    if (!VideoFinder.fetchLock.tryLock()) return FetchOutcome.Busy
    try {
        status.value = "Looking for the official video…"
        ensureYtDlp(context).onFailure {
            VideoPrefs.markFailed(context, track.id)
            return FetchOutcome.Failed(it.message?.take(200) ?: "yt-dlp could not start")
        }
        val candidate = VideoFinder.findOfficial(context, track, retryWithUpdate = manual).getOrElse {
            VideoPrefs.markFailed(context, track.id)
            return FetchOutcome.Failed(it.message?.take(200) ?: "Search failed")
        }
        if (candidate == null) {
            VideoPrefs.markMissed(context, track.id)
            return FetchOutcome.NotFound
        }
        status.value = "Downloading official video…"
        var lastPercent = -1
        val result = downloadCompanionVideo(context, track, candidate.url, VideoFinder.AUTO_FORMAT, false) { percent ->
            if (percent != lastPercent) { lastPercent = percent; status.value = "Downloading official video… $percent%" }
        }
        return result.fold(
            onSuccess = { VideoPrefs.clear(context, track.id); FetchOutcome.Saved },
            onFailure = { VideoPrefs.markFailed(context, track.id); FetchOutcome.Failed(it.message?.take(200) ?: "Download failed") }
        )
    } finally {
        VideoFinder.fetchLock.unlock()
    }
}

private suspend fun trimOnMain(context: Context, src: File, startMs: Long, endMs: Long, out: File): Result<Unit> =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Result<Unit>> { cont ->
            try {
                Exporter.trim(context, Uri.fromFile(src), startMs, endMs, out) { if (cont.isActive) cont.resume(it) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
    }

/**
 * Downloads [url] with yt-dlp into the app's companion-video slot for [track], trimming silent
 * intro/outro the same way the manual download always did. Cancelling the coroutine kills yt-dlp.
 * Returns a human-readable success message.
 */
suspend fun downloadCompanionVideo(
    context: Context, track: Track, url: String, formatSelector: String, removeSponsor: Boolean, onProgress: (Int) -> Unit
): Result<String> {
    val finalFile = Store.appFile(context, track, false).apply { parentFile?.mkdirs() }
    val tempFile = File(finalFile.parentFile, "${track.id}.download.mp4")
    val pid = "canvas-${track.id}-${System.nanoTime()}"
    try {
        val downloaded = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val handle = job?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
            }
            try {
                runCatching {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    val request = YoutubeDLRequest(url.trim()).apply {
                        addOption("--no-playlist")
                        addOption("--force-overwrites")
                        addOption("-f", formatSelector)
                        addOption("--merge-output-format", "mp4")
                        addOption("--remux-video", "mp4")
                        if (removeSponsor) addOption("--sponsorblock-remove", "sponsor,intro,outro")
                        addOption("-o", tempFile.absolutePath)
                    }
                    val run = {
                        tempFile.delete()
                        YoutubeDL.getInstance().execute(request, pid) { progress, _, _ -> onProgress(progress.toInt().coerceIn(0, 100)) }
                    }
                    try { run() } catch (e: Exception) {
                        if (job?.isActive != true) throw e
                        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                        run()
                    }
                    check(tempFile.isFile && tempFile.length() > 0L) { "yt-dlp finished without creating the expected MP4. Try another MP4 format." }
                }
            } finally { handle?.dispose() }
        }
        if (downloaded.isFailure) return Result.failure(downloaded.exceptionOrNull() ?: IllegalStateException("Download failed"))

        val (range, durationMs) = withContext(Dispatchers.IO) {
            SilenceAnalyzer.audibleRange(tempFile) to runCatching {
                MediaMetadataRetriever().run {
                    try { extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { release() }
                }
            }.getOrDefault(0L)
        }
        val needsTrim = range != null && (range.first >= 450L || (durationMs > 0 && range.second <= durationMs - 700L))
        return if (needsTrim && range != null) {
            onProgress(99)
            val trimmed = trimOnMain(context, tempFile, range.first, range.second, finalFile)
            trimmed.fold(
                onSuccess = { Result.success("Downloaded and trimmed quiet intro/outro for ${track.title}") },
                onFailure = { Result.failure(IllegalStateException("Video downloaded, but silence trim failed: ${it.message}")) }
            )
        } else {
            finalFile.delete()
            if (tempFile.renameTo(finalFile)) Result.success("Video saved for ${track.title}")
            else Result.failure(IllegalStateException("Video downloaded but could not save it for this track"))
        }
    } finally {
        if (tempFile.exists()) tempFile.delete()
    }
}
