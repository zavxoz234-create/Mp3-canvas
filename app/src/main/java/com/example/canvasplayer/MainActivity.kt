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
import kotlinx.coroutines.*
import com.yausername.youtubedl_android.YoutubeDLRequest

data class Track(
    val id: Long, val title: String, val artist: String,
    val album: String, val albumId: Long, val path: String, val durationMs: Long = 0L,
    val albumArtist: String = "", val documentUri: Uri? = null
) {
    val uri: Uri get() = documentUri ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
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

fun Track.albumGroupingKey(): String {
    val albumName = normalizeGroupValue(album).ifBlank { "unknownalbum" }
    val albumPerformer = normalizeGroupValue(albumArtist)
    val folder = normalizeGroupValue(File(path).parent.orEmpty())
    return "$albumName\u0000${albumPerformer.ifBlank { folder }}"
}

fun Track.belongsToSameAlbum(other: Track): Boolean = albumGroupingKey() == other.albumGroupingKey()

data class TimedLyric(val timeMs: Long, val text: String)

/** Automatically resolves and downloads Spotify Canvas / official vertical video loops for local tracks. */
object CanvasFetcher {
    private val downloadingIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    fun fetchCanvasIfNeeded(context: Context, track: Track, scope: CoroutineScope, onComplete: () -> Unit = {}) {
        val existing = Store.find(context, track, false)
        if (existing != null) return
        if (downloadingIds.contains(track.id)) return

        downloadingIds.add(track.id)
        scope.launch(Dispatchers.IO) {
            try {
                val cleanTitle = track.title.replace(Regex("(?i)\\\\(.*?(remix|feat|ft|live|version|edit).*?\\\\)"), "").trim()
                val cleanArtist = track.artist.split('/', ',')[0].trim()
                val query = "$cleanArtist $cleanTitle canvas official vertical"

                val targetFile = Store.appFile(context, track, false)
                targetFile.parentFile?.mkdirs()
                val tempFile = File(targetFile.parentFile, "${track.id}.canvas_tmp.mp4")
                tempFile.delete()

                val request = YoutubeDLRequest("ytsearch1:$query").apply {
                    addOption("--no-playlist")
                    addOption("--force-overwrites")
                    addOption("-f", "bestvideo[height<=1080][ext=mp4]/best[ext=mp4]/best")
                    addOption("--remux-video", "mp4")
                    addOption("-o", tempFile.absolutePath)
                }

                val result = runCatching {
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                }

                if (result.isSuccess && tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(targetFile)
                    withContext(Dispatchers.Main) { onComplete() }
                } else {
                    tempFile.delete()
                }
            } catch (_: Exception) {
            } finally {
                downloadingIds.remove(track.id)
            }
        }
    }
}

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
        emptyList()
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
            val albumArtist = track.albumArtist.ifBlank { readEmbeddedAlbumArtist(c, track) }
            out += track.copy(albumArtist = albumArtist)
        }
    }
    return out
}

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

object Store {
    private fun dir(c: Context) = File(c.getExternalFilesDir(null), "canvas").apply { mkdirs() }
    fun appFile(c: Context, t: Track, lyrics: Boolean) = File(dir(c), "${t.id}${if (lyrics) ".lyrics" else ""}.mp4")

    fun find(c: Context, t: Track, lyrics: Boolean): Uri? {
        val app = appFile(c, t, lyrics)
        if (app.isFile && app.length() > 0) return Uri.fromFile(app)
        return null
    }
}

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
