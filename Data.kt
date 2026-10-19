package com.example.canvasplayer

import android.content.ContentUris
import android.content.Context
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

data class Track(
    val id: Long, val title: String, val artist: String,
    val album: String, val albumId: Long, val path: String
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

fun loadTracks(c: Context): List<Track> {
    val out = mutableListOf<Track>()
    val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA
    )
    c.contentResolver.query(
        col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
    )?.use { cur ->
        while (cur.moveToNext()) out += Track(
            cur.getLong(0), cur.getString(1) ?: "Unknown", cur.getString(2) ?: "Unknown",
            cur.getString(3) ?: "", cur.getLong(4), cur.getString(5) ?: ""
        )
    }
    return out
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
