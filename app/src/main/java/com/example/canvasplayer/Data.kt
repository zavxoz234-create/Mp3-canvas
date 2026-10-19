package com.example.canvasplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt
import java.io.BufferedReader

data class Track(
    val id: Long, val title: String, val artist: String,
    val album: String, val albumId: Long, val path: String, val durationMs: Long = 0L
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    // Static art only: this is what the lock screen / notification receive.
    val artUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setAlbumTitle(album).setArtworkUri(artUri).build()
        ).build()
}

data class TimedLyric(val timeMs: Long, val text: String)

/** Matches a sibling .lrc by track title first, then by the audio filename. */
fun loadLrc(context: Context, track: Track, treeUri: String?, rootFolder: String?): List<TimedLyric> {
    if (treeUri.isNullOrBlank() || rootFolder.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val normalizedRoot = rootFolder.replace("\\", "/").trimEnd('/') + "/"
        val normalizedPath = track.path.replace("\\", "/")
        if (!normalizedPath.startsWith(normalizedRoot, ignoreCase = true)) return emptyList()
        val relativeParent = normalizedPath.substring(normalizedRoot.length).substringBeforeLast('/', "")
        var directory = root
        for (segment in relativeParent.split('/').filter { it.isNotBlank() }) {
            directory = directory.findFile(segment)?.takeIf { it.isDirectory } ?: return emptyList()
        }
        val audioName = track.path.substringAfterLast('/').substringBeforeLast('.')
        val expected = listOf("${track.title}.lrc", "$audioName.lrc")
        val lyricFile = expected.firstNotNullOfOrNull { name -> directory.findFile(name) }
            ?: directory.listFiles().firstOrNull { file -> file.isFile && file.name?.endsWith(".lrc", true) == true && file.name?.substringBeforeLast('.')?.equals(track.title, true) == true }
            ?: return emptyList()
        val content = context.contentResolver.openInputStream(lyricFile.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return emptyList()
        val pattern = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
        content.lineSequence().flatMap { line ->
            val matches = pattern.findAll(line).toList()
            val text = matches.lastOrNull()?.groupValues?.get(4)?.trim().orEmpty()
            matches.map { match ->
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) { 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; 3 -> fraction.toLong(); else -> 0L }
                TimedLyric((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + fractionMs, text)
            }
        }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
    }.getOrDefault(emptyList())
}

fun loadTracks(c: Context, rootFolder: String): List<Track> {
    val out = mutableListOf<Track>()
    val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DURATION
    )
    c.contentResolver.query(
        col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?", arrayOf(rootFolder.trimEnd('/') + "/%"),
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
    )?.use { cur ->
        while (cur.moveToNext()) out += Track(
            cur.getLong(0), cur.getString(1) ?: "Unknown", cur.getString(2) ?: "Unknown",
            cur.getString(3) ?: "", cur.getLong(4), cur.getString(5) ?: "", cur.getLong(6)
        )
    }
    return out
}

/** Finds the first and last audible decoder buffers in a downloaded video's audio stream. */
object SilenceAnalyzer {
    fun audibleRange(file: File): Pair<Long, Long>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationMs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
            extractor.selectTrack(audioTrack)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var firstSound = Long.MAX_VALUE
            var lastSound = -1L
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val samples = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            var squares = 0.0; var count = 0
                            while (samples.hasRemaining()) { val value = samples.get().toInt(); squares += value.toDouble() * value; count++ }
                            val rms = if (count == 0) 0.0 else sqrt(squares / count)
                            if (rms >= 420.0) {
                                val timeMs = info.presentationTimeUs / 1000L
                                if (timeMs < firstSound) firstSound = timeMs
                                if (timeMs > lastSound) lastSound = timeMs
                            }
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            if (lastSound < 0) return null
            val start = (firstSound - 120L).coerceAtLeast(0L)
            val end = (lastSound + 450L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
            return if (end > start) start to end else null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; runCatching { extractor.release() }
        }
    }
}

/** Looks only on this device. Nothing is ever downloaded. */
object Store {
    private fun dir(c: Context) = File(c.getExternalFilesDir(null), "canvas").apply { mkdirs() }

    fun appFile(c: Context, t: Track, lyrics: Boolean) =
        File(dir(c), "${t.id}${if (lyrics) ".lyrics" else ""}.mp4")

    fun find(c: Context, t: Track, lyrics: Boolean): Uri? {
        val app = appFile(c, t, lyrics)
        if (app.isFile && app.length() > 0) return Uri.fromFile(app)
        val sfx = if (lyrics) ".lyrics" else ""
        val f = File(t.path)
        val d = f.parentFile ?: return null
        val names = listOf(f.nameWithoutExtension, "${t.artist} - ${t.title}")
        for (n in names) for (sub in listOf("", "Canvas/")) {
            val cand = File(d, "$sub$n$sfx.mp4")
            if (cand.isFile && cand.length() > 0) return Uri.fromFile(cand)
        }
        return null
    }
}

/** Uses the track's metadata (title/artist) to rank video files in a folder the user picked. */
object MetadataMatcher {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun match(files: List<DocumentFile>, t: Track): List<DocumentFile> {
        val title = norm(t.title)
        val artist = norm(t.artist)
        if (title.isEmpty()) return emptyList()
        return files
            .filter { it.isFile && (it.type?.startsWith("video/") == true) }
            .filter { norm(it.name ?: "").contains(title) }
            .sortedByDescending { if (norm(it.name ?: "").contains(artist)) 1 else 0 }
    }
}

/** Trims to [startMs, endMs], crops to 720x720, strips audio, re-encodes as H.264. */
object Exporter {
    fun trim(ctx: Context, src: Uri, startMs: Long, endMs: Long, out: File, done: (Result<Unit>) -> Unit) {
        out.delete()
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        val item = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
        val fx = Effects(
            emptyList(),
            listOf(Presentation.createForWidthAndHeight(720, 720, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        )
        val edited = EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(fx).build()
        Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) =
                    done(Result.success(Unit))
                override fun onError(
                    composition: Composition, exportResult: ExportResult, exportException: ExportException
                ) = done(Result.failure(exportException))
            })
            .build()
            .start(edited, out.absolutePath)
    }
}
