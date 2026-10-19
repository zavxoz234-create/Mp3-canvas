package com.example.canvasplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import java.text.Normalizer

data class Track(
    val id: Long, val title: String, val artist: String,
    val album: String, val albumId: Long, val path: String, val durationMs: Long = 0L,
    val albumArtist: String = ""
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    // Static art only: this is what the lock screen / notification receive.
    val artUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setAlbumTitle(album).setAlbumArtist(albumArtist.ifBlank { artist }).setArtworkUri(artUri).build()
        ).build()
}

data class TimedLyric(val timeMs: Long, val text: String)

/** Matches a sibling .lrc by track title first, then by the audio filename. */
fun loadLrc(context: Context, track: Track, treeUri: String?, rootFolder: String?): List<TimedLyric> {
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")
    val audioName = track.path.substringAfterLast('/').substringBeforeLast('.')
    val stems = setOf(normalized(track.title), normalized(audioName)).filter(String::isNotBlank).toSet()
    fun matches(fileName: String?) = fileName?.endsWith(".lrc", true) == true &&
        normalized(fileName.substringBeforeLast('.')) in stems

    fun parse(content: String): List<TimedLyric> {
        val offset = Regex("(?im)\\[offset:([+-]?\\d+)\\]").find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val pattern = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
        return content.removePrefix("\uFEFF").lineSequence().flatMap { line ->
            val matches = pattern.findAll(line).toList()
            val text = matches.lastOrNull()?.groupValues?.get(4)?.trim().orEmpty()
            matches.map { match ->
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) { 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; 3 -> fraction.toLong(); else -> 0L }
                TimedLyric(((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + fractionMs + offset).coerceAtLeast(0L), text)
            }
        }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
    }
    fun readFile(file: File): List<TimedLyric> = runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    fun readDocument(file: DocumentFile): List<TimedLyric> = runCatching {
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return emptyList()
        val charset = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) Charsets.UTF_16LE else Charsets.UTF_8
        parse(String(bytes, charset))
    }.getOrDefault(emptyList())

    // Direct sibling access handles tracks whose media path is available even when no scan folder is configured.
    runCatching {
        File(track.path).parentFile?.listFiles()?.firstOrNull { matches(it.name) }?.let(::readFile)
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

    if (treeUri.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val normalizedRoot = rootFolder?.replace("\\", "/")?.trimEnd('/')?.plus("/")
        val normalizedPath = track.path.replace("\\", "/")
        val relativeParent = if (normalizedRoot != null && normalizedPath.startsWith(normalizedRoot, true))
            normalizedPath.substring(normalizedRoot.length).substringBeforeLast('/', "") else null
        var directory: DocumentFile? = root
        for (segment in relativeParent.orEmpty().split('/').filter { it.isNotBlank() }) {
            directory = directory?.findFile(segment)?.takeIf { it.isDirectory }
        }
        directory?.listFiles()?.firstOrNull { it.isFile && matches(it.name) }?.let(::readDocument)
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        // SAF providers sometimes expose a different path spelling from MediaStore. Locate the audio sibling by name.
        val expectedAudio = normalized(audioName)
        var visited = 0
        fun search(folder: DocumentFile, depth: Int): List<TimedLyric>? {
            if (depth > 10 || visited > 3000) return null
            for (child in runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())) {
                visited++
                if (child.isDirectory) search(child, depth + 1)?.let { return it }
                else if (child.isFile && normalized(child.name?.substringBeforeLast('.') ?: "") == expectedAudio) {
                    child.parentFile?.listFiles()?.firstOrNull { it.isFile && matches(it.name) }?.let(::readDocument)
                        ?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return null
        }
        search(root, 0) ?: emptyList()
    }.getOrDefault(emptyList())
}

fun loadTracks(c: Context, rootFolder: String? = null): List<Track> {
    val out = mutableListOf<Track>()
    val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val baseProj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DURATION
    )
    val proj = if (Build.VERSION.SDK_INT >= 29) baseProj + MediaStore.Audio.Media.ALBUM_ARTIST else baseProj
    c.contentResolver.query(
        col, proj,
        if (rootFolder.isNullOrBlank()) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?",
        if (rootFolder.isNullOrBlank()) null else arrayOf(rootFolder.trimEnd('/') + "/%"),
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
    )?.use { cur ->
        val albumArtistIndex = if (Build.VERSION.SDK_INT >= 29) cur.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
        while (cur.moveToNext()) out += Track(
            cur.getLong(0), cur.getString(1) ?: "Unknown", cur.getString(2) ?: "Unknown",
            cur.getString(3) ?: "", cur.getLong(4), cur.getString(5) ?: "", cur.getLong(6),
            if (albumArtistIndex >= 0) cur.getString(albumArtistIndex).orEmpty() else ""
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

data class WaveformData(val durationMs: Long, val bars: List<Float>)

/** Decodes audio into a small RMS waveform so users can line up a companion video by ear and sight. */
object WaveformAnalyzer {
    fun read(context: Context, uri: Uri, bins: Int = 180, fallbackPath: String? = null): WaveformData? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            try { extractor.setDataSource(context, uri, null) }
            catch (first: Exception) {
                if (fallbackPath.isNullOrBlank()) throw first
                extractor.setDataSource(fallbackPath)
            }
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationMs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L)
                .takeIf { it > 0L } ?: runCatching {
                    MediaMetadataRetriever().run { setDataSource(context, uri); val duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); duration }
                }.getOrDefault(0L)
            if (durationMs <= 0L) return null
            val levels = FloatArray(bins)
            extractor.selectTrack(audioTrack)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
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
                            var sum = 0.0; var count = 0
                            while (samples.hasRemaining()) { val sample = samples.get().toInt(); sum += sample.toDouble() * sample; count++ }
                            val rms = if (count == 0) 0f else sqrt(sum / count).toFloat() / 32768f
                            val time = info.presentationTimeUs.coerceAtLeast(0L) / 1000L
                            val index = ((time * bins) / durationMs).toInt().coerceIn(0, bins - 1)
                            levels[index] = maxOf(levels[index], rms)
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            val peak = levels.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
            return WaveformData(durationMs, levels.map { (it / peak).coerceIn(.03f, 1f) })
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

    fun findVideosRecursively(root: DocumentFile, maxDepth: Int = 8): List<DocumentFile> {
        val found = mutableListOf<DocumentFile>()
        fun visit(dir: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            for (child in runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())) {
                if (child.isDirectory) visit(child, depth + 1)
                else if (child.isFile && (child.type?.startsWith("video/") == true ||
                        child.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "m4v", "mov", "webm", "mkv"))) found += child
            }
        }
        visit(root, 0)
        return found
    }
}

/** Trims to [startMs, endMs], crops to 720x720, and retains source audio for waveform alignment. */
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
        val edited = EditedMediaItem.Builder(item).setRemoveAudio(false).setEffects(fx).build()
        Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
