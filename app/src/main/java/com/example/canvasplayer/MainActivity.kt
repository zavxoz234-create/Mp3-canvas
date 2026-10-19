package com.example.canvasplayer

import android.Manifest
import android.content.ComponentName
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracks = loadTracks(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(this))

        perms.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(tracks, controller) }
            }
        }
    }
}

data class TrimJob(val src: Uri, val lyrics: Boolean, val track: Track)

@Composable
fun App(tracks: List<Track>, player: Player?) {
    val ctx = LocalContext.current
    var inPlayer by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var trim by remember { mutableStateOf<TrimJob?>(null) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        currentId = player.currentMediaItem?.mediaId
        playing = player.isPlaying
        val l = object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { currentId = m?.mediaId }
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val job = trim
    val track = tracks.firstOrNull { it.id.toString() == currentId }
    BackHandler(job != null || inPlayer) { if (job != null) trim = null else inPlayer = false }

    when {
        job != null -> TrimScreen(job.src, onCancel = { trim = null }) { s, e, finished ->
            Exporter.trim(ctx, job.src, s, e, Store.appFile(ctx, job.track, job.lyrics)) { r ->
                finished()
                if (r.isSuccess) { refresh++; trim = null }
                Toast.makeText(ctx, if (r.isSuccess) "Saved" else "Export failed: ${r.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
        inPlayer && player != null && track != null ->
            PlayerScreen(track, player, playing, refresh, onTrim = { u, l -> trim = TrimJob(u, l, track) },
                onChanged = { refresh++ })
        else -> LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            items(tracks) { t ->
                Column(Modifier.fillMaxWidth().clickable {
                    if (player != null) {
                        player.setMediaItems(tracks.map { it.toMediaItem() }, tracks.indexOf(t), 0)
                        player.prepare(); player.play(); inPlayer = true
                    }
                }.padding(16.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    track: Track, player: Player, playing: Boolean, refresh: Int,
    onTrim: (Uri, Boolean) -> Unit, onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    var lyricsMode by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pendingLyrics by remember { mutableStateOf(false) }
    var folderMatches by remember { mutableStateOf<List<DocumentFile>?>(null) }
    var pos by remember { mutableLongStateOf(0L) }

    val visuals by produceState<Pair<Uri?, Uri?>>(null to null, track.id, refresh) {
        value = withContext(Dispatchers.IO) { Store.find(ctx, track, false) to Store.find(ctx, track, true) }
    }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; delay(500) } }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onTrim(u, pendingLyrics)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            val files = DocumentFile.fromTreeUri(ctx, u)?.listFiles()?.toList().orEmpty()
            folderMatches = MetadataMatcher.match(files, track)
            if (folderMatches.isNullOrEmpty())
                Toast.makeText(ctx, "No video matching \"${track.title}\" in that folder", Toast.LENGTH_LONG).show()
        }
    }

    val videoUri = if (lyricsMode) visuals.second ?: visuals.first else visuals.first

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtBox(videoUri, track.artUri, player,
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(16.dp))
        Text(track.title, style = MaterialTheme.typography.headlineSmall)
        Text("${track.artist} - ${track.album}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = lyricsMode, onClick = { lyricsMode = !lyricsMode }, label = { Text("Lyrics video") })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Scan this device for video") }, onClick = {
                        menu = false; onChanged()
                        Toast.makeText(ctx, if (visuals.first != null) "Video found" else "No video found", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Add video from file") }, onClick = {
                        menu = false; pendingLyrics = false; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Find in folder (match by metadata)") }, onClick = {
                        menu = false; pendingLyrics = false; pickFolder.launch(null) })
                    DropdownMenuItem(text = { Text("Add lyrics video") }, onClick = {
                        menu = false; pendingLyrics = true; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Remove video") }, onClick = {
                        menu = false
                        Store.appFile(ctx, track, false).delete(); Store.appFile(ctx, track, true).delete()
                        onChanged()
                    })
                }
            }
        }
        val dur = player.duration.coerceAtLeast(1L)
        Slider(value = pos.toFloat().coerceIn(0f, dur.toFloat()), onValueChange = { player.seekTo(it.toLong()); pos = it.toLong() },
            valueRange = 0f..dur.toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ player.seekToPreviousMediaItem() }) { Text("<<") }
            Button({ if (playing) player.pause() else player.play() }) { Text(if (playing) "Pause" else "Play") }
            TextButton({ player.seekToNextMediaItem() }) { Text(">>") }
        }
    }

    folderMatches?.takeIf { it.isNotEmpty() }?.let { list ->
        AlertDialog(onDismissRequest = { folderMatches = null }, confirmButton = {},
            title = { Text("Matches for \"${track.title}\"") },
            text = {
                Column {
                    list.forEach { f ->
                        TextButton({ folderMatches = null; onTrim(f.uri, pendingLyrics) }) { Text(f.name ?: "video") }
                    }
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(src: Uri, onCancel: () -> Unit, onSave: (Long, Long, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val durMs = remember(src) {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(ctx, src); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        catch (e: Exception) { 0L } finally { r.release() }
    }
    if (durMs <= 0) {
        Column(Modifier.padding(24.dp)) { Text("Couldn't read this video."); TextButton(onCancel) { Text("Back") } }
        return
    }
    var range by remember { mutableStateOf(0f..durMs.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember {
        ExoPlayer.Builder(ctx).build().apply { volume = 0f; setMediaItem(MediaItem.fromUri(src)); prepare() }
    }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    fun fmt(ms: Float) = "%d:%02d".format((ms / 60000).toInt(), ((ms / 1000) % 60).toInt())

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        AndroidView({ PlayerView(it).apply { player = preview; useController = false } },
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
        RangeSlider(value = range, valueRange = 0f..durMs.toFloat(), onValueChange = {
            preview.seekTo(if (it.start != range.start) it.start.toLong() else it.endInclusive.toLong())
            range = it
        })
        Text("Start ${fmt(range.start)}   End ${fmt(range.endInclusive)}   (${fmt(range.endInclusive - range.start)})")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onCancel, enabled = !busy) { Text("Cancel") }
            OutlinedButton({
                scope.launch {
                    preview.seekTo(range.start.toLong()); preview.play()
                    while (preview.isPlaying && preview.currentPosition < range.endInclusive) delay(200)
                    preview.pause()
                }
            }, enabled = !busy) { Text("Preview") }
            Button({
                busy = true
                onSave(range.start.toLong(), range.endInclusive.toLong()) { busy = false }
            }, enabled = !busy) { Text(if (busy) "Saving..." else "Trim & save") }
        }
    }
}
