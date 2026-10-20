package com.example.canvasplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One place that decodes album art. Every list row used to open its own MediaMetadataRetriever and
 * hand the full-size embedded picture to Coil; now art is decoded once at a size bucket close to what
 * is displayed, kept in a small LRU cache, and at most two decodes run at the same time.
 */
object ArtCache {
    private class Entry(val bitmap: Bitmap?) // bitmap == null means "looked, nothing there"

    private val cache = object : LruCache<String, Entry>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry): Int = (value.bitmap?.byteCount ?: 0) + 64
    }
    private val gate = Semaphore(2)

    /** Bumped whenever tags are written so that every visible cover reloads. */
    var version by mutableIntStateOf(0)
        private set

    fun invalidate() {
        cache.evictAll()
        version++
    }

    private fun bucketFor(px: Int) = when {
        px <= 128 -> 128
        px <= 256 -> 256
        px <= 512 -> 512
        else -> 1024
    }

    private fun key(track: Track, bucket: Int) = "${track.id}@$bucket"

    /** Synchronous cache lookup, safe to call from composition. */
    fun peek(track: Track, sizePx: Int): Bitmap? = cache.get(key(track, bucketFor(sizePx)))?.bitmap

    suspend fun load(context: Context, track: Track, sizePx: Int): Bitmap? {
        val bucket = bucketFor(sizePx)
        val k = key(track, bucket)
        cache.get(k)?.let { return it.bitmap }
        return gate.withPermit {
            cache.get(k)?.let { return@withPermit it.bitmap }
            val decoded = withContext(Dispatchers.IO) { runCatching { decode(context.applicationContext, track, bucket) }.getOrNull() }
            cache.put(k, Entry(decoded))
            decoded
        }
    }

    private fun decode(context: Context, track: Track, target: Int): Bitmap? {
        val embedded = runCatching {
            MediaMetadataRetriever().run {
                try { setDataSource(context, track.uri); embeddedPicture } finally { release() }
            }
        }.getOrNull()
        if (embedded != null) decodeSampled(embedded, target)?.let { return it }
        if (track.albumId > 0L) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(track.artUri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) return decodeSampled(bytes, target)
        }
        return null
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Vivid average colour of the cover, computed from a 24x24 thumbnail. Null when the cover is grey/dark/missing. */
    fun accent(bitmap: Bitmap?): Color? {
        if (bitmap == null) return null
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        if (small !== bitmap) small.recycle()
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        val hsv = FloatArray(3)
        for (pixel in pixels) {
            android.graphics.Color.colorToHSV(pixel, hsv)
            if (hsv[1] > .28f && hsv[2] > .28f) {
                red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++
            }
        }
        if (count == 0) return null
        val r = (red / count).toInt(); val g = (green / count).toInt(); val b = (blue / count).toInt()
        return Color(android.graphics.Color.rgb((r * .72 + 70).toInt().coerceAtMost(255), (g * .72 + 70).toInt().coerceAtMost(255), (b * .72 + 70).toInt().coerceAtMost(255)))
    }
}

/** Cover for [track]; the previous track's cover is cleared immediately instead of lingering until the new one decodes. */
@Composable
fun rememberTrackArt(track: Track, sizePx: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    val version = ArtCache.version
    return produceState<ImageBitmap?>(null, track.id, sizePx, version) {
        value = ArtCache.peek(track, sizePx)?.asImageBitmap()
        value = ArtCache.load(context, track, sizePx)?.asImageBitmap()
    }
}
