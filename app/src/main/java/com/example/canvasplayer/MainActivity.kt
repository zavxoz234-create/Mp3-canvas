package com.example.canvasplayer

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
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
    private var reloadJob: Job? = null
    private fun reloadTracks(root: String? = null) {
        if (reloadJob?.isActive == true) return
        reloadJob = reloadScope.launch {
            val cached = withContext(Dispatchers.IO) { MediaStoreTrackCache.load(this@MainActivity) }
            if (cached != null) { tracks = cached; return@launch }
            val lastKnown = withContext(Dispatchers.IO) { MediaStoreTrackCache.loadLastKnown(this@MainActivity) }
            if (!lastKnown.isNullOrEmpty()) tracks = lastKnown
            // Android may invalidate its MediaStore version before finishing a rescan. Retry briefly,
            // and never replace a useful saved library with that temporary empty result.
            var loaded = emptyList<Track>()
            for (attempt in 0..2) {
                loaded = withContext(Dispatchers.IO) { loadTracks(this@MainActivity) }
                if (loaded.isNotEmpty() || attempt == 2) break
                delay(700L * (attempt + 1))
            }
            if (loaded.isNotEmpty() || lastKnown.isNullOrEmpty()) {
                tracks = loaded
                if (loaded.isNotEmpty()) withContext(Dispatchers.IO) { MediaStoreTrackCache.save(this@MainActivity, loaded) }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash reporter: remember the last fatal error so it can be shown (and screenshotted) on next launch.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences("crashlog", MODE_PRIVATE).edit()
                    .putString("trace", "${'$'}{error.javaClass.simpleName}: ${'$'}{error.message}\n" + error.stackTraceToString())
                    .commit()
            }
            previousHandler?.uncaughtException(thread, error)
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        val requestedPermissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        perms.launch(requestedPermissions)
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, audioPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) reloadTracks()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel)) { App(tracks, controller, onFolderChange = { reloadTracks() }, onLibraryEdited = { edit ->
            tracks = tracks.map { t -> if (t.id == edit.track.id) t.copy(title = edit.title.ifBlank { t.title }, artist = edit.artist.ifBlank { t.artist }, album = edit.album, albumArtist = edit.albumArtist) else t }
            MediaStoreTrackCache.clear(this)
        }) } }
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

@Composable private fun App(tracks: List<Track>, player: Player?, onFolderChange: (String?) -> Unit, onLibraryEdited: (TrackEdit) -> Unit) {
    // Show the last crash, once, so it can be reported.
    val crashPrefs = LocalContext.current.getSharedPreferences("crashlog", android.content.Context.MODE_PRIVATE)
    var crashTrace by remember { mutableStateOf(crashPrefs.getString("trace", null)?.also { crashPrefs.edit().remove("trace").apply() }) }
    crashTrace?.let { trace ->
        AlertDialog(
            onDismissRequest = { crashTrace = null },
            title = { Text("Crash report") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(trace, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { crashTrace = null }) { Text("Close") } }
        )
    }
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
    // Hoisted so the selected tab + artist/album survive navigation to the player and back.
    val librarySection = remember { mutableStateOf("Songs") }
    val libraryGroup = remember { mutableStateOf<String?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var trim by remember { mutableStateOf<TrimJob?>(null) }
    var pendingEdit by remember { mutableStateOf<TrackEdit?>(null) }
    var editDialogTrack by remember { mutableStateOf<Track?>(null) }
    var showChangelog by remember { mutableStateOf(prefs.getString("seenChangelogVersion", "") != "0.8.0") }
    var ytDlpReady by remember { mutableStateOf<Boolean?>(null) }
    var ytDlpInitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(exportAppSettingsBackup(ctx)) }
                    ?: error("Could not open the backup destination")
            } }
            Toast.makeText(ctx, if (result.isSuccess) "Library settings exported" else "Could not export settings: " + result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
        }
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected backup")
                importAppSettingsBackup(ctx, content)
            } }
            if (result.isSuccess) {
                folder = prefs.getString("folder", null)
                folderRoots = prefs.getStringSet("folders_v2", null)?.toList() ?: listOfNotNull(folder)
                folderUris = prefs.getStringSet("folder_uris_v2", null)?.mapNotNull { entry -> entry.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
                folderLabel = prefs.getString("folderLabel", null)
                folderTree = prefs.getString("folderUri", null)
                onFolderChange(folder)
                refresh++
                Toast.makeText(ctx, "Library settings restored. Re-select folders if Android requests access.", Toast.LENGTH_LONG).show()
            } else Toast.makeText(ctx, "Could not import settings: " + result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
        }
    }

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
                onLibraryEdited(edit)
                refresh++
                ArtCache.invalidate(); Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show()
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
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
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
    LaunchedEffect(track?.id, refresh, ArtCache.version) {
        accent = if (track == null) Mint else withContext(Dispatchers.Default) { ArtCache.accent(ArtCache.load(ctx, track, 128)) } ?: Mint
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
                onShuffle = { if (player != null && visibleTracks.isNotEmpty()) { val shuffled = visibleTracks.shuffled(); player.setMediaItems(shuffled.map { it.toMediaItem() }); player.shuffleModeEnabled = true; player.prepare(); player.play(); shuffle = true; inPlayer = true } },               onPlay = { selected -> if (player != null) { val keepShuffle = shuffle || player.shuffleModeEnabled; player.setMediaItems(visibleTracks.map { it.toMediaItem() }, visibleTracks.indexOf(selected), 0); player.shuffleModeEnabled = keepShuffle; player.prepare(); player.play(); shuffle = keepShuffle; inPlayer = true } },
                onMiniOpen = { val wasPlaying = player?.isPlaying == true; inPlayer = true; if (wasPlaying) player?.play() },
                onMiniPlay = { if (player != null) { if (playing) player.pause() else player.play() } },
                onExportBackup = { exportSettingsLauncher.launch("CanvasPlayer-settings.json") }, onImportBackup = { importSettingsLauncher.launch(arrayOf("application/json", "text/*")) },
                onEdit = { edit ->
                    pendingEdit = edit
                    val mediaStoreTargets = artworkTargets(edit, tracks).filter { it.documentUri == null }
                    if (Build.VERSION.SDK_INT >= 30 && mediaStoreTargets.isNotEmpty()) {
                        runCatching {
                            val request = MediaStore.createWriteRequest(ctx.contentResolver, mediaStoreTargets.map { it.uri })
                            editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit, tracks) } }
                            pendingEdit = null
                            if (saved.isSuccess) { onLibraryEdited(edit); refresh++; ArtCache.invalidate(); Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
                            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }, returnToCurrentTrack = returnToCurrentTrack, songListState = librarySongListState, sectionState = librarySection, groupState = libraryGroup)
        }
    }
    editDialogTrack?.let { item -> EditTrackDialog(item, tracks.count { it.belongsToSameAlbum(item) }, onDismiss = { editDialogTrack = null }) { edit ->
        editDialogTrack = null
        pendingEdit = edit
        val mediaStoreTargets = artworkTargets(edit, tracks).filter { it.documentUri == null }
        if (Build.VERSION.SDK_INT >= 30 && mediaStoreTargets.isNotEmpty()) {
            runCatching {
                val request = MediaStore.createWriteRequest(ctx.contentResolver, mediaStoreTargets.map { it.uri })
                editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
        } else scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit, tracks) } }
            pendingEdit = null
            if (saved.isSuccess) { onLibraryEdited(edit); refresh++; ArtCache.invalidate(); Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    } }
    if (showChangelog) AlertDialog(onDismissRequest = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.8.0").apply() },
        title = { Text("Canvas Player 0.8.0") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("What’s new", style = MaterialTheme.typography.titleMedium)
            Text("• Finds the official music video for songs without one (artist/VEVO channels only, no lyric, live, cover or audio-only uploads) and plays it instead of the album art\n• Long titles and artists scroll\n• Much smoother Now Playing screen: position updates no longer rebuild the whole screen, lyrics glide, swiping the artwork follows your finger\n• Video now renders on a texture surface and plays 720p H.264 only\n• Album art is decoded once at the displayed size and cached, so the library scrolls without stutter")
        } }, confirmButton = { TextButton(onClick = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.8.0").apply() }) { Text("Got it") } })
    } }
}

@Composable private fun LibraryScreen(tracks: List<Track>, current: Track?, playing: Boolean, folder: String?, onChooseFolder: () -> Unit, onRefreshScan: () -> Unit, refreshingFolderScan: Boolean, onClearFolder: () -> Unit, onShuffle: () -> Unit, onPlay: (Track) -> Unit, onMiniOpen: () -> Unit, onMiniPlay: () -> Unit, onEdit: (TrackEdit) -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, returnToCurrentTrack: Int, songListState: LazyListState, sectionState: MutableState<String>, groupState: MutableState<String?>) {
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Track?>(null) }
    var section by sectionState
    var sortBy by remember { mutableStateOf("Default") }
    var sortMenu by remember { mutableStateOf(false) }
    var selectedGroup by groupState
    var listMenu by remember { mutableStateOf(false) }
    val shown = remember(tracks, search) { tracks.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) || it.albumArtist.contains(search, true) } }
    fun sorted(list: List<Track>) = when (sortBy) {
        "Title" -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
        "Artist" -> list.sortedWith(compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }))
        "Album" -> list.sortedWith(compareBy({ it.album.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }))
        "Duration" -> list.sortedBy { it.durationMs }
        else -> list
    }
    val artists = remember(tracks) {
        tracks.flatMap { track -> splitArtists(track.artist).ifEmpty { listOf("Unknown artist") }.map { it to track } }
            .groupBy({ it.first.trim().lowercase(Locale.ROOT) }, { it })
            .mapValues { (_, entries) -> entries.first().first to entries.map { it.second }.distinctBy { it.id } }.toSortedMap()
    }
    val albums = remember(tracks) { tracks.groupBy { it.albumGroupingKey() }.toSortedMap() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("CANVAS", style = MaterialTheme.typography.labelLarge, color = LocalAccent.current); Text("Your music", style = MaterialTheme.typography.headlineMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { searching = !searching; if (!searching) search = "" }) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search", tint = LocalAccent.current) }
                Box {
                IconButton(onClick = { listMenu = true }) { Icon(Icons.Default.MoreVert, "Library options", tint = LocalAccent.current) }
                DropdownMenu(listMenu, { listMenu = false }) {
                    DropdownMenuItem(text = { Text("Add music folder") }, onClick = { listMenu = false; onChooseFolder() }); if (folder != null) DropdownMenuItem(enabled = !refreshingFolderScan, text = { Text(if (refreshingFolderScan) "Refreshing scan…" else "Refresh selected folders") }, onClick = { listMenu = false; onRefreshScan() })
                    if (folder != null) DropdownMenuItem(text = { Text("Clear scanned folders") }, onClick = { listMenu = false; onClearFolder() })
                    DropdownMenuItem(text = { Text("Export library backup") }, onClick = { listMenu = false; onExportBackup() })
                    DropdownMenuItem(text = { Text("Import library backup") }, onClick = { listMenu = false; onImportBackup() })
                }
                }
            }
        }
        AnimatedVisibility(visible = searching, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search songs, artists, albums") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        }
        TabRow(selectedTabIndex = listOf("Songs", "Artists", "Albums").indexOf(section), containerColor = Ink) {
            listOf("Songs", "Artists", "Albums").forEachIndexed { index, tab -> Tab(selected = section == tab, onClick = { section = tab; selectedGroup = null }, text = { Text(tab) }) }
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
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("Sort: $sortBy", color = LocalAccent.current) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("Default", "Title", "Artist", "Album", "Duration").forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { sortBy = s; sortMenu = false }) }
                }
            }
            IconButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.Shuffle, "Shuffle songs", tint = LocalAccent.current) }
        }
        val groupTracks = when {
            section == "Artists" && selectedGroup != null -> artists[selectedGroup]?.second.orEmpty()
            section == "Albums" && selectedGroup != null -> albums[selectedGroup].orEmpty()
            else -> emptyList()
        }
        val songRows = sorted(if (selectedGroup != null) groupTracks.filter { it.title.contains(search, true) } else shown)
     LaunchedEffect(returnToCurrentTrack) {
    if (returnToCurrentTrack > 0 && current != null) {
        val rowIndex = songRows.indexOfFirst { it.id == current.id }
        if (rowIndex >= 0) {
            songListState.animateScrollToItem(rowIndex)
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
                            if (t.id == current?.id) MarqueeText(t.title, style = MaterialTheme.typography.titleMedium, color = LocalAccent.current)
                            else Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = Color.White)
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
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { MarqueeText(t.title); MarqueeText(t.artist, style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                IconButton(onClick = onMiniPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = LocalAccent.current) }
            }
        }
    }
    edit?.let { item -> EditTrackDialog(item, tracks.count { it.belongsToSameAlbum(item) }, onDismiss = { edit = null }) { onEdit(it); edit = null } }
}

private val waveformCache = android.util.LruCache<String, WaveformData>(8)

private val ArtPlaceholder = Brush.linearGradient(listOf(Color(0xFF26473A), Color(0xFF111A16)))

@Composable private fun AlbumArt(track: Track, modifier: Modifier = Modifier, decodePx: Int = 192) {
    val art by rememberTrackArt(track, decodePx)
    Box(modifier.background(ArtPlaceholder), contentAlignment = Alignment.Center) {
        val bitmap = art
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = .62f), modifier = Modifier.size(26.dp))
    }
}

@Composable private fun BlurredAlbumBackground(track: Track, modifier: Modifier = Modifier) {
    // Avoid full-screen artwork blur; it causes costly GPU work during every UI update.
    Box(modifier.background(Brush.verticalGradient(listOf(LocalAccent.current.copy(alpha = .12f), Ink, Ink))))
}

/** Single-line text that scrolls horizontally only when it does not fit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MarqueeText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    Text(text, modifier = modifier.basicMarquee(initialDelayMillis = 1500, velocity = 40.dp), style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
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
        if (query.isBlank()) return
        searching = true; error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                fetchInAppCovers(
                    query = query,
                    byAlbum = albumMode,
                    highResolution = highResolution,
                    expectedAlbum = track.album,
                    expectedAlbumArtist = track.albumArtist,
                    expectedSong = track.title,
                    expectedSongArtist = track.artist
                )
            }
            searching = false
            results = res
            if (res.isEmpty()) error = "No covers found in-app. Try a different query."
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find album art") },
        text = { Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(1.dp))
                Text("Images query: song or album + artist + Deezer", modifier = Modifier.weight(1f))
                Icon(Icons.Default.Settings, "Deezer artwork search")
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(if (albumMode) "Album and artist" else "Song and artist") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!albumMode, { albumMode = false; query = "${track.title} ${track.artist}" }, label = { Text("Song") })
                FilterChip(albumMode, { albumMode = true; query = "${track.album} ${track.albumArtist.ifBlank { track.artist }}" }, label = { Text("Album") })
                Button(onClick = ::search, enabled = !searching && query.isNotBlank()) { Text(if (searching) "Searching…" else "Search Google") }
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

private suspend fun fetchInAppCovers(
    query: String,
    byAlbum: Boolean,
    highResolution: Boolean,
    expectedAlbum: String,
    expectedAlbumArtist: String,
    expectedSong: String,
    expectedSongArtist: String
): List<CoverResult> = coroutineScope {
    val results = mutableListOf<CoverResult>()
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return@coroutineScope emptyList()

    // 1. iTunes Search API
    runCatching {
        val entity = if (byAlbum) "album" else "song"
        val url = URL("https://itunes.apple.com/search?term=${Uri.encode(cleanQuery)}&media=music&entity=$entity&limit=10")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val data = JSONObject(body).optJSONArray("results") ?: JSONArray()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val artworkUrl = item.optString("artworkUrl100").replace("100x100bb", "600x600bb")
            if (artworkUrl.isNotBlank()) {
                val title = if (byAlbum) item.optString("collectionName") else item.optString("trackName")
                val artist = item.optString("artistName")
                val imgConn = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 4000
                }
                val bytes = imgConn.inputStream.use { it.readBytes() }
                imgConn.disconnect()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0) {
                    results.add(CoverResult(title, artist, bytes, bounds.outWidth, bounds.outHeight))
                }
            }
        }
    }

    // 2. Deezer API
    if (results.size < 4) {
        runCatching {
            val endpoint = if (byAlbum) "album" else "track"
            val url = URL("https://api.deezer.com/search/$endpoint?q=${Uri.encode(cleanQuery)}&limit=10")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val albumObj = if (byAlbum) item else item.optJSONObject("album") ?: continue
                val imgUrl = albumObj.optString("cover_xl").ifBlank { albumObj.optString("cover_big") }
                if (imgUrl.isNotBlank()) {
                    val title = if (byAlbum) item.optString("title") else item.optString("title")
                    val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
                    val imgConn = (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000; readTimeout = 4000
                    }
                    val bytes = imgConn.inputStream.use { it.readBytes() }
                    imgConn.disconnect()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0) {
                        results.add(CoverResult(title, artist, bytes, bounds.outWidth, bounds.outHeight))
                    }
                }
            }
        }
    }
    return@coroutineScope results.distinctBy { "${it.title}-${it.subtitle}" }
}

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
    val api = URL("https://api.deezer.com/search/$endpoint?q=${Uri.encode(query)}&limit=25")
    val connection = (api.openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 7_000
        setRequestProperty("User-Agent", "CanvasPlayer/0.8.0")
    }
    val responseCode = connection.responseCode
    val json = try {
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(responseCode in 200..299) {
            val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            "Deezer search failed (HTTP $responseCode)${detail?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
        }
        body
    } finally { connection.disconnect() }
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
        val titleScore = when {
            title == expectedTitle -> 4
            title.contains(expectedTitle) || expectedTitle.contains(title) -> 2
            else -> 0
        }
        val artistNorm = normalizeCoverMatch(artist)
        val artistScore = when {
            artistNorm == expectedArtist -> 4
            artistNorm.contains(expectedArtist) || expectedArtist.contains(artistNorm) -> 2
            else -> 0
        }
        val imageUrl = sequenceOf(
            album.optString(if (highResolution) "cover_xl" else "cover_big"),
            album.optString("cover_big"), album.optString("cover_medium"), album.optString("cover")
        ).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?.replaceFirst("http://", "https://") ?: return@mapNotNull null
        Triple(Candidate(album, artist, imageUrl), titleScore + artistScore, index)
    }.distinctBy { it.first.album.optString("id", it.first.album.optString("title")) }
        .sortedWith(compareByDescending<Triple<Candidate, Int, Int>> { it.second }.thenBy { it.third })
        .take(8).map { it.first }

    candidates.map { candidate -> async(Dispatchers.IO) {
        runCatching {
            val imageConnection = (URL(candidate.imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 7_000
                setRequestProperty("User-Agent", "CanvasPlayer/0.8.0")
            }
            val bytes = try {
                check(imageConnection.responseCode in 200..299) { "Deezer image request failed (${imageConnection.responseCode})" }
                imageConnection.inputStream.use { it.readBytes() }
            } finally { imageConnection.disconnect() }
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

@Composable
private fun LyricsOverlay(visible: Boolean, track: Track, lyrics: List<TimedLyric>, pos: MutableLongState, player: Player) {
    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f))) {
            key(track.id) { LyricsPane(track, lyrics, pos, player, Modifier.fillMaxSize()) }
        }
    }
}

@Composable private fun PlayerScreen(track: Track, queueTracks: List<Track>, player: Player, playing: Boolean, shuffle: Boolean, refresh: Int, ytDlpReady: Boolean?, ytDlpInitError: String?, folderTree: String?, rootFolder: String?, onBack: () -> Unit, onShuffle: () -> Unit, onYtDlpInit: (Boolean, String?) -> Unit, onTrim: (Uri, Boolean) -> Unit, onChanged: () -> Unit, onEditTrack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lyricsMode by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }; var pendingLyrics by remember { mutableStateOf(false) }; var canvasSettings by remember { mutableStateOf(false) }; val pos = remember(track.id) { mutableLongStateOf(0L) }
    val syncPrefs = remember { ctx.getSharedPreferences("video_sync", 0) }
    var videoOffset by remember(track.id) { mutableLongStateOf(syncPrefs.getLong("offset_${track.id}", 0L)) }
    var syncDialog by remember { mutableStateOf(false) }
    var audioWaveform by remember(track.id) { mutableStateOf<WaveformData?>(null) }
    var videoWaveform by remember(track.id) { mutableStateOf<WaveformData?>(null) }
    var loadingWaveforms by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf(false) }; var downloadUrl by remember { mutableStateOf("") }; var downloading by remember { mutableStateOf(false) }; var downloadProgress by remember { mutableFloatStateOf(0f) }; var updatingYtDlp by remember { mutableStateOf(false) }; var removeSponsorSegments by remember { mutableStateOf(false) }; var ytDlpError by remember { mutableStateOf<String?>(null) }
    var lookingUp by remember { mutableStateOf(false) }; var variants by remember { mutableStateOf<List<DownloadVariant>>(emptyList()) }; var selectedVariant by remember { mutableStateOf<String?>(null) }
    val lyrics by produceState<List<TimedLyric>>(emptyList(), track.id, folderTree, rootFolder, refresh) {
        value = emptyList()
        value = withContext(Dispatchers.IO) { loadLrc(ctx, track, folderTree, rootFolder) }
    }
    val scope = rememberCoroutineScope()
    // Tagged with the track id so the previous track's video is never shown for the next track.
    val visualsState by produceState<Triple<Long, Uri?, Uri?>?>(null, track.id, refresh) {
        value = withContext(Dispatchers.IO) { Triple(track.id, Store.find(ctx, track, false) ?: CanvasUrlStore.get(ctx, track.id)?.let(Uri::parse), Store.find(ctx, track, true)) }
    }
    val visuals = visualsState?.takeIf { it.first == track.id }
    // Only leaf composables (slider, time labels, lyrics) read `pos`; the screen itself does not recompose on every tick.
    LaunchedEffect(player, track.id, playing) {
        fun sample() { if (player.currentMediaItem?.mediaId == track.id.toString()) pos.longValue = player.currentPosition.coerceAtLeast(0L) }
        sample()
        while (playing) {
            delay(200)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) sample()
        }
    }

    val autoStatus = remember(track.id) { mutableStateOf<String?>(null) }
    var autoFindEnabled by remember { mutableStateOf(VideoPrefs.autoEnabled(ctx)) }
    var wifiOnly by remember { mutableStateOf(VideoPrefs.wifiOnly(ctx)) }
    // Automatic official-video lookup for songs that have no companion video yet.
    LaunchedEffect(track.id, visuals != null, visuals?.second != null, autoFindEnabled) {
        if (visuals == null || visuals.second != null || !autoFindEnabled) return@LaunchedEffect
        if (!VideoPrefs.canTry(ctx, track.id) || !VideoFinder.networkAllowed(ctx)) return@LaunchedEffect
        delay(2_000) // the user may just be skipping through the queue
        try {
            // Canvas first: cheap HTTP lookup + small file. yt-dlp official video only as fallback.
            if (CanvasPrefs.enabled(ctx) && fetchCanvasVideo(ctx, track, autoStatus) is FetchOutcome.Saved) {
                onChanged(); return@LaunchedEffect
            }
            if (fetchOfficialVideo(ctx, track, autoStatus) is FetchOutcome.Saved) onChanged()
        } finally { autoStatus.value = null }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onTrim(uri, pendingLyrics) }
    val normalVideo: Uri? = visuals?.second
    val video = if (lyricsMode) visuals?.third ?: normalVideo else normalVideo
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val settleArt: () -> Unit = { scope.launch { animate(dragOffset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ -> dragOffset = value } } }
    val artworkGestures = Modifier.pointerInput(track.id) {
        var dragX = 0f; var dragY = 0f
        detectDragGestures(onDragStart = { dragX = 0f; dragY = 0f }, onDragEnd = {
            if (kotlin.math.abs(dragX) > swipeThreshold && kotlin.math.abs(dragX) > kotlin.math.abs(dragY)) {
                if (dragX > 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
            } else if (kotlin.math.abs(dragY) > swipeThreshold && kotlin.math.abs(dragY) > kotlin.math.abs(dragX)) {
                if (dragY < 0) lyricsMode = true else onBack()
            }
            settleArt()
        }, onDragCancel = { settleArt() }) { change, amount ->
            change.consume(); dragX += amount.x; dragY += amount.y
            if (kotlin.math.abs(dragX) > kotlin.math.abs(dragY)) dragOffset = dragX
        }
    }
    LaunchedEffect(syncDialog, track.id, video) {
        if (syncDialog && video != null) {
            loadingWaveforms = true
            val data = withContext(Dispatchers.IO) {
                val audio = waveformCache.get("a${track.id}")
                    ?: WaveformAnalyzer.read(ctx, track.uri, fallbackPath = track.path)?.also { waveformCache.put("a${track.id}", it) }
                val videoWave = waveformCache.get(video.toString())
                    ?: WaveformAnalyzer.read(ctx, video)?.also { waveformCache.put(video.toString(), it) }
                audio to videoWave
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
                    DropdownMenuItem(text = { Text("Find official video now") }, onClick = { menu = false; scope.launch {
                        if (!VideoFinder.networkAllowed(ctx, ignoreWifiOnly = true)) { Toast.makeText(ctx, "No internet connection", Toast.LENGTH_LONG).show(); return@launch }
                        VideoPrefs.clear(ctx, track.id)
                        val outcome = try { fetchOfficialVideo(ctx, track, autoStatus, manual = true) } finally { autoStatus.value = null }
                        when (outcome) {
                            is FetchOutcome.Saved -> { onChanged(); Toast.makeText(ctx, "Official video added for ${track.title}", Toast.LENGTH_LONG).show() }
                            is FetchOutcome.NotFound -> Toast.makeText(ctx, "No official video found for ${track.title}", Toast.LENGTH_LONG).show()
                            is FetchOutcome.Busy -> Toast.makeText(ctx, "Another video search is still running", Toast.LENGTH_LONG).show()
                            is FetchOutcome.Failed -> Toast.makeText(ctx, "Video search failed: ${outcome.message}", Toast.LENGTH_LONG).show()
                        }
                    } })
                    DropdownMenuItem(text = { Text(if (autoFindEnabled) "Auto-find official videos: on" else "Auto-find official videos: off") }, onClick = { menu = false; autoFindEnabled = !autoFindEnabled; VideoPrefs.setAutoEnabled(ctx, autoFindEnabled) })
                    DropdownMenuItem(text = { Text(if (wifiOnly) "Auto-download on Wi-Fi only: on" else "Auto-download on Wi-Fi only: off") }, onClick = { menu = false; wifiOnly = !wifiOnly; VideoPrefs.setWifiOnly(ctx, wifiOnly) })
                    DropdownMenuItem(text = { Text("Find canvas now") }, onClick = { menu = false; scope.launch {
                        if (!VideoFinder.networkAllowed(ctx, ignoreWifiOnly = true)) { Toast.makeText(ctx, "No internet connection", Toast.LENGTH_LONG).show(); return@launch }
                        CanvasPrefs.clear(ctx, track.id)
                        val outcome = try { fetchCanvasVideo(ctx, track, autoStatus) } finally { autoStatus.value = null }
                        when (outcome) {
                            is FetchOutcome.Saved -> { onChanged(); Toast.makeText(ctx, "Canvas added for ${track.title}", Toast.LENGTH_LONG).show() }
                            is FetchOutcome.NotFound -> Toast.makeText(ctx, "No canvas found for ${track.title}", Toast.LENGTH_LONG).show()
                            is FetchOutcome.Busy -> Toast.makeText(ctx, "Another download is still running", Toast.LENGTH_LONG).show()
                            is FetchOutcome.Failed -> Toast.makeText(ctx, "Canvas failed: ${outcome.message}", Toast.LENGTH_LONG).show()
                        }
                    } })
                    DropdownMenuItem(text = { Text("Canvas source…") }, onClick = { menu = false; canvasSettings = true })
                    DropdownMenuItem(text = { Text("Choose video file") }, onClick = { menu = false; pendingLyrics = false; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Choose lyrics video") }, onClick = { menu = false; pendingLyrics = true; pickVideo.launch("video/*") })
                    DropdownMenuItem(text = { Text("Download video with yt-dlp") }, onClick = { menu = false; downloadUrl = ""; variants = emptyList(); selectedVariant = null; downloadDialog = true })
                    DropdownMenuItem(text = { Text("Remove companion videos") }, onClick = { menu = false; Store.appFile(ctx, track, false).delete(); Store.appFile(ctx, track, true).delete(); CanvasUrlStore.remove(ctx, track.id); VideoPrefs.markRemoved(ctx, track.id); onChanged() })
                }
            }
        }
        Spacer(Modifier.weight(.4f))
        // Streamed canvases are 9:16 portrait: morph the frame to tall for them; art and downloaded videos stay square.
        val streamedCanvas = video != null && video.toString().startsWith("http")
        val artAspect by animateFloatAsState(if (streamedCanvas) 9f / 16f else 1f, animationSpec = tween(350), label = "artAspect")
        AnimatedContent(
            targetState = track.id,
            transitionSpec = {
                (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(240)) { -it / 3 } + fadeOut(tween(240)))
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(artAspect).heightIn(max = 560.dp)
                .graphicsLayer {
                    translationX = dragOffset * .55f
                    rotationZ = dragOffset / size.width * 5f
                    alpha = 1f - (kotlin.math.abs(dragOffset) / (size.width * 2f)).coerceIn(0f, .3f)
                }
                .clip(RoundedCornerShape(26.dp)).background(Panel)
        ) { targetTrackId ->
            val artTrack = queueTracks.firstOrNull { it.id == targetTrackId } ?: track
            Box(Modifier.fillMaxSize().then(if (targetTrackId == track.id) artworkGestures else Modifier)) {
                if (targetTrackId == track.id) {
                    // Canvas video if there is one (album art disappears once it is playing), otherwise the album art.
                    ArtBox(video, player, videoOffset, Modifier.fillMaxSize()) { AlbumArt(track, Modifier.fillMaxSize(), decodePx = 1024) }
                    // Lyrics float over the art/canvas, so the video player keeps running instead of being rebuilt.
                    LyricsOverlay(lyricsMode, track, lyrics, pos, player)
                } else {
                    AlbumArt(artTrack, Modifier.fillMaxSize(), decodePx = 1024)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = track.id,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.weight(1f)
            ) { targetTrackId ->
                val textTrack = queueTracks.firstOrNull { it.id == targetTrackId } ?: track
                Column(Modifier.padding(end = 12.dp)) {
                    MarqueeText(textTrack.title, style = MaterialTheme.typography.headlineSmall)
                    MarqueeText(textTrack.artist + if (textTrack.album.isBlank()) "" else " · ${textTrack.album}", color = Color.LightGray)
                }
            }
            FilterChip(lyricsMode, { lyricsMode = !lyricsMode }, label = { Text("Lyrics") })
        }
        AutoVideoStatus(autoStatus)
        PlaybackProgress(player, track, pos)
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
    if (canvasSettings) CanvasSettingsDialog { canvasSettings = false }
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
                    val chosen = variants.firstOrNull { it.id == selectedVariant }
                    if (chosen == null) { downloading = false; ytDlpError = "Select a video format first"; return@launch }
                    if (!VideoFinder.fetchLock.tryLock()) { downloading = false; Toast.makeText(ctx, "Another video download is still running", Toast.LENGTH_LONG).show(); return@launch }
                    val selector = if (chosen.hasAudio) chosen.id else "${chosen.id}+bestaudio[ext=m4a]/${chosen.id}+bestaudio"
                    val result = try {
                        downloadCompanionVideo(ctx, track, downloadUrl, selector, removeSponsorSegments) { percent -> downloadProgress = percent.toFloat() }
                    } finally { VideoFinder.fetchLock.unlock() }
                    downloading = false
                    result.onSuccess { message -> downloadDialog = false; onChanged(); Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }
                    result.onFailure { error ->
                        ytDlpError = error.message?.take(500) ?: "Unknown download error"
                        Toast.makeText(ctx, "Download failed: $ytDlpError", Toast.LENGTH_LONG).show()
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
            val source = File(context.cacheDir, "tag-source-${target.id}.${target.path.substringAfterLast('.', "mp3")}")
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
                context.contentResolver.openFileDescriptor(target.uri, "w")?.use { pfd ->
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                        output.inputStream().use { it.copyTo(stream) }
                    }
                } ?: error("Could not write changes to ${target.title}")
                changedPaths += target.path
            } finally {
                source.delete(); output.delete()
            }
    }
    MediaScannerConnection.scanFile(context, changedPaths.toTypedArray(), Array(changedPaths.size) { "audio/mpeg" }, null)
    MediaStoreTrackCache.clear(context)
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

/** Slider and time labels live in their own composable so the 5 Hz position updates only recompose these three nodes. */
@Composable private fun PlaybackProgress(player: Player, track: Track, pos: MutableLongState) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = player.duration.takeIf { it > 0 } ?: track.durationMs.coerceAtLeast(1L)
    val shown = (if (dragging) dragValue else pos.longValue.toFloat()).coerceIn(0f, duration.toFloat())
    Slider(
        value = shown,
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { player.seekTo(dragValue.toLong()); pos.longValue = dragValue.toLong(); dragging = false },
        valueRange = 0f..duration.toFloat()
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shown.toLong()), color = Color.Gray); Text(formatTime(duration), color = Color.Gray)
    }
}

/** Reserved fixed height so showing or hiding the status never shifts the controls below it. */
@Composable private fun AutoVideoStatus(status: State<String?>) {
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        val text = status.value
        if (text != null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = LocalAccent.current)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun LyricsPane(track: Track, lyrics: List<TimedLyric>, pos: MutableLongState, player: Player, modifier: Modifier = Modifier) {
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No synced lyrics found. Put ${track.title}.lrc beside the song.", color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
        return
    }
    val accent = LocalAccent.current
    val listState = rememberLazyListState()
    val activeIndex by remember(lyrics) { derivedStateOf { lyrics.indexOfLast { it.timeMs <= pos.longValue }.coerceAtLeast(0) } }
    var touchTick by remember { mutableIntStateOf(0) }
    var userScrolling by remember { mutableStateOf(false) }
    var firstScroll by remember { mutableStateOf(true) }
    // Only real finger drags count as "the user is browsing"; the follow-scroll below must not trigger it.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) touchTick++ }
    }
    LaunchedEffect(touchTick) {
        if (touchTick > 0) { userScrolling = true; delay(5_000); userScrolling = false }
    }
    LaunchedEffect(activeIndex, userScrolling) {
        if (userScrolling) return@LaunchedEffect
        if (firstScroll) { listState.scrollToItem(activeIndex); firstScroll = false }
        else listState.animateScrollToItem(activeIndex)
    }
    BoxWithConstraints(modifier) {
        val edge = (maxHeight / 2 - 30.dp).coerceAtLeast(24.dp)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = edge), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(lyrics, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                val active = index == activeIndex
                // Scale/alpha are applied in the graphics layer: no re-measure or re-layout when the active line changes.
                val scale = animateFloatAsState(if (active) 1f else .88f, tween(220), label = "lyricScale")
                val fade = animateFloatAsState(if (active) 1f else .55f, tween(220), label = "lyricAlpha")
                Text(
                    line.text,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = fade.value }
                        .clickable { player.seekTo(line.timeMs); pos.longValue = line.timeMs; touchTick++ }
                        .padding(vertical = 11.dp, horizontal = 8.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge,
                    color = if (active) accent else Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Canvas settings: enable toggle + source picker (Auto / Echo Music / Apple Music / Convx / Spotify). */
@Composable private fun CanvasSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(CanvasPrefs.enabled(ctx)) }
    var source by remember { mutableStateOf(CanvasPrefs.source(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas") },
        text = {
            Column {
                Text("Replaces the static album art in the player with a short looping video when one is available for the current song.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Use canvas", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; CanvasPrefs.setEnabled(ctx, it) })
                }
                Text("Canvas source", style = MaterialTheme.typography.labelMedium, color = LocalAccent.current)
                CanvasPrefs.sources.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { source = s; CanvasPrefs.setSource(ctx, s) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = source == s, onClick = { source = s; CanvasPrefs.setSource(ctx, s) })
                        Text(CanvasPrefs.label(s))
                    }
                }
                Text("Auto tries Apple Music first, then Echo Music, Convx, and Spotify. Streamed canvases play directly and need an internet connection; downloaded ones work offline.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
