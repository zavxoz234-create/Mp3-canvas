package com.example.canvasplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.mapper.VideoFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

private val Ink = Color(0xFF101714)
private val Panel = Color(0xFF1B2420)
private val Mint = Color(0xFF9CE7B5)
private val LocalAccent = compositionLocalOf { Mint }

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reloadTracks()
    }
    private val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun reloadTracks(root: String? = null) {
        reloadScope.launch { tracks = withContext(Dispatchers.IO) { loadTracks(this@MainActivity) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        perms.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        reloadTracks()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel)) { App(tracks, controller) { reloadTracks() } } }
    }
}

private data class TrimJob(val src: Uri, val lyrics: Boolean, val track: Track)
private data class DownloadVariant(val id: String, val label: String, val hasAudio: Boolean)
private data class TrackEdit(val track: Track, val title: String, val trackNumber: String, val year: String, val genre: String, val artist: String, val album: String, val albumArtist: String, val composer: String, val comment: String, val coverUri: Uri?, val coverBytes: ByteArray? = null, val clearArtwork: Boolean = false, val applyArtworkToAlbum: Boolean = false)
private data class CoverResult(val title: String, val subtitle: String, val bytes: ByteArray, val width: Int, val height: Int)

private fun artworkTargets(edit: TrackEdit, allTracks: List<Track>): List<Track> {
    val changesArtwork = edit.coverBytes != null || edit.coverUri != null || edit.clearArtwork
    return if (edit.applyArtworkToAlbum && changesArtwork) {
        allTracks.filter { it.belongsToSameAlbum(edit.track) }.ifEmpty { listOf(edit.track) }
    } else listOf(edit.track)
}

@Composable private fun App(tracks: List<Track>, player: Player?, onFolderChange: (String?) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("library", 0) }
    var folder by remember { mutableStateOf(prefs.getString("folder", null)) }
    var folderRoots by remember { mutableStateOf(prefs.getStringSet("folders_v2", null)?.toList() ?: listOfNotNull(folder)) }
    var folderUris by remember { mutableStateOf(prefs.getStringSet("folder_uris_v2", null)?.mapNotNull { entry -> entry.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()) }
    var folderLabel by remember { mutableStateOf(prefs.getString("folderLabel", null)) }
    var folderTree by remember { mutableStateOf(prefs.getString("folderUri", null)) }
    var visibleTracks by remember { mutableStateOf(tracks) }; var scannedFolderTracks by remember { mutableStateOf<List<Track>?>(null) }; var refreshingFolderScan by remember { mutableStateOf(false) }
    var inPlayer by remember { mutableStateOf(false) }
    var returnToCurrentTrack by remember { mutableIntStateOf(0) }
    val librarySongListState = rememberLazyListState()
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var trim by remember { mutableStateOf<TrimJob?>(null) }
    var pendingEdit by remember { mutableStateOf<TrackEdit?>(null) }
    var editDialogTrack by remember { mutableStateOf<Track?>(null) }
    var showChangelog by remember { mutableStateOf(prefs.getString("seenChangelogVersion", "") != "0.7.2") }
    var ytDlpReady by remember { mutableStateOf<Boolean?>(null) }
    var ytDlpInitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val initialized = withContext(Dispatchers.IO) { runCatching {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
        } }
        ytDlpReady = initialized.isSuccess
        ytDlpInitError = initialized.exceptionOrNull()?.message?.take(400)
    }
    val editPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val edit = pendingEdit
        pendingEdit = null
        if (result.resultCode == android.app.Activity.RESULT_OK && edit != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit, tracks) } }
            if (saved.isSuccess) {
                if (folder != null) onFolderChange(folder)
                refresh++
                Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show()
            } else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(folderRoots, folderUris, folder, folderTree) {
        val selectedFolders = folderRoots.mapNotNull { root ->
            val treeUri = folderUris[root] ?: if (root == folder) folderTree else null
            treeUri?.let { root to it }
        }
        scannedFolderTracks = if (folderRoots.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            SelectedFolderScanCache.load(ctx, selectedFolders) ?: loadTracksFromSelectedFolders(ctx, selectedFolders).also {
                SelectedFolderScanCache.save(ctx, selectedFolders, it)
            }
        }
    }
    LaunchedEffect(tracks, folderRoots, folderUris, scannedFolderTracks) {
        visibleTracks = if (folderRoots.isEmpty()) tracks else withContext(Dispatchers.IO) {
            val roots = folderRoots.map { it.replace("\\", "/").trimEnd('/') }
            val byPath = tracks.filter { track -> roots.any { root ->
                val path = track.path.replace("\\", "/").trimEnd('/')
                path.startsWith("$root/", true) || path.equals(root, true)
            } }
            val selectedFolders = folderRoots.mapNotNull { root ->
                val treeUri = folderUris[root] ?: if (root == folder) folderTree else null
                treeUri?.let { root to it }
            }
            val folderTracks = scannedFolderTracks.orEmpty()
            val indexedPaths = byPath.map { it.path.replace("\\", "/").trimEnd('/').lowercase(Locale.ROOT) }.toSet()
            (byPath + folderTracks.filterNot { it.path.replace("\\", "/").trimEnd('/').lowercase(Locale.ROOT) in indexedPaths })
                .distinctBy { it.path.replace("\\", "/").trimEnd('/').lowercase(Locale.ROOT) }
        }
    }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        currentId = player.currentMediaItem?.mediaId; playing = player.isPlaying
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentId = mediaItem?.mediaId }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener); onDispose { player.removeListener(listener) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val volume = docId?.substringBefore(':')
            val relative = docId?.substringAfter(':', "").orEmpty()
            val root = when {
                volume.equals("primary", true) -> "/storage/emulated/0/$relative"
                volume.equals("home", true) -> "/storage/emulated/0/Documents/$relative"
                !volume.isNullOrBlank() && volume.matches(Regex("[A-Fa-f0-9-]{8,}")) -> "/storage/$volume/$relative"
                else -> null
            }
            if (root == null) Toast.makeText(ctx, "This storage provider cannot be used for folder filtering", Toast.LENGTH_LONG).show()
            else {
                folder = root; folderRoots = (folderRoots + root).distinct(); folderLabel = folderRoots.joinToString(" + ") { File(it).name.ifBlank { it } }; folderTree = uri.toString()
                folderUris = folderUris + (root to uri.toString())
                prefs.edit().putString("folder", root).putString("folderLabel", folderLabel).putString("folderUri", folderTree)
                    .putStringSet("folders_v2", folderRoots.toSet()).putStringSet("folder_uris_v2", folderUris.map { (path, tree) -> "$path\t$tree" }.toSet()).apply()
                onFolderChange(root)
            }
        }
    }
    val refreshSelectedScan = {
        if (!refreshingFolderScan && folderRoots.isNotEmpty()) scope.launch {
            val selectedFolders = folderRoots.mapNotNull { root ->
                val treeUri = folderUris[root] ?: if (root == folder) folderTree else null
                treeUri?.let { root to it }
            }
            refreshingFolderScan = true
            try {
                val scan = withContext(Dispatchers.IO) {
                    loadTracksFromSelectedFolders(ctx, selectedFolders).also { SelectedFolderScanCache.save(ctx, selectedFolders, it) }
                }
                scannedFolderTracks = scan
                Toast.makeText(ctx, "Folder scan refreshed: " + scan.size + " songs", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(ctx, "Could not refresh folder scan: " + error.message, Toast.LENGTH_LONG).show()
            } finally { refreshingFolderScan = false }
        }
    }
    val job = trim
    val track = visibleTracks.firstOrNull { it.id.toString() == currentId } ?: tracks.firstOrNull { it.id.toString() == currentId }
    var accent by remember { mutableStateOf(Mint) }
    LaunchedEffect(track?.id, refresh) {
        accent = withContext(Dispatchers.IO) { track?.let { accentFromArtwork(ctx, it) } ?: Mint }
    }
    val leavePlayer = { inPlayer = false; returnToCurrentTrack++ }
    BackHandler(job != null || inPlayer) { if (job != null) trim = null else leavePlayer() }
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Ink, primaryContainer = accent.copy(alpha = .28f), onPrimaryContainer = Color.White,
        secondary = accent, onSecondary = Ink, secondaryContainer = accent.copy(alpha = .22f), onSecondaryContainer = Color.White,
        tertiary = accent, onTertiary = Ink, background = Ink, surface = Panel, onSurface = Color.White,
        surfaceVariant = accent.copy(alpha = .12f), onSurfaceVariant = Color.LightGray
    )
    val screen = when { job != null -> "trim"; inPlayer && player != null && track != null -> "player"; else -> "library" }
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalAccent provides accent) { Surface(Modifier.fillMaxSize(), color = Ink) {
        when (screen) {
            "trim" -> job?.let { activeJob -> TrimScreen(activeJob.src, onCancel = { trim = null }) { start, end, done ->
                Exporter.trim(ctx, activeJob.src, start, end, Store.appFile(ctx, activeJob.track, activeJob.lyrics)) { result ->
                    done(); if (result.isSuccess) { refresh++; trim = null }
                    Toast.makeText(ctx, if (result.isSuccess) "Video saved" else "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } }
            "player" -> if (player != null && track != null) PlayerScreen(track, tracks, player, playing, shuffle, refresh, ytDlpReady, ytDlpInitError,
                folderUris.entries.firstOrNull { (root, _) -> track.path.replace("\\", "/").startsWith(root.replace("\\", "/").trimEnd('/') + "/", true) }?.value ?: folderTree,
                folderRoots.firstOrNull { root -> track.path.replace("\\", "/").startsWith(root.replace("\\", "/").trimEnd('/') + "/", true) } ?: folder,
                onBack = { leavePlayer() }, onShuffle = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle },
                onYtDlpInit = { ready, error -> ytDlpReady = ready; ytDlpInitError = error },
                onTrim = { uri, lyrics -> trim = TrimJob(uri, lyrics, track) }, onChanged = { refresh++ },
                onEditTrack = { editDialogTrack = track })
            else -> LibraryScreen(visibleTracks, track, playing, folderLabel ?: folderRoots.joinToString(" + ") { File(it).name.ifBlank { it } },
                onChooseFolder = { picker.launch(null) }, onRefreshScan = refreshSelectedScan, refreshingFolderScan = refreshingFolderScan, onClearFolder = { folder = null; folderRoots = emptyList(); folderLabel = null; folderTree = null; folderUris = emptyMap(); prefs.edit().remove("folder").remove("folderLabel").remove("folderUri").remove("folders_v2").remove("folder_uris_v2").apply(); onFolderChange(null) },
                onShuffle = { if (player != null && visibleTracks.isNotEmpty()) { player.setMediaItems(visibleTracks.map { it.toMediaItem() }); player.shuffleModeEnabled = true; player.prepare(); player.play(); shuffle = true; inPlayer = true } },
                onPlay = { selected -> if (player != null) { player.setMediaItems(visibleTracks.map { it.toMediaItem() }, visibleTracks.indexOf(selected), 0); player.shuffleModeEnabled = shuffle; player.prepare(); player.play(); inPlayer = true } },
                onMiniOpen = { val wasPlaying = player?.isPlaying == true; inPlayer = true; if (wasPlaying) player?.play() },
                onMiniPlay = { if (player != null) { if (playing) player.pause() else player.play() } },
                onEdit = { edit ->
                    pendingEdit = edit
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            val request = MediaStore.createWriteRequest(ctx.contentResolver, artworkTargets(edit, tracks).map { it.uri })
                            editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit, tracks) } }
                            pendingEdit = null
                            if (saved.isSuccess) { if (folder != null) onFolderChange(folder); refresh++; Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
                            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }, returnToCurrentTrack = returnToCurrentTrack, songListState = librarySongListState)
        }
    }
    editDialogTrack?.let { item -> EditTrackDialog(item, tracks.count { it.belongsToSameAlbum(item) }, onDismiss = { editDialogTrack = null }) { edit ->
        editDialogTrack = null
        pendingEdit = edit
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val request = MediaStore.createWriteRequest(ctx.contentResolver, artworkTargets(edit, tracks).map { it.uri })
                editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
        } else scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit, tracks) } }
            pendingEdit = null
            if (saved.isSuccess) { if (folder != null) onFolderChange(folder); refresh++; Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    } }
    if (showChangelog) AlertDialog(onDismissRequest = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.7.2").apply() },
        title = { Text("Canvas Player 0.7.2") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("What’s new", style = MaterialTheme.typography.titleMedium)
            Text("• Scan selected folders directly so songs appear even when Android has not indexed them\n• Reload lyrics for each track and follow the current playback position\n• Keep manual video sync available when a video has no audio waveform\n• Downloaded videos are saved as companion videos and shown in the player\n• Find more relevant album covers faster and return to the current song in the library after leaving Now Playing.")
        } }, confirmButton = { TextButton(onClick = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.7.2").apply() }) { Text("Got it") } })
    } }
}

@Composable private fun LibraryScreen(tracks: List<Track>, current: Track?, playing: Boolean, folder: String?, onChooseFolder: () -> Unit, onRefreshScan: () -> Unit, refreshingFolderScan: Boolean, onClearFolder: () -> Unit, onShuffle: () -> Unit, onPlay: (Track) -> Unit, onMiniOpen: () -> Unit, onMiniPlay: () -> Unit, onEdit: (TrackEdit) -> Unit, returnToCurrentTrack: Int, songListState: LazyListState) {
    var search by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Track?>(null) }
    var section by remember { mutableStateOf("Songs") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    val shown = tracks.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) || it.albumArtist.contains(search, true) }
    val artistPairs = tracks.flatMap { track -> splitArtists(track.artist).ifEmpty { listOf("Unknown artist") }.map { it to track } }
    val artists = artistPairs.groupBy({ it.first.trim().lowercase(Locale.ROOT) }, { it })
        .mapValues { (_, entries) -> entries.first().first to entries.map { it.second }.distinctBy { it.id } }.toSortedMap()
    val albums = tracks.groupBy { it.albumGroupingKey() }.toSortedMap()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("CANVAS", style = MaterialTheme.typography.labelLarge, color = LocalAccent.current); Text("Your music", style = MaterialTheme.typography.headlineMedium) }
            Box {
                IconButton(onClick = { listMenu = true }) { Icon(Icons.Default.MoreVert, "Library options", tint = LocalAccent.current) }
                DropdownMenu(listMenu, { listMenu = false }) {
                    DropdownMenuItem(text = { Text("Add music folder") }, onClick = { listMenu = false; onChooseFolder() }); if (folder != null) DropdownMenuItem(enabled = !refreshingFolderScan, text = { Text(if (refreshingFolderScan) "Refreshing scan…" else "Refresh selected folders") }, onClick = { listMenu = false; onRefreshScan() })
                    if (folder != null) DropdownMenuItem(text = { Text("Clear scanned folders") }, onClick = { listMenu = false; onClearFolder() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(folder?.let { "Scanning selected folders: $it" } ?: "Showing all songs · choose folders to filter", color = Color.LightGray, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search songs, artists, albums") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        TabRow(selectedTabIndex = listOf("Songs", "Artists", "Albums").indexOf(section), containerColor = Ink) {
            listOf("Songs", "Artists", "Albums").forEachIndexed { index, tab -> Tab(selected = section == tab, onClick = { section = tab; selectedGroup = null }, text = { Text(tab) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.Shuffle, "Shuffle songs", tint = LocalAccent.current) }
            Text("Shuffle", color = Color.LightGray, modifier = Modifier.clickable(enabled = tracks.isNotEmpty(), onClick = onShuffle))
            Spacer(Modifier.weight(1f))
        }
        val groupKey = selectedGroup
        val groupCount = if (groupKey == null) 0 else if (section == "Artists") artists[groupKey]?.second?.size ?: 0 else albums[groupKey].orEmpty().size
        val groupTitle = when {
            groupKey == null -> section
            section == "Artists" -> artists[groupKey]?.first ?: groupKey
            else -> albums[groupKey]?.firstOrNull()?.album?.ifBlank { "Unknown album" } ?: "Unknown album"
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(groupTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalAccent.current); Text(if (selectedGroup != null) "$groupCount tracks" else when(section) { "Artists" -> "${artists.size} artists"; "Albums" -> "${albums.size} albums"; else -> "${shown.size} tracks" }, style = MaterialTheme.typography.titleLarge) }
        }
        val groupTracks = when {
            section == "Artists" && selectedGroup != null -> artists[selectedGroup]?.second.orEmpty()
            section == "Albums" && selectedGroup != null -> albums[selectedGroup].orEmpty()
            else -> emptyList()
        }
        val songRows = if (selectedGroup != null) groupTracks.filter { it.title.contains(search, true) } else shown
        LaunchedEffect(returnToCurrentTrack, current?.id, section, selectedGroup, search, tracks) {
            if (returnToCurrentTrack > 0 && current != null) {
                val rowIndex = songRows.indexOfFirst { it.id == current.id }
                if (rowIndex >= 0 && (section == "Songs" || selectedGroup != null)) {
                    songListState.animateScrollToItem(rowIndex + if (selectedGroup != null) 1 else 0)
                } else {
                    section = "Songs"
                    selectedGroup = null
                    val matchesSearch = current.title.contains(search, true) || current.artist.contains(search, true) || current.album.contains(search, true) || current.albumArtist.contains(search, true)
                    if (!matchesSearch) search = ""
                }
            }
        }
        when {
            tracks.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (folder == null) "Choose a music folder to get started" else "No indexed songs found in this folder yet", color = Color.LightGray)
                    if (folder != null) TextButton(onClick = onChooseFolder) { Text("Choose another folder") }
                }
            }
            section == "Artists" && selectedGroup == null -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                itemsIndexed(artists.keys.toList(), key = { _, name -> name.lowercase() }) { _, name ->
                    val label = artists[name]?.first ?: name
                    val songs = artists[name]?.second.orEmpty(); val sample = songs.firstOrNull()
                    Row(Modifier.fillMaxWidth().clickable { selectedGroup = name }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sample?.let { AlbumArt(it, Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(label, style = MaterialTheme.typography.titleMedium); Text("${songs.size} songs", color = Color.LightGray) }
                        IconButton(onClick = { selectedGroup = name }) { Icon(Icons.Default.ChevronRight, "Open artist $label") }
                    }
                }
            }
            section == "Albums" && selectedGroup == null -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                itemsIndexed(albums.keys.toList(), key = { _, name -> name }) { _, key ->
                    val songs = albums[key].orEmpty(); val sample = songs.firstOrNull(); val title = sample?.album?.ifBlank { "Unknown album" } ?: "Unknown album"
                    Row(Modifier.fillMaxWidth().clickable { selectedGroup = key }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sample?.let { AlbumArt(it, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(sample?.albumArtist?.ifBlank { sample.artist }.orEmpty(), color = Color.LightGray) }
                        IconButton(onClick = { selectedGroup = key }) { Icon(Icons.Default.ChevronRight, "Open album $title") }
                    }
                }
            }
            else -> LazyColumn(state = songListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (selectedGroup != null) item { TextButton(onClick = { selectedGroup = null }) { Icon(Icons.Default.ArrowBack, null); Text(" Back to $section") } }
                itemsIndexed(songRows, key = { _, t -> t.id }) { _, t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlay(t) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(t, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = if (t.id == current?.id) LocalAccent.current else Color.White)
                            Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                        Text(formatTime(t.durationMs), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { edit = t }) { Icon(Icons.Default.MoreVert, "Song options", tint = Color.LightGray) }
                    }
                }
            }
        }
        current?.let { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).padding(bottom = 24.dp).clip(RoundedCornerShape(18.dp)).background(Panel).clickable { onMiniOpen() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(t, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                IconButton(onClick = onMiniPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = LocalAccent.current) }
            }
        }
    }
    edit?.let { item -> EditTrackDialog(item, tracks.count { it.belongsToSameAlbum(item) }, onDismiss = { edit = null }) { onEdit(it); edit = null } }
}

@Composable private fun AlbumArt(track: Track, modifier: Modifier = Modifier) {
    var embedded by remember(track.id) { mutableStateOf<ByteArray?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(track.id) { embedded = withContext(Dispatchers.IO) {
        runCatching { MediaMetadataRetriever().run { setDataSource(ctx, track.uri); val b = embeddedPicture; release(); b } }.getOrNull()
    } }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF26473A), Color(0xFF111A16)))), contentAlignment = Alignment.Center) {
        if (embedded != null) AsyncImage(embedded, null, Modifier.fillMaxSize())
        else AsyncImage(track.artUri, null, Modifier.fillMaxSize(), onError = { })
        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = if (embedded == null) .62f else 0f), modifier = Modifier.size(26.dp))
    }
}

@Composable private fun BlurredAlbumBackground(track: Track, modifier: Modifier = Modifier) {
    // Avoid full-screen artwork blur; it causes costly GPU work during every UI update.
    Box(modifier.background(Brush.verticalGradient(listOf(LocalAccent.current.copy(alpha = .12f), Ink, Ink))))
}

private fun imageDimensions(context: android.content.Context, uri: Uri): Pair<Int, Int>? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}.getOrNull()

@Composable private fun CoverArtDialog(track: Track, onDismiss: () -> Unit, onSelect: (ByteArray?, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var albumMode by remember { mutableStateOf(false) }
    var highResolution by remember { mutableStateOf(true) }
    var query by remember(track.id) { mutableStateOf("${track.title} ${track.artist}") }
    var results by remember { mutableStateOf<List<CoverResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var embedded by remember(track.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(track.id) { embedded = withContext(Dispatchers.IO) {
        runCatching { MediaMetadataRetriever().run { setDataSource(ctx, track.uri); val bytes = embeddedPicture; release(); bytes } }.getOrNull()
    } }
    fun search() {
        searching = true; error = null
        scope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { searchDeezerCovers(query.trim(), albumMode, highResolution, track.album, track.albumArtist.ifBlank { track.artist }, track.title, track.artist) } }
            searching = false
            if (fetched.isSuccess) { results = fetched.getOrDefault(emptyList()); if (results.isEmpty()) error = "No exact album or artist match found. Try adding the artist or album name." }
            else error = fetched.exceptionOrNull()?.message?.take(250) ?: "Cover search failed"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find album art") },
        text = { Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(highResolution, { highResolution = it })
                Text("High Resolution", modifier = Modifier.weight(1f))
                Icon(Icons.Default.Settings, "Deezer artwork search")
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(if (albumMode) "Album and artist" else "Song and artist") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!albumMode, { albumMode = false; query = "${track.title} ${track.artist}" }, label = { Text("Song") })
                FilterChip(albumMode, { albumMode = true; query = "${track.album} ${track.albumArtist.ifBlank { track.artist }}" }, label = { Text("Album") })
                Button(onClick = ::search, enabled = !searching && query.isNotBlank()) { Text(if (searching) "Searching…" else "Search") }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(null, true) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Panel), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.Gray, modifier = Modifier.size(44.dp)) }
                    Text("No album art", style = MaterialTheme.typography.labelMedium)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = embedded != null) { embedded?.let { onSelect(it, false) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Panel), contentAlignment = Alignment.Center) {
                        if (embedded != null) AsyncImage(embedded, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Icon(Icons.Default.MusicNote, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                    }
                    Text("Embedded album art", style = MaterialTheme.typography.labelMedium)
                }
            }
            results.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { result -> Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(result.bytes, false) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Panel), contentAlignment = Alignment.Center) {
                        AsyncImage(result.bytes, result.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Text("${result.width}×${result.height}", Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .82f)).padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    Text(result.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            } }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private fun normalizeCoverMatch(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

private suspend fun searchDeezerCovers(
    query: String,
    byAlbum: Boolean,
    highResolution: Boolean,
    expectedAlbum: String,
    expectedAlbumArtist: String,
    expectedSong: String,
    expectedSongArtist: String
): List<CoverResult> = coroutineScope {
    if (query.isBlank()) return@coroutineScope emptyList()
    val endpoint = if (byAlbum) "album" else "track"
    val api = URL("https://api.deezer.com/search/$endpoint?q=4{Uri.encode(query)}&limit=25")
    val connection = api.openConnection().apply {
        connectTimeout = 5_000
        readTimeout = 7_000
        setRequestProperty("User-Agent", "CanvasPlayer/0.7.3")
    }
    val json = try { connection.getInputStream().bufferedReader().use { it.readText() } }
        finally { (connection as? HttpURLConnection)?.disconnect() }
    val data = JSONObject(json).optJSONArray("data") ?: return@coroutineScope emptyList()
    data class Candidate(val album: JSONObject, val artist: String, val imageUrl: String)
    val expectedTitle = normalizeCoverMatch(if (byAlbum) expectedAlbum else expectedSong)
    val expectedArtist = normalizeCoverMatch(if (byAlbum) expectedAlbumArtist else expectedSongArtist)
    val candidates = (0 until data.length()).mapNotNull { index ->
        val item = data.optJSONObject(index) ?: return@mapNotNull null
        val album = if (byAlbum) item else item.optJSONObject("album") ?: return@mapNotNull null
        val title = normalizeCoverMatch(item.optString("title"))
        val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
            .ifBlank { album.optJSONObject("artist")?.optString("name").orEmpty() }
        if (title != expectedTitle || normalizeCoverMatch(artist) != expectedArtist) return@mapNotNull null
        val imageUrl = album.optString(if (highResolution) "cover_xl" else "cover_big")
            .takeIf { it.startsWith("https://") }
            ?: album.optString("cover_big").takeIf { it.startsWith("https://") }
            ?: return@mapNotNull null
        Candidate(album, artist, imageUrl)
    }.distinctBy { it.album.optString("id", it.album.optString("title")) }.take(8)

    candidates.map { candidate -> async(Dispatchers.IO) {
        runCatching {
            val imageConnection = URL(candidate.imageUrl).openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                setRequestProperty("User-Agent", "CanvasPlayer/0.7.3")
            }
            val bytes = try { imageConnection.getInputStream().use { it.readBytes() } }
                finally { (imageConnection as? HttpURLConnection)?.disconnect() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            CoverResult(candidate.album.optString("title"), candidate.artist, bytes, bounds.outWidth, bounds.outHeight)
        }.getOrNull()
    } }.awaitAll().filterNotNull()
}
@Composable private fun EditTrackDialog(track: Track, albumTrackCount: Int, onDismiss: () -> Unit, onSave: (TrackEdit) -> Unit) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var trackNo by remember(track.id) { mutableStateOf("") }; var year by remember(track.id) { mutableStateOf("") }
    var genre by remember(track.id) { mutableStateOf("") }; var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }; var albumArtist by remember(track.id) { mutableStateOf(track.albumArtist) }
    var composer by remember(track.id) { mutableStateOf("") }; var comment by remember(track.id) { mutableStateOf("") }
    var cover by remember(track.id) { mutableStateOf<Uri?>(null) }
    var coverBytes by remember(track.id) { mutableStateOf<ByteArray?>(null) }
    var clearArtwork by remember(track.id) { mutableStateOf(false) }
    var applyArtworkToAlbum by remember(track.id) { mutableStateOf(false) }
    var coverDimensions by remember(track.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showCoverFinder by remember(track.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        cover = selected; coverBytes = null; clearArtwork = false; coverDimensions = null
        if (selected != null) scope.launch { coverDimensions = withContext(Dispatchers.IO) { imageDimensions(ctx, selected) } }
    }
    if (showCoverFinder) CoverArtDialog(track, onDismiss = { showCoverFinder = false }) { bytes, remove ->
        coverBytes = bytes; cover = null; clearArtwork = remove
        coverDimensions = bytes?.let { data ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, options)
            options.outWidth to options.outHeight
        }
        showCoverFinder = false
    } else AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit tags") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(track.path.substringAfterLast('/'), color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when { coverBytes != null -> AsyncImage(coverBytes, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))); cover != null -> AsyncImage(cover, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))); else -> AlbumArt(track, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) }
                Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { showCoverFinder = true }) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(4.dp)); Text("Find art") }
                Spacer(Modifier.width(4.dp)); IconButton(onClick = { coverPicker.launch("image/*") }) { Icon(Icons.Default.Image, "Choose from gallery") }
            }
            coverDimensions?.let { (width, height) -> Text("Selected image: ${width} × ${height} px", color = LocalAccent.current, style = MaterialTheme.typography.bodySmall) }
            if (coverBytes != null || cover != null || clearArtwork) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyArtworkToAlbum, onCheckedChange = { applyArtworkToAlbum = it }, enabled = albumTrackCount > 1)
                    Text(if (albumTrackCount > 1) "Apply to all $albumTrackCount tracks in this album" else "This album has only one scanned track", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(trackNo, { trackNo = it }, label = { Text("Track") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Artist tags split on /, feat., and commas. AC/DC, +/-, No/Me, and 25/17 stay intact.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(composer, { composer = it }, label = { Text("Composer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(comment, { comment = it }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onSave(TrackEdit(track, title, trackNo, year, genre, artist, album, albumArtist, composer, comment, cover, coverBytes, clearArtwork, applyArtworkToAlbum)) }) { Text("Save to MP3") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun PlayerScreen(track: Track, queueTracks: List<Track>, player: Player, playing: Boolean, shuffle: Boolean, refresh: Int, ytDlpReady: Boolean?, ytDlpInitError: String?, folderTree: String?, rootFolder: String?, onBack: () -> Unit, onShuffle: () -> Unit, onYtDlpInit: (Boolean, String?) -> Unit, onTrim: (Uri, Boolean) -> Unit, onChanged: () -> Unit, onEditTrack: () -> Unit) {
    val ctx = LocalContext.current
    var lyricsMode by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }; var pendingLyrics by remember { mutableStateOf(false) }; var matches by remember { mutableStateOf<List<DocumentFile>?>(null) }; var pos by remember { mutableLongStateOf(0L) }
    val syncPrefs = remember { ctx.getSharedPreferences("video_sync", 0) }
    var videoOffset by remember(track.id) { mutableLongStateOf(syncPrefs.getLong("offset_${track.id}", 0L)) }
    var syncDialog by remember { mutableStateOf(false) }
    var audioWaveform by remember(track.id) { mutableStateOf<WaveformData?>(null) }
    var videoWaveform by remember(track.id) { mutableStateOf<WaveformData?>(null) }
    var loadingWaveforms by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf(false) }; var downloadUrl by remember { mutableStateOf("") }; var downloading by remember { mutableStateOf(false) }; var downloadProgress by remember { mutableFloatStateOf(0f) }; var updatingYtDlp by remember { mutableStateOf(false) }; var removeSponsorSegments by remember { mutableStateOf(false) }; var ytDlpError by remember { mutableStateOf<String?>(null) }
    var lookingUp by remember { mutableStateOf(false) }; var variants by remember { mutableStateOf<List<DownloadVariant>>(emptyList()) }; var selectedVariant by remember { mutableStateOf<String?>(null) }
    val lyricListState = key(track.id) { rememberLazyListState() }
    var lyricsInteraction by remember { mutableStateOf(false) }
    val lyrics by produceState<List<TimedLyric>>(emptyList(), track.id, folderTree, rootFolder, refresh) {
        value = emptyList()
        value = withContext(Dispatchers.IO) { loadLrc(ctx, track, folderTree, rootFolder) }
    }
    val activeLyricIndex = lyrics.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    LaunchedEffect(lyricListState.isScrollInProgress) {
        if (lyricListState.isScrollInProgress) lyricsInteraction = true
        else { delay(5_000); lyricsInteraction = false }
    }
    LaunchedEffect(track.id) {
        pos = 0L
        lyricsInteraction = false
        lyricListState.scrollToItem(0)
    }
    LaunchedEffect(lyricsMode, lyrics.size, activeLyricIndex, lyricsInteraction) {
        if (lyricsMode && lyrics.isNotEmpty() && !lyricsInteraction) lyricListState.scrollToItem(activeLyricIndex)
    }
    val scope = rememberCoroutineScope()
    val visuals by produceState<Pair<Uri?, Uri?>>(null to null, track.id, refresh) { value = withContext(Dispatchers.IO) { Store.find(ctx, track, false) to Store.find(ctx, track, true) } }
    LaunchedEffect(player, track.id) {
        pos = 0L
        while (true) {
            if (player.currentMediaItem?.mediaId == track.id.toString()) pos = player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onTrim(uri, pendingLyrics) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; val root = DocumentFile.fromTreeUri(ctx, uri); val files = root?.let { MetadataMatcher.findVideosRecursively(it) }.orEmpty(); matches = MetadataMatcher.match(files, track); if (matches.isNullOrEmpty()) Toast.makeText(ctx, "No video matching ${track.title} found in that folder", Toast.LENGTH_LONG).show() }
    }
    val video = if (lyricsMode) visuals.second ?: visuals.first else visuals.first
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val artworkGestures = Modifier.pointerInput(track.id) {
        var dragX = 0f; var dragY = 0f
        detectDragGestures(onDragStart = { dragX = 0f; dragY = 0f }, onDragEnd = {
            if (kotlin.math.abs(dragX) > swipeThreshold && kotlin.math.abs(dragX) > kotlin.math.abs(dragY)) {
                if (dragX > 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
            } else if (kotlin.math.abs(dragY) > swipeThreshold && kotlin.math.abs(dragY) > kotlin.math.abs(dragX)) {
                if (dragY < 0) lyricsMode = true else onBack()
            }
        }) { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
    }
    LaunchedEffect(syncDialog, track.id, video) {
        if (syncDialog && video != null) {
            loadingWaveforms = true
            val data = withContext(Dispatchers.IO) {
                WaveformAnalyzer.read(ctx, track.uri, fallbackPath = track.path) to WaveformAnalyzer.read(ctx, video)
            }
            audioWaveform = data.first; videoWaveform = data.second
            loadingWaveforms = false
        }
    }
    Box(Modifier.fillMaxSize()) {
    BlurredAlbumBackground(track, Modifier.matchParentSize())
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("NOW PLAYING", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current)
            IconButton(onClick = onEditTrack) { Icon(Icons.Default.Edit, "Edit song tags") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit song tags") }, onClick = { menu = false; onEditTrack() })
                    DropdownMenuItem(text = { Text("Sync video to song") }, onClick = { menu = false; syncDialog = true })
                    DropdownMenuItem(text = { Text("Check for matching video") }, onClick = { menu = false; scope.launch {
                        val local = withContext(Dispatchers.IO) { Store.find(ctx, track, false) }
                        if (local != null) Toast.makeText(ctx, "Video found and shown for ${track.title}", Toast.LENGTH_LONG).show()
                        else if (!folderTree.isNullOrBlank()) {
                            val found = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(ctx, Uri.parse(folderTree))?.let { root -> MetadataMatcher.match(MetadataMatcher.findVideosRecursively(root), track) }.orEmpty() }
                            if (found.isNotEmpty()) matches = found else Toast.makeText(ctx, "No matching video found. Pick a video file or folder.", Toast.LENGTH_LONG).show()
                        } else Toast.makeText(ctx, "No matching video found. Pick a video file or folder.", Toast.LENGTH_LONG).show()
                        onChanged()
                    } })
                    DropdownMenuItem(text = { Text("Choose video file") }, onClick = { menu = false; pendingLyrics = false; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Find video in folder") }, onClick = { menu = false; pendingLyrics = false; pickFolder.launch(null) })
                    DropdownMenuItem(text = { Text("Choose lyrics video") }, onClick = { menu = false; pendingLyrics = true; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Download video with yt-dlp") }, onClick = { menu = false; downloadUrl = ""; variants = emptyList(); selectedVariant = null; downloadDialog = true })
                    DropdownMenuItem(text = { Text("Remove companion videos") }, onClick = { menu = false; Store.appFile(ctx, track, false).delete(); Store.appFile(ctx, track, true).delete(); onChanged() })
                }
            }
        }
        Spacer(Modifier.weight(.4f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Panel).then(artworkGestures)) {
            if (lyricsMode && lyrics.isNotEmpty()) {
                LazyColumn(state = lyricListState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(lyrics, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                        val activeColor = if (index == activeLyricIndex) LocalAccent.current else Color.LightGray.copy(alpha = .72f)
                        Text(line.text, modifier = Modifier.fillMaxWidth().clickable {
                            player.seekTo(line.timeMs); pos = line.timeMs; lyricsInteraction = true
                            scope.launch { delay(5_000); lyricsInteraction = false }
                        }.padding(vertical = 11.dp, horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = if (index == activeLyricIndex) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                            color = activeColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (lyricsMode && lyrics.isEmpty()) Text("No synced lyrics found. Put ${track.title}.lrc beside the song.", color = Color.LightGray, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else if (video != null) ArtBox(video, track.artUri, player, videoOffset, Modifier.fillMaxSize()) else AlbumArt(track, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist + if (track.album.isBlank()) "" else " · ${track.album}", color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FilterChip(lyricsMode, { lyricsMode = !lyricsMode }, label = { Text(if (lyrics.isEmpty() && visuals.second == null) "Lyrics" else "Lyrics") })
        }
        Spacer(Modifier.height(16.dp))
        val duration = player.duration.takeIf { it > 0 } ?: track.durationMs.coerceAtLeast(1L)
        Slider(value = pos.toFloat().coerceIn(0f, duration.toFloat()), onValueChange = { pos = it.toLong(); player.seekTo(it.toLong()) }, valueRange = 0f..duration.toFloat())
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(pos), color = Color.Gray); Text(formatTime(duration), color = Color.Gray) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(38.dp)) }
            FilledIconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.size(72.dp), shape = CircleShape) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp)) }
            IconButton(onClick = { player.seekToNextMediaItem() }, modifier = Modifier.size(58.dp)) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(38.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) LocalAccent.current else Color.Gray) }
            IconButton(onClick = { syncDialog = true }, enabled = video != null) { Icon(Icons.Default.Sync, "Sync companion video", tint = if (video != null) LocalAccent.current else Color.Gray) }
        }
        Spacer(Modifier.weight(.4f))
    }
    }
    matches?.takeIf { it.isNotEmpty() }?.let { list -> AlertDialog(onDismissRequest = { matches = null }, title = { Text("Matching videos") }, text = { Column { list.forEach { file -> TextButton(onClick = { matches = null; onTrim(file.uri, pendingLyrics) }) { Text(file.name ?: "Choose video") } } } }, confirmButton = { TextButton(onClick = { matches = null }) { Text("Cancel") } }) }
    if (syncDialog) AlertDialog(onDismissRequest = { syncDialog = false }, title = { Text("Sync companion video") },
        text = { Column {
            Text("Drag the video waveform to line up a beat. If waveforms cannot be decoded, play the track and use the manual offset controls below.", color = Color.LightGray)
            if (loadingWaveforms) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (audioWaveform != null || videoWaveform != null) SyncWaveforms(audioWaveform, videoWaveform, track.durationMs, videoOffset) { videoOffset = it }
            if (!loadingWaveforms && (audioWaveform == null || videoWaveform == null)) Text(
                listOfNotNull(if (audioWaveform == null) "Could not decode the song waveform." else null, if (videoWaveform == null) "This video has no readable audio waveform." else null).joinToString(" ") + " You can still adjust sync below.",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall
            )
            Slider(value = videoOffset.toFloat().coerceIn(-120_000f, 120_000f), onValueChange = { videoOffset = it.toLong() }, valueRange = -120_000f..120_000f)
            Text("${if (videoOffset >= 0) "+" else "−"}${formatTime(kotlin.math.abs(videoOffset))}", color = LocalAccent.current)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { videoOffset = (videoOffset - 1_000L).coerceIn(-120_000L, 120_000L) }) { Text("−1 s") }
                TextButton(onClick = { videoOffset = (videoOffset - 100L).coerceIn(-120_000L, 120_000L) }) { Text("−100 ms") }
                TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(if (player.isPlaying) "Pause" else "Play") }
                TextButton(onClick = { videoOffset = (videoOffset + 100L).coerceIn(-120_000L, 120_000L) }) { Text("+100 ms") }
                TextButton(onClick = { videoOffset = (videoOffset + 1_000L).coerceIn(-120_000L, 120_000L) }) { Text("+1 s") }
            }
        } }, confirmButton = { TextButton(onClick = { syncPrefs.edit().putLong("offset_${track.id}", videoOffset).apply(); syncDialog = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { videoOffset = 0L; syncPrefs.edit().remove("offset_${track.id}").apply(); syncDialog = false }) { Text("Reset") } })
    if (downloadDialog) AlertDialog(
        onDismissRequest = { if (!downloading) downloadDialog = false },
        title = { Text("Download a companion video") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ytDlpReady) {
                true -> Text("yt-dlp and FFmpeg are ready.", color = LocalAccent.current, style = MaterialTheme.typography.bodySmall)
                false -> Text("yt-dlp failed to initialize: ${ytDlpInitError ?: "unknown initialization error"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                null -> Text("Preparing yt-dlp and FFmpeg…", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(downloadUrl, { downloadUrl = it }, label = { Text("Video URL") }, placeholder = { Text("https://…") }, singleLine = true, enabled = !downloading)
            Text("Choose one of the formats below before downloading. Nothing downloads until you select a format and tap Download.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            TextButton(enabled = !updatingYtDlp && !downloading, onClick = {
                updatingYtDlp = true; ytDlpError = null
                scope.launch {
                    val initialized = withContext(Dispatchers.IO) { runCatching {
                        YoutubeDL.getInstance().init(ctx.applicationContext)
                        FFmpeg.getInstance().init(ctx.applicationContext)
                    } }
                    onYtDlpInit(initialized.isSuccess, initialized.exceptionOrNull()?.message)
                    if (initialized.isFailure) {
                        updatingYtDlp = false
                        ytDlpError = initialized.exceptionOrNull()?.message?.take(500) ?: "Initialization failed"
                        return@launch
                    }
                    val updated = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE) } }
                    updatingYtDlp = false
                    onYtDlpInit(true, null)
                    if (updated.isFailure) ytDlpError = updated.exceptionOrNull()?.message?.take(500) ?: "Unknown yt-dlp update error"
                    Toast.makeText(ctx, if (updated.isSuccess) "yt-dlp is ready: ${updated.getOrNull()}" else "Could not update yt-dlp: ${updated.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }) { Text(if (updatingYtDlp) "Updating yt-dlp…" else "Update yt-dlp") }
            ytDlpError?.let { Text("yt-dlp: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = removeSponsorSegments, onCheckedChange = { removeSponsorSegments = it }, enabled = !downloading)
                Text("Remove SponsorBlock sponsor, intro, and outro sections (YouTube only)", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            }
            if (lookingUp) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            variants.forEach { variant ->
                Row(Modifier.fillMaxWidth().clickable { selectedVariant = variant.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedVariant == variant.id, onClick = { selectedVariant = variant.id })
                    Text(variant.label + if (variant.hasAudio) " · video + audio" else " · video only; audio will be muxed", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (downloading) LinearProgressIndicator(progress = { (downloadProgress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Row {
            TextButton(enabled = !downloading && !lookingUp && downloadUrl.startsWith("http") && variants.isEmpty(), onClick = {
                lookingUp = true
                scope.launch {
                    val initialized = withContext(Dispatchers.IO) { runCatching {
                        YoutubeDL.getInstance().init(ctx.applicationContext)
                        FFmpeg.getInstance().init(ctx.applicationContext)
                    } }
                    onYtDlpInit(initialized.isSuccess, initialized.exceptionOrNull()?.message)
                    val result: Result<List<DownloadVariant>> = if (initialized.isFailure) Result.failure(initialized.exceptionOrNull()!!)
                    else withContext(Dispatchers.IO) {
                        runCatching {
                            val info = runCatching { YoutubeDL.getInstance().getInfo(downloadUrl.trim()) }.recoverCatching {
                                YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                                YoutubeDL.getInstance().getInfo(downloadUrl.trim())
                            }.getOrThrow()
                            info.formats.orEmpty()
                                .filter { !it.formatId.isNullOrBlank() && it.ext == "mp4" && !it.vcodec.isNullOrBlank() && it.vcodec != "none" }
                                .distinctBy { it.formatId }
                                .sortedByDescending { it.height }
                                .take(24)
                                .map { format -> DownloadVariant(format.formatId!!, formatLabel(format), !format.acodec.isNullOrBlank() && format.acodec != "none") }
                        }
                    }
                    lookingUp = false
                    if (result.isSuccess && result.getOrNull().orEmpty().isNotEmpty()) {
                        variants = result.getOrDefault(emptyList()); selectedVariant = variants.first().id
                        ytDlpError = null
                    } else {
                        ytDlpError = result.exceptionOrNull()?.message?.take(500) ?: "No MP4 video formats found for this URL"
                        Toast.makeText(ctx, ytDlpError, Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(if (lookingUp) "Checking…" else "Show formats") }
            TextButton(enabled = !downloading && !lookingUp && selectedVariant != null, onClick = {
            downloading = true; downloadProgress = 0f
            scope.launch {
                val finalFile = Store.appFile(ctx, track, false).apply { parentFile?.mkdirs() }
                val tempFile = File(finalFile.parentFile, "${track.id}.download.mp4")
                tempFile.delete()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        YoutubeDL.getInstance().init(ctx.applicationContext)
                        FFmpeg.getInstance().init(ctx.applicationContext)
                        val chosen = variants.firstOrNull { it.id == selectedVariant } ?: error("Select a video format first")
                        val request = YoutubeDLRequest(downloadUrl.trim()).apply {
                            addOption("--no-playlist")
                            addOption("--force-overwrites")
                            addOption("-f", if (chosen.hasAudio) chosen.id else "${chosen.id}+bestaudio[ext=m4a]/${chosen.id}+bestaudio")
                            addOption("--merge-output-format", "mp4")
                            addOption("--remux-video", "mp4")
                            if (removeSponsorSegments) addOption("--sponsorblock-remove", "sponsor,intro,outro")
                            addOption("-o", tempFile.absolutePath)
                        }
                        val runDownload = {
                            tempFile.delete()
                            YoutubeDL.getInstance().execute(request, "canvas-${track.id}-${System.currentTimeMillis()}") { progress, _, _ ->
                                ctx.mainExecutor.execute { downloadProgress = progress }
                            }
                        }
                        runCatching { runDownload() }.recoverCatching {
                            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                            runDownload()
                        }.getOrThrow()
                        check(tempFile.isFile && tempFile.length() > 0L) { "yt-dlp finished without creating the expected MP4. Try another MP4 format." }
                    }
                }
                if (result.isFailure) {
                    downloading = false
                    ytDlpError = result.exceptionOrNull()?.message?.take(500) ?: "Unknown download error"
                    Toast.makeText(ctx, "Download failed: $ytDlpError", Toast.LENGTH_LONG).show()
                } else {
                    val rangeAndDuration = withContext(Dispatchers.IO) {
                        SilenceAnalyzer.audibleRange(tempFile) to runCatching {
                            MediaMetadataRetriever().run { setDataSource(tempFile.absolutePath); val value = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); value }
                        }.getOrDefault(0L)
                    }
                    val range = rangeAndDuration.first
                    val needsTrim = range != null && (range.first >= 450L || (rangeAndDuration.second > 0 && range.second <= rangeAndDuration.second - 700L))
                    if (needsTrim && range != null) {
                        downloadProgress = 99f
                        Exporter.trim(ctx, Uri.fromFile(tempFile), range.first, range.second, finalFile) { trimResult ->
                            downloading = false; tempFile.delete()
                            if (trimResult.isSuccess) { downloadDialog = false; onChanged(); Toast.makeText(ctx, "Downloaded and trimmed quiet intro/outro for ${track.title}", Toast.LENGTH_LONG).show() }
                            else Toast.makeText(ctx, "Video downloaded, but silence trim failed: ${trimResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        finalFile.delete()
                        val moved = tempFile.renameTo(finalFile)
                        downloading = false
                        if (moved) { downloadDialog = false; onChanged(); Toast.makeText(ctx, "Video saved for ${track.title}; no quiet edges detected", Toast.LENGTH_LONG).show() }
                        else Toast.makeText(ctx, "Video downloaded but could not save it for this track", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }) { Text(if (downloading) "Downloading…" else "Download selected") }
        } },
        dismissButton = { TextButton(enabled = !downloading, onClick = { downloadDialog = false }) { Text("Cancel") } }
    )
}

@Composable private fun SyncWaveforms(audio: WaveformData?, video: WaveformData?, trackDurationMs: Long, offsetMs: Long, onOffsetChange: (Long) -> Unit) {
    val accent = LocalAccent.current
    val latestOffset = rememberUpdatedState(offsetMs)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SONG", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("VIDEO  •  DRAG TO ALIGN", color = accent, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .36f))
                .pointerInput(audio?.durationMs, video?.durationMs) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onOffsetChange((latestOffset.value + drag.x * 50f).roundToLong().coerceIn(-30_000L, 30_000L))
                    }
                }
        ) {
            val count = max(audio?.bars?.size ?: 0, video?.bars?.size ?: 0).coerceAtLeast(2)
            val duration = audio?.durationMs?.takeIf { it > 0 } ?: trackDurationMs.coerceAtLeast(1L)
            val audioCenter = size.height * .27f
            val videoCenter = size.height * .73f
            val maxAmp = size.height * .22f
            val step = size.width / count
            for (i in 0 until count) {
                val x = (i + .5f) * step
                val time = (i.toLong() * duration) / count
                audio?.bars?.getOrNull(i * (audio.bars.size) / count)?.let { amplitude ->
                    drawLine(Color.White.copy(alpha = .88f), Offset(x, audioCenter - amplitude * maxAmp), Offset(x, audioCenter + amplitude * maxAmp), step * .62f, StrokeCap.Round)
                }
                video?.let { wave ->
                    val videoTime = Math.floorMod(time - latestOffset.value, wave.durationMs.coerceAtLeast(1L))
                    val index = ((videoTime * wave.bars.size) / wave.durationMs.coerceAtLeast(1L)).toInt().coerceIn(0, wave.bars.lastIndex.coerceAtLeast(0))
                    wave.bars.getOrNull(index)?.let { amplitude ->
                        drawLine(accent.copy(alpha = .95f), Offset(x, videoCenter - amplitude * maxAmp), Offset(x, videoCenter + amplitude * maxAmp), step * .62f, StrokeCap.Round)
                    }
                }
            }
            drawLine(Color.White.copy(alpha = .2f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrimScreen(src: Uri, onCancel: () -> Unit, onSave: (Long, Long, () -> Unit) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val dur = remember(src) { val r = MediaMetadataRetriever(); try { r.setDataSource(ctx, src); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L } finally { r.release() } }
    var range by remember { mutableStateOf(0f..dur.toFloat()) }; var busy by remember { mutableStateOf(false) }
    if (dur <= 0) { Column(Modifier.padding(24.dp)) { Text("Could not read this video"); TextButton(onClick = onCancel) { Text("Back") } }; return }
    val preview = remember(src) { ExoPlayer.Builder(ctx).build().apply { volume = 0f; setMediaItem(MediaItem.fromUri(src)); prepare() } }
    DisposableEffect(preview) { onDispose { preview.release() } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Trim companion video", style = MaterialTheme.typography.titleLarge) }
        AndroidView(factory = { PlayerView(it).apply { player = preview; useController = false } }, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)))
        RangeSlider(range, { range = it; preview.seekTo(it.start.toLong()) }, valueRange = 0f..dur.toFloat())
        Text("${formatTime(range.start.toLong())} – ${formatTime(range.endInclusive.toLong())}")
        Spacer(Modifier.height(18.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            OutlinedButton(onClick = { scope.launch { preview.seekTo(range.start.toLong()); preview.play(); while (preview.isPlaying && preview.currentPosition < range.endInclusive) delay(200); preview.pause() } }, enabled = !busy) { Text("Preview") }
            Button(onClick = { busy = true; onSave(range.start.toLong(), range.endInclusive.toLong()) { busy = false } }, enabled = !busy) { Text(if (busy) "Saving…" else "Trim & save") }
        }
    }
}

private fun formatTime(ms: Long): String = "%d:%02d".format((ms.coerceAtLeast(0) / 60000), (ms.coerceAtLeast(0) / 1000) % 60)

/** Picks a vivid cover-art color for player controls without decoding full-size cover art on the UI thread. */
private fun accentFromArtwork(context: android.content.Context, track: Track): Color {
    val bytes = runCatching { MediaMetadataRetriever().run {
        setDataSource(context, track.uri)
        val picture = embeddedPicture
        release()
        picture
    } }.getOrNull() ?: return Mint
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 16 }) ?: return Mint
    var red = 0L; var green = 0L; var blue = 0L; var count = 0
    for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
        val pixel = bitmap.getPixel(x, y)
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[1] > .28f && hsv[2] > .28f) {
            red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++
        }
    }
    bitmap.recycle()
    if (count == 0) return Mint
    val r = (red / count).toInt(); val g = (green / count).toInt(); val b = (blue / count).toInt()
    return Color(android.graphics.Color.rgb((r * .72 + 70).toInt().coerceAtMost(255), (g * .72 + 70).toInt().coerceAtMost(255), (b * .72 + 70).toInt().coerceAtMost(255)))
}

private fun formatLabel(format: VideoFormat): String = buildString {
    append(if (format.height > 0) "${format.height}p" else format.formatNote ?: "Video")
    append(" · "); append(format.ext ?: "unknown")
    if (format.fps > 0) append(" · ${format.fps} fps")
    if (format.fileSizeApproximate > 0) append(" · ~${format.fileSizeApproximate / (1024 * 1024)} MB")
    append(" · ${format.formatId}")
}

/** Rewrites the selected MP3's ID3 tags and optional embedded front-cover artwork. */
private fun writeTrackTags(context: android.content.Context, edit: TrackEdit, allTracks: List<Track>) {
    val targets = artworkTargets(edit, allTracks)
    val imageBytes = edit.coverBytes ?: edit.coverUri?.let { uri -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    val embeddedImage = imageBytes?.let { bytes ->
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not read the selected cover image")
        val encoded = try {
            ByteArrayOutputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) error("Could not encode cover image")
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
        encoded
    }
    val changedPaths = mutableListOf<String>()
    targets.forEach { target ->
            val source = File(context.cacheDir, "tag-source-${target.id}.mp3")
            val output = File(context.cacheDir, "tag-output-${target.id}.mp3")
            try {
                context.contentResolver.openInputStream(target.uri)?.use { input -> source.outputStream().use { input.copyTo(it) } }
                    ?: error("Could not open ${target.title}")
                val audio = AudioFileIO.read(source)
                val tag = audio.tagOrCreateAndSetDefault
                if (target.id == edit.track.id) {
                    fun set(key: FieldKey, value: String) {
                        if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
                    }
                    set(FieldKey.TITLE, edit.title); set(FieldKey.TRACK, edit.trackNumber); set(FieldKey.YEAR, edit.year)
                    set(FieldKey.GENRE, edit.genre)
                    tag.deleteField(FieldKey.ARTIST)
                    splitArtists(edit.artist).forEach { tag.addField(FieldKey.ARTIST, it) }
                    set(FieldKey.ALBUM, edit.album)
                    set(FieldKey.ALBUM_ARTIST, edit.albumArtist); set(FieldKey.COMPOSER, edit.composer); set(FieldKey.COMMENT, edit.comment)
                }
                if (edit.clearArtwork) tag.deleteArtworkField()
                embeddedImage?.let { bytes ->
                    val artwork = ArtworkFactory.getNew()
                    artwork.setBinaryData(bytes); artwork.setMimeType("image/jpeg"); artwork.setPictureType(3)
                    tag.deleteArtworkField(); tag.setField(artwork)
                }
                AudioFileIO.write(audio)
                source.copyTo(output, overwrite = true)
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream -> output.inputStream().use { it.copyTo(stream) } }
                    ?: error("Could not write changes to ${target.title}")
                changedPaths += target.path
            } finally {
                source.delete(); output.delete()
            }
    }
    MediaScannerConnection.scanFile(context, changedPaths.toTypedArray(), Array(changedPaths.size) { "audio/mpeg" }, null)
}

/** Splits multi-artist text while protecting common names that contain a slash. */
private fun splitArtists(value: String): List<String> {
    val protected = listOf("AC/DC", "+/-", "No/Me", "25/17")
    var text = value.trim()
    protected.forEachIndexed { index, name ->
        text = text.replace(name, "\u0000ARTIST$index\u0000", ignoreCase = true)
    }
    val artists = text.split(Regex("(?i)\\s*(?:/|,|\\bfeat(?:uring)?\\.?|\\bft\\.?)\\s*"))
        .map(String::trim).filter(String::isNotEmpty)
    return artists.map { artist ->
        protected.indices.fold(artist) { current, index ->
            current.replace("\u0000ARTIST$index\u0000", protected[index], ignoreCase = true)
        }
    }.distinctBy { it.lowercase() }
}
