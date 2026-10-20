package com.example.canvasplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodec
import android.media.AudioFormat
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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject

data class Track(
    val id: Long, val title: String, val artist: String,
    val album: String, val albumId: Long, val path: String, val durationMs: Long = 0L,
    val albumArtist: String = "", val documentUri: Uri? = null
) {
    val uri: Uri get() = documentUri ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    // Static art only: this is what the lock screen / notification receive.
    val artUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setAlbumTitle(album).setAlbumArtist(albumArtist.ifBlank { artist })
            .apply { if (documentUri == null && albumId > 0L) setArtworkUri(artUri) }.build()).build()
}

private fun normalizeGroupValue(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

/** Group by the album's actual album-artist tag; MediaStore album IDs can split compilations. */
fun Track.albumGroupingKey(): String {
    val albumName = normalizeGroupValue(album).ifBlank { "unknownalbum" }
    val albumPerformer = normalizeGroupValue(albumArtist)
    val folder = normalizeGroupValue(File(path).parent.orEmpty())
    return "$albumName\u0000${albumPerformer.ifBlank { folder }}"
}

fun Track.belongsToSameAlbum(other: Track): Boolean = albumGroupingKey() == other.albumGroupingKey()

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
        while (cur.moveToNext()) {
            val id = cur.getLong(0)
            val track = Track(
                id, cur.getString(1) ?: "Unknown", cur.getString(2) ?: "Unknown",
                cur.getString(3) ?: "", cur.getLong(4), cur.getString(5) ?: "", cur.getLong(6),
                if (albumArtistIndex >= 0) cur.getString(albumArtistIndex).orEmpty().trim() else ""
            )
            // MediaStore often omits ALBUM_ARTIST even when it is embedded in the MP3.
            val albumArtist = track.albumArtist.ifBlank { readEmbeddedAlbumArtist(c, track) }
            out += track.copy(albumArtist = albumArtist)
        }
    }
    return out
}

/** Read selected SAF folders directly so songs do not disappear when Android has not indexed them in MediaStore. */
fun loadTracksFromSelectedFolders(context: Context, folders: List<Pair<String, String>>): List<Track> {
    val supported = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff", "ape")
    val results = LinkedHashMap<String, Track>()
    var visited = 0

    folders.forEach { (rootPath, treeUri) ->
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull() ?: return@forEach
        fun visit(directory: DocumentFile, relative: String, depth: Int) {
            if (depth > 16 || visited >= 20_000) return
            val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
            for (child in children) {
                if (visited++ >= 20_000) break
                val name = child.name ?: continue
                val childRelative = if (relative.isBlank()) name else "$relative/$name"
                if (child.isDirectory) {
                    visit(child, childRelative, depth + 1)
                } else if (child.isFile && name.substringAfterLast('.', "").lowercase() in supported) {
                    val trackUri = child.uri
                    val retriever = MediaMetadataRetriever()
                    val metadata = try {
                        retriever.setDataSource(context, trackUri)
                        Triple(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                        )
                    } catch (_: Exception) { Triple("", "", "") }
                    val duration = runCatching {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }.getOrDefault(0L)
                    val albumArtist = runCatching {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
                    }.getOrDefault("")
                    runCatching { retriever.release() }
                    val fallbackTitle = name.substringBeforeLast('.', name)
                    val absolutePath = rootPath.trimEnd('/') + "/" + childRelative
                    val uriKey = trackUri.toString()
                    val uriHash = uriKey.hashCode().toLong() and 0xffffffffL
                    val id = Long.MIN_VALUE + uriHash
                    results[absolutePath.lowercase()] = Track(
                        id = id,
                        title = metadata.first.ifBlank { fallbackTitle },
                        artist = metadata.second.ifBlank { "Unknown artist" },
                        album = metadata.third,
                        albumId = 0L,
                        path = absolutePath,
                        durationMs = duration,
                        albumArtist = albumArtist,
                        documentUri = trackUri
                    )
                }
            }
        }
        visit(root, "", 0)
    }
    return results.values.toList()
}

/** MediaStore and MediaMetadataRetriever both omit TPE2 on some Android builds; read the MP3 tag directly as a fallback. */
private fun readEmbeddedAlbumArtist(context: Context, track: Track): String {
    val uri = track.uri
    val retriever = MediaMetadataRetriever()
    val retrieverValue = try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty().trim()
    } catch (_: Exception) { "" } finally { runCatching { retriever.release() } }
    if (retrieverValue.isNotBlank()) return retrieverValue

    val local = File(track.path)
    val cachedCopy = File(context.cacheDir, "album-artist-${track.id}.audio")
    return try {
        val source = if (local.isFile && local.canRead()) local else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cachedCopy.outputStream().use { output -> input.copyTo(output) }
            } ?: return ""
            cachedCopy
        }
        AudioFileIO.read(source).tag?.getFirst(FieldKey.ALBUM_ARTIST).orEmpty().trim()
    } catch (_: Exception) { "" }
    finally { cachedCopy.delete() }
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
            var outputFormat = format
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
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = decoder.outputFormat
                    continue
                }
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            pcm.position(info.offset.coerceAtLeast(0)); pcm.limit((info.offset + info.size).coerceAtMost(pcm.capacity()))
                            val frameBytes = channels * bytesPerSample
                            val frameCount = pcm.remaining() / frameBytes
                            repeat(frameCount) { frame ->
                                var peak = 0f
                                repeat(channels) {
                                    val sample = when (encoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> pcm.float.coerceIn(-1f, 1f)
                                        AudioFormat.ENCODING_PCM_32BIT -> pcm.int.toFloat() / Int.MAX_VALUE
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                            var raw = (pcm.get().toInt() and 0xff) or ((pcm.get().toInt() and 0xff) shl 8) or ((pcm.get().toInt() and 0xff) shl 16)
                                            if (raw and 0x800000 != 0) raw = raw or -0x1000000
                                            raw / 8388608f
                                        }
                                        AudioFormat.ENCODING_PCM_8BIT -> ((pcm.get().toInt() and 0xff) - 128) / 128f
                                        else -> pcm.short.toFloat() / Short.MAX_VALUE
                                    }
                                    peak = maxOf(peak, kotlin.math.abs(sample))
                                }
                                val time = (info.presentationTimeUs / 1000L) + frame * 1000L / sampleRate
                                val index = ((time * bins) / durationMs).toInt().coerceIn(0, bins - 1)
                                levels[index] = maxOf(levels[index], peak)
                            }
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            val peak = levels.maxOrNull()?.coerceAtLeast(.001f) ?: return null
            if (peak <= .0011f) return null
            return WaveformData(durationMs, levels.map { (kotlin.math.sqrt(it / peak)).coerceIn(.06f, 1f) })
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

/** Persists the last selected-folder scan so opening the app never rescans storage automatically. */
object SelectedFolderScanCache {
    private const val FILE_NAME = "selected-folder-scan.json"
    private fun cacheFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun foldersKey(folders: List<Pair<String, String>>): String = folders
        .map { (path, uri) -> path.replace('\\', '/').trimEnd('/') + "\u0000" + uri }.sorted().joinToString("\n")

    fun load(context: Context, folders: List<Pair<String, String>>): List<Track>? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return null
        val payload = JSONObject(file.readText(Charsets.UTF_8))
        if (payload.optString("folders") != foldersKey(folders)) return null
        val entries = payload.optJSONArray("tracks") ?: JSONArray()
        List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val uri = item.optString("uri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            Track(item.getLong("id"), item.optString("title", "Unknown"), item.optString("artist", "Unknown artist"),
                item.optString("album"), item.optLong("albumId"), item.optString("path"), item.optLong("durationMs"),
                item.optString("albumArtist"), uri)
        }
    }.getOrNull()

    fun save(context: Context, folders: List<Pair<String, String>>, tracks: List<Track>) {
        runCatching {
            val payload = JSONObject().put("folders", foldersKey(folders))
            val entries = JSONArray()
            tracks.forEach { track -> entries.put(JSONObject()
                .put("id", track.id).put("title", track.title).put("artist", track.artist)
                .put("album", track.album).put("albumId", track.albumId).put("path", track.path)
                .put("durationMs", track.durationMs).put("albumArtist", track.albumArtist)
                .put("uri", track.documentUri?.toString().orEmpty())) }
            payload.put("tracks", entries)
            val file = cacheFile(context)
            val temporary = File(context.filesDir, FILE_NAME + ".tmp")
            temporary.writeText(payload.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) }
        }
    }

    fun clear(context: Context) { cacheFile(context).delete() }
}





/** Reuse MediaStore results across launches and app updates while the indexed media volume is unchanged. */
object MediaStoreTrackCache {
    private const val FILE_NAME = "media-store-library.json"
    private fun cacheFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun version(context: Context): String = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL) else MediaStore.getVersion(context)

    private fun readTracks(context: Context): Pair<String, List<Track>>? = runCatching {
        val payload = JSONObject(cacheFile(context).readText(Charsets.UTF_8))
        val entries = payload.optJSONArray("tracks") ?: error("Missing cached tracks")
        payload.optString("version") to List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            Track(item.getLong("id"), item.optString("title", "Unknown"), item.optString("artist", "Unknown"),
                item.optString("album"), item.optLong("albumId"), item.optString("path"), item.optLong("durationMs"),
                item.optString("albumArtist"))
        }
    }.getOrNull()

    /** Last known-good rows remain usable while Android is rebuilding its media index. */
    fun loadLastKnown(context: Context): List<Track>? = readTracks(context)?.second?.takeIf { it.isNotEmpty() }

    fun load(context: Context): List<Track>? {
        val (cachedVersion, tracks) = readTracks(context) ?: return null
        if (cachedVersion != version(context) || tracks.isEmpty()) return null
        return tracks
    }

    fun save(context: Context, tracks: List<Track>) {
        runCatching {
            val payload = JSONObject().put("version", version(context))
            val entries = JSONArray()
            tracks.forEach { track -> entries.put(JSONObject()
                .put("id", track.id).put("title", track.title).put("artist", track.artist)
                .put("album", track.album).put("albumId", track.albumId).put("path", track.path)
                .put("durationMs", track.durationMs).put("albumArtist", track.albumArtist)) }
            payload.put("tracks", entries)
            val file = cacheFile(context)
            val temporary = File(context.filesDir, FILE_NAME + ".tmp")
            temporary.writeText(payload.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) }
        }
    }
}


private const val SETTINGS_BACKUP_FORMAT = "canvas-player-settings-backup"
private val SETTINGS_BACKUP_PREFS = listOf("library", "video_sync")

fun exportAppSettingsBackup(context: Context): String {
    val preferences = JSONObject()
    SETTINGS_BACKUP_PREFS.forEach { name ->
        val values = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            val encoded = when (value) {
                is String -> JSONObject().put("type", "string").put("value", value)
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is Set<*> -> JSONObject().put("type", "string_set").put("value", JSONArray(value.filterIsInstance<String>()))
                else -> null
            }
            if (encoded != null) values.put(key, encoded)
        }
        preferences.put(name, values)
    }
    return JSONObject().put("format", SETTINGS_BACKUP_FORMAT).put("version", 1).put("preferences", preferences).toString(2)
}

fun importAppSettingsBackup(context: Context, content: String) {
    val backup = JSONObject(content)
    require(backup.optString("format") == SETTINGS_BACKUP_FORMAT) { "This is not a Canvas Player settings backup" }
    val preferences = backup.getJSONObject("preferences")
    SETTINGS_BACKUP_PREFS.forEach { name ->
        val values = preferences.optJSONObject(name) ?: return@forEach
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = values.optJSONObject(key) ?: continue
            when (value.optString("type")) {
                "string" -> editor.putString(key, value.optString("value"))
                "boolean" -> editor.putBoolean(key, value.optBoolean("value"))
                "int" -> editor.putInt(key, value.optInt("value"))
                "long" -> editor.putLong(key, value.optLong("value"))
                "float" -> editor.putFloat(key, value.optDouble("value").toFloat())
                "string_set" -> {
                    val array = value.optJSONArray("value") ?: JSONArray()
                    editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                }
            }
        }
        editor.apply()
    }
}
