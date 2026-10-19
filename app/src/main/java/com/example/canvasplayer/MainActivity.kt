package com.example.canvasplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.FFmpeg
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF101714)
private val Panel = Color(0xFF1B2420)
private val Mint = Color(0xFF9CE7B5)
private val LocalAccent = compositionLocalOf { Mint }

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracks = loadTracks(this, null)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        perms.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        tracks = loadTracks(this, null)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel)) { App(tracks, controller) { tracks = loadTracks(this@MainActivity, null) } } }
    }
}

private data class TrimJob(val src: Uri, val lyrics: Boolean, val track: Track)
private data class DownloadVariant(val id: String, val label: String)
private data class TrackEdit(val track: Track, val title: String, val trackNumber: String, val year: String, val genre: String, val artist: String, val album: String, val albumArtist: String, val composer: String, val comment: String, val coverUri: Uri?)

@Composable private fun App(tracks: List<Track>, player: Player?, onFolderChange: (String?) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("library", 0) }
    var folder by remember { mutableStateOf(prefs.getString("folder", null)) }
    var folderRoots by remember { mutableStateOf(prefs.getStringSet("folders_v2", null)?.toList() ?: listOfNotNull(folder)) }
    var folderUris by remember { mutableStateOf(prefs.getStringSet("folder_uris_v2", null)?.mapNotNull { entry -> entry.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()) }
    var folderLabel by remember { mutableStateOf(prefs.getString("folderLabel", null)) }
    var folderTree by remember { mutableStateOf(prefs.getString("folderUri", null)) }
    var visibleTracks by remember { mutableStateOf(tracks) }
    var inPlayer by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var trim by remember { mutableStateOf<TrimJob?>(null) }
    var pendingEdit by remember { mutableStateOf<TrackEdit?>(null) }
    var editDialogTrack by remember { mutableStateOf<Track?>(null) }
    var showChangelog by remember { mutableStateOf(prefs.getString("seenChangelogVersion", "") != "0.3.0") }
    val scope = rememberCoroutineScope()
    val editPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val edit = pendingEdit
        pendingEdit = null
        if (result.resultCode == android.app.Activity.RESULT_OK && edit != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit) } }
            if (saved.isSuccess) {
                if (folder != null) onFolderChange(folder)
                refresh++
                Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show()
            } else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(tracks, folderRoots) {
        visibleTracks = if (folderRoots.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            val roots = folderRoots.map { it.replace("\\", "/").trimEnd('/') }
            tracks.filter { track -> roots.any { root -> track.path.replace("\\", "/").startsWith("$root/", true) || track.path.replace("\\", "/").equals(root, true) } }.distinctBy { it.id }
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
    val job = trim
    val track = visibleTracks.firstOrNull { it.id.toString() == currentId } ?: tracks.firstOrNull { it.id.toString() == currentId }
    var accent by remember { mutableStateOf(Mint) }
    LaunchedEffect(track?.id, refresh) {
        accent = withContext(Dispatchers.IO) { track?.let { accentFromArtwork(ctx, it) } ?: Mint }
    }
    BackHandler(job != null || inPlayer) { if (job != null) trim = null else inPlayer = false }
    CompositionLocalProvider(LocalAccent provides accent) { Surface(Modifier.fillMaxSize(), color = Ink) {
        when {
            job != null -> TrimScreen(job.src, onCancel = { trim = null }) { start, end, done ->
                Exporter.trim(ctx, job.src, start, end, Store.appFile(ctx, job.track, job.lyrics)) { result ->
                    done(); if (result.isSuccess) { refresh++; trim = null }
                    Toast.makeText(ctx, if (result.isSuccess) "Video saved" else "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
            inPlayer && player != null && track != null -> PlayerScreen(track, player, playing, shuffle, refresh,
                folderUris.entries.firstOrNull { (root, _) -> track.path.replace("\\", "/").startsWith(root.replace("\\", "/").trimEnd('/') + "/", true) }?.value ?: folderTree,
                folderRoots.firstOrNull { root -> track.path.replace("\\", "/").startsWith(root.replace("\\", "/").trimEnd('/') + "/", true) } ?: folder,
                onBack = { inPlayer = false }, onShuffle = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle },
                onTrim = { uri, lyrics -> trim = TrimJob(uri, lyrics, track) }, onChanged = { refresh++ },
                onEditTrack = { editDialogTrack = track })
            else -> LibraryScreen(visibleTracks, track, playing, folderLabel ?: folderRoots.joinToString(" + ") { File(it).name.ifBlank { it } },
                onChooseFolder = { picker.launch(null) }, onClearFolder = { folder = null; folderRoots = emptyList(); folderLabel = null; folderTree = null; folderUris = emptyMap(); prefs.edit().remove("folder").remove("folderLabel").remove("folderUri").remove("folders_v2").remove("folder_uris_v2").apply(); onFolderChange(null) },
                onShuffle = { if (player != null && visibleTracks.isNotEmpty()) { player.setMediaItems(visibleTracks.map { it.toMediaItem() }); player.shuffleModeEnabled = true; player.prepare(); player.play(); shuffle = true; inPlayer = true } },
                onPlay = { selected -> if (player != null) { player.setMediaItems(visibleTracks.map { it.toMediaItem() }, visibleTracks.indexOf(selected), 0); player.shuffleModeEnabled = shuffle; player.prepare(); player.play(); inPlayer = true } },
                onMiniOpen = { val wasPlaying = player?.isPlaying == true; inPlayer = true; if (wasPlaying) player?.play() },
                onMiniPlay = { if (player != null) { if (playing) player.pause() else player.play() } },
                onEdit = { edit ->
                    pendingEdit = edit
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            val request = MediaStore.createWriteRequest(ctx.contentResolver, listOf(edit.track.uri))
                            editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit) } }
                            pendingEdit = null
                            if (saved.isSuccess) { if (folder != null) onFolderChange(folder); refresh++; Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
                            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
        }
    } }
    editDialogTrack?.let { item -> EditTrackDialog(item, onDismiss = { editDialogTrack = null }) { edit ->
        editDialogTrack = null
        pendingEdit = edit
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val request = MediaStore.createWriteRequest(ctx.contentResolver, listOf(edit.track.uri))
                editPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure { pendingEdit = null; Toast.makeText(ctx, "Could not request permission to edit this song: ${it.message}", Toast.LENGTH_LONG).show() }
        } else scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { writeTrackTags(ctx, edit) } }
            pendingEdit = null
            if (saved.isSuccess) { if (folder != null) onFolderChange(folder); refresh++; Toast.makeText(ctx, "Tags and album cover saved into the MP3", Toast.LENGTH_LONG).show() }
            else Toast.makeText(ctx, "Could not save tags: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    } }
    if (showChangelog) AlertDialog(onDismissRequest = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.3.0").apply() },
        title = { Text("Canvas Player 0.3.0") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("What’s new", style = MaterialTheme.typography.titleMedium)
            Text("• Edit song tags from the player\n• Multiple music folders and separate Artists / Albums views\n• Better video matching and manual video selection\n• Video sync controls and improved portrait-video cropping\n• Reliable mini-player navigation\n• Choose a download format before starting yt-dlp")
        } }, confirmButton = { TextButton(onClick = { showChangelog = false; prefs.edit().putString("seenChangelogVersion", "0.3.0").apply() }) { Text("Got it") } })
}

@Composable private fun LibraryScreen(tracks: List<Track>, current: Track?, playing: Boolean, folder: String?, onChooseFolder: () -> Unit, onClearFolder: () -> Unit, onShuffle: () -> Unit, onPlay: (Track) -> Unit, onMiniOpen: () -> Unit, onMiniPlay: () -> Unit, onEdit: (TrackEdit) -> Unit) {
    var search by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Track?>(null) }
    var section by remember { mutableStateOf("Songs") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    val shown = tracks.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) }
    val artists = tracks.flatMap { track -> splitArtists(track.artist).ifEmpty { listOf("Unknown artist") }.map { it to track } }
        .groupBy({ it.first }, { it.second }).toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val albums = tracks.groupBy { "${it.album.ifBlank { "Unknown album" }}\u0000${it.artist}" }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("CANVAS", style = MaterialTheme.typography.labelLarge, color = LocalAccent.current); Text("Your music", style = MaterialTheme.typography.headlineMedium) }
            IconButton(onClick = onChooseFolder) { Icon(Icons.Default.CreateNewFolder, "Add music folder", tint = LocalAccent.current) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(folder?.let { "Scanning selected folders: $it" } ?: "Choose one or more music folders", color = Color.LightGray, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search songs, artists, albums") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        TabRow(selectedTabIndex = listOf("Songs", "Artists", "Albums").indexOf(section), containerColor = Ink) {
            listOf("Songs", "Artists", "Albums").forEachIndexed { index, tab -> Tab(selected = section == tab, onClick = { section = tab; selectedGroup = null }, text = { Text(tab) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.Shuffle, "Shuffle songs", tint = LocalAccent.current) }
            Text("Shuffle", color = Color.LightGray, modifier = Modifier.clickable(enabled = tracks.isNotEmpty(), onClick = onShuffle))
            Spacer(Modifier.weight(1f))
            Box { IconButton(onClick = { listMenu = true }) { Icon(Icons.Default.MoreVert, "Library options") }; DropdownMenu(listMenu, { listMenu = false }) {
                DropdownMenuItem(text = { Text("Add music folder") }, onClick = { listMenu = false; onChooseFolder() })
                if (folder != null) DropdownMenuItem(text = { Text("Clear scanned folders") }, onClick = { listMenu = false; onClearFolder() })
            } }
        }
        val groupKey = selectedGroup
        val groupCount = if (groupKey == null) 0 else if (section == "Artists") artists[groupKey].orEmpty().size else albums[groupKey].orEmpty().size
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text((selectedGroup ?: section).uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalAccent.current); Text(if (selectedGroup != null) "$groupCount tracks" else when(section) { "Artists" -> "${artists.size} artists"; "Albums" -> "${albums.size} albums"; else -> "${shown.size} tracks" }, style = MaterialTheme.typography.titleLarge) }
        }
        val groupTracks = when {
            section == "Artists" && selectedGroup != null -> artists[selectedGroup].orEmpty()
            section == "Albums" && selectedGroup != null -> albums[selectedGroup].orEmpty()
            else -> emptyList()
        }
        val songRows = if (selectedGroup != null) groupTracks.filter { it.title.contains(search, true) } else shown
        when {
            tracks.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Choose a music folder to get started", color = Color.LightGray) }
            section == "Artists" && selectedGroup == null -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                itemsIndexed(artists.keys.toList(), key = { _, name -> name.lowercase() }) { _, name ->
                    val songs = artists[name].orEmpty(); val sample = songs.firstOrNull()
                    Row(Modifier.fillMaxWidth().clickable { selectedGroup = name }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sample?.let { AlbumArt(it, Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(name, style = MaterialTheme.typography.titleMedium); Text("${songs.size} songs", color = Color.LightGray) }
                        IconButton(onClick = { selectedGroup = name }) { Icon(Icons.Default.ChevronRight, "Open artist $name") }
                    }
                }
            }
            section == "Albums" && selectedGroup == null -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                itemsIndexed(albums.keys.toList(), key = { _, name -> name }) { _, key ->
                    val songs = albums[key].orEmpty(); val sample = songs.firstOrNull(); val title = key.substringBefore('\u0000')
                    Row(Modifier.fillMaxWidth().clickable { selectedGroup = key }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        sample?.let { AlbumArt(it, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(sample?.artist.orEmpty(), color = Color.LightGray) }
                        IconButton(onClick = { selectedGroup = key }) { Icon(Icons.Default.ChevronRight, "Open album $title") }
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Panel).clickable { onMiniOpen() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(t, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Color.LightGray) }
                IconButton(onClick = onMiniPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = LocalAccent.current) }
            }
        }
    }
    edit?.let { item -> EditTrackDialog(item, onDismiss = { edit = null }) { onEdit(it); edit = null } }
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

@Composable private fun EditTrackDialog(track: Track, onDismiss: () -> Unit, onSave: (TrackEdit) -> Unit) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var trackNo by remember(track.id) { mutableStateOf("") }; var year by remember(track.id) { mutableStateOf("") }
    var genre by remember(track.id) { mutableStateOf("") }; var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }; var albumArtist by remember(track.id) { mutableStateOf("") }
    var composer by remember(track.id) { mutableStateOf("") }; var comment by remember(track.id) { mutableStateOf("") }
    var cover by remember(track.id) { mutableStateOf<Uri?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { cover = it }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit tags") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(track.path.substringAfterLast('/'), color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cover != null) AsyncImage(cover, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) else AlbumArt(track, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp)); OutlinedButton(onClick = { coverPicker.launch("image/*") }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Album art") }
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
        confirmButton = { TextButton(onClick = { onSave(TrackEdit(track, title, trackNo, year, genre, artist, album, albumArtist, composer, comment, cover)) }) { Text("Save to MP3") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun PlayerScreen(track: Track, player: Player, playing: Boolean, shuffle: Boolean, refresh: Int, folderTree: String?, rootFolder: String?, onBack: () -> Unit, onShuffle: () -> Unit, onTrim: (Uri, Boolean) -> Unit, onChanged: () -> Unit, onEditTrack: () -> Unit) {
    val ctx = LocalContext.current
    var lyricsMode by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }; var pendingLyrics by remember { mutableStateOf(false) }; var matches by remember { mutableStateOf<List<DocumentFile>?>(null) }; var pos by remember { mutableLongStateOf(0L) }
    val syncPrefs = remember { ctx.getSharedPreferences("video_sync", 0) }
    var videoOffset by remember(track.id) { mutableLongStateOf(syncPrefs.getLong("offset_${track.id}", 0L)) }
    var syncDialog by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf(false) }; var downloadUrl by remember { mutableStateOf("") }; var downloading by remember { mutableStateOf(false) }; var downloadProgress by remember { mutableFloatStateOf(0f) }; var updatingYtDlp by remember { mutableStateOf(false) }; var removeSponsorSegments by remember { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }; var variants by remember { mutableStateOf<List<DownloadVariant>>(emptyList()) }; var selectedVariant by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val visuals by produceState<Pair<Uri?, Uri?>>(null to null, track.id, refresh) { value = withContext(Dispatchers.IO) { Store.find(ctx, track, false) to Store.find(ctx, track, true) } }
    val lyrics by produceState<List<TimedLyric>>(emptyList(), track.id, folderTree, rootFolder, refresh) {
        value = withContext(Dispatchers.IO) { loadLrc(ctx, track, folderTree, rootFolder) }
    }
    LaunchedEffect(player) { while (true) { pos = player.currentPosition; delay(400) } }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onTrim(uri, pendingLyrics) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; val root = DocumentFile.fromTreeUri(ctx, uri); val files = root?.let { MetadataMatcher.findVideosRecursively(it) }.orEmpty(); matches = MetadataMatcher.match(files, track); if (matches.isNullOrEmpty()) Toast.makeText(ctx, "No video matching ${track.title} found in that folder", Toast.LENGTH_LONG).show() }
    }
    val video = if (lyricsMode) visuals.second ?: visuals.first else visuals.first
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("NOW PLAYING", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current); IconButton(onClick = onEditTrack) { Icon(Icons.Default.Edit, "Edit song tags") }; IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") } }
        Spacer(Modifier.weight(.4f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Panel)) {
            if (lyricsMode && lyrics.isNotEmpty()) {
                val active = lyrics.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(lyrics.getOrNull(active - 1)?.text.orEmpty(), color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(22.dp)); Text(lyrics.getOrNull(active)?.text.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = LocalAccent.current, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(22.dp)); Text(lyrics.getOrNull(active + 1)?.text.orEmpty(), color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) LocalAccent.current else Color.Gray) }
            IconButton(onClick = { syncDialog = true }, enabled = video != null) { Icon(Icons.Default.Sync, "Sync companion video", tint = if (video != null) LocalAccent.current else Color.Gray) }
            Box { DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Edit song tags") }, onClick = { menu = false; onEditTrack() })
                DropdownMenuItem(text = { Text("Sync video to song") }, onClick = { menu = false; syncDialog = true })
                DropdownMenuItem(text = { Text("Check for matching video") }, onClick = { menu = false; scope.launch {
                    val local = withContext(Dispatchers.IO) { Store.find(ctx, track, false) }
                    if (local != null) Toast.makeText(ctx, "Video found and shown for ${track.title}", Toast.LENGTH_LONG).show()
                    else if (!folderTree.isNullOrBlank()) {
                        val found = withContext(Dispatchers.IO) {
                            DocumentFile.fromTreeUri(ctx, Uri.parse(folderTree))?.let { root -> MetadataMatcher.match(MetadataMatcher.findVideosRecursively(root), track) }.orEmpty()
                        }
                        if (found.isNotEmpty()) matches = found
                        else Toast.makeText(ctx, "No matching video found. Pick a video file or folder.", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(ctx, "No matching video found. Pick a video file or folder.", Toast.LENGTH_LONG).show()
                    onChanged()
                } })
                DropdownMenuItem(text = { Text("Choose video file") }, onClick = { menu = false; pendingLyrics = false; pickVideo.launch("video/*") })
                DropdownMenuItem(text = { Text("Find video in folder") }, onClick = { menu = false; pendingLyrics = false; pickFolder.launch(null) })
                DropdownMenuItem(text = { Text("Choose lyrics video") }, onClick = { menu = false; pendingLyrics = true; pickVideo.launch("video/*") })
                DropdownMenuItem(text = { Text("Download video with yt-dlp") }, onClick = { menu = false; downloadUrl = ""; variants = emptyList(); selectedVariant = null; downloadDialog = true })
                DropdownMenuItem(text = { Text("Remove companion videos") }, onClick = { menu = false; Store.appFile(ctx, track, false).delete(); Store.appFile(ctx, track, true).delete(); onChanged() })
            } }
        }
        Spacer(Modifier.weight(.4f))
    }
    matches?.takeIf { it.isNotEmpty() }?.let { list -> AlertDialog(onDismissRequest = { matches = null }, title = { Text("Matching videos") }, text = { Column { list.forEach { file -> TextButton(onClick = { matches = null; onTrim(file.uri, pendingLyrics) }) { Text(file.name ?: "Choose video") } } } }, confirmButton = { TextButton(onClick = { matches = null }) { Text("Cancel") } }) }
    if (syncDialog) AlertDialog(onDismissRequest = { syncDialog = false }, title = { Text("Sync companion video") },
        text = { Column {
            Text("Adjust the visual timing against this song. Positive values move the video later.", color = Color.LightGray)
            Slider(value = videoOffset.toFloat(), onValueChange = { videoOffset = it.toLong() }, valueRange = -30_000f..30_000f)
            Text("${if (videoOffset >= 0) "+" else "−"}${formatTime(kotlin.math.abs(videoOffset))}", color = LocalAccent.current)
        } }, confirmButton = { TextButton(onClick = { syncPrefs.edit().putLong("offset_${track.id}", videoOffset).apply(); syncDialog = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { videoOffset = 0L; syncPrefs.edit().remove("offset_${track.id}").apply(); syncDialog = false }) { Text("Reset") } })
    if (downloadDialog) AlertDialog(
        onDismissRequest = { if (!downloading) downloadDialog = false },
        title = { Text("Download a companion video") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(downloadUrl, { downloadUrl = it }, label = { Text("Video URL") }, placeholder = { Text("https://…") }, singleLine = true, enabled = !downloading)
            Text("Choose one of the formats below before downloading. Nothing downloads until you select a format and tap Download.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            TextButton(enabled = !updatingYtDlp && !downloading, onClick = {
                updatingYtDlp = true
                scope.launch {
                    val updated = withContext(Dispatchers.IO) { runCatching {
                        YoutubeDL.getInstance().init(ctx.applicationContext)
                        FFmpeg.getInstance().init(ctx.applicationContext)
                        YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                    } }
                    updatingYtDlp = false
                    Toast.makeText(ctx, if (updated.isSuccess) "yt-dlp is ready: ${updated.getOrNull()}" else "Could not update yt-dlp: ${updated.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }) { Text(if (updatingYtDlp) "Updating yt-dlp…" else "Update yt-dlp") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = removeSponsorSegments, onCheckedChange = { removeSponsorSegments = it }, enabled = !downloading)
                Text("Remove SponsorBlock sponsor, intro, and outro sections (YouTube only)", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            }
            if (lookingUp) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            variants.forEach { variant ->
                Row(Modifier.fillMaxWidth().clickable { selectedVariant = variant.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedVariant == variant.id, onClick = { selectedVariant = variant.id })
                    Text(variant.label, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (downloading) LinearProgressIndicator(progress = { (downloadProgress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Row {
            TextButton(enabled = !downloading && !lookingUp && downloadUrl.startsWith("http") && variants.isEmpty(), onClick = {
                lookingUp = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            YoutubeDL.getInstance().init(ctx.applicationContext)
                            FFmpeg.getInstance().init(ctx.applicationContext)
                            YoutubeDL.getInstance().getInfo(downloadUrl.trim()).formats.orEmpty()
                                .filter { !it.formatId.isNullOrBlank() && it.ext == "mp4" && !it.vcodec.isNullOrBlank() && it.vcodec != "none" }
                                .distinctBy { it.formatId }
                                .sortedByDescending { it.height }
                                .take(24)
                                .map { format -> DownloadVariant(format.formatId!!, formatLabel(format)) }
                        }
                    }
                    lookingUp = false
                    if (result.isSuccess && result.getOrNull().orEmpty().isNotEmpty()) {
                        variants = result.getOrDefault(emptyList()); selectedVariant = variants.first().id
                    } else Toast.makeText(ctx, result.exceptionOrNull()?.message ?: "No MP4 video formats found", Toast.LENGTH_LONG).show()
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
                        val request = YoutubeDLRequest(downloadUrl.trim()).apply {
                            addOption("--no-playlist")
                            addOption("-f", selectedVariant!!)
                            if (removeSponsorSegments) addOption("--sponsorblock-remove", "sponsor,intro,outro")
                            addOption("-o", tempFile.absolutePath)
                        }
                        YoutubeDL.getInstance().execute(request, "canvas-${track.id}") { progress, _, _ ->
                            ctx.mainExecutor.execute { downloadProgress = progress }
                        }
                    }
                }
                if (result.isFailure) {
                    downloading = false
                    Toast.makeText(ctx, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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
private fun writeTrackTags(context: android.content.Context, edit: TrackEdit) {
    val source = File(context.cacheDir, "tag-source-${edit.track.id}.mp3")
    val output = File(context.cacheDir, "tag-output-${edit.track.id}.mp3")
    try {
        context.contentResolver.openInputStream(edit.track.uri)?.use { input -> source.outputStream().use { output -> input.copyTo(output) } }
            ?: error("Could not open the selected audio file")
        val audio = AudioFileIO.read(source)
        val tag = audio.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String) {
            if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
        }
        set(FieldKey.TITLE, edit.title); set(FieldKey.TRACK, edit.trackNumber); set(FieldKey.YEAR, edit.year)
        set(FieldKey.GENRE, edit.genre)
        tag.deleteField(FieldKey.ARTIST)
        splitArtists(edit.artist).forEach { tag.addField(FieldKey.ARTIST, it) }
        set(FieldKey.ALBUM, edit.album)
        set(FieldKey.ALBUM_ARTIST, edit.albumArtist); set(FieldKey.COMPOSER, edit.composer); set(FieldKey.COMMENT, edit.comment)
        edit.coverUri?.let { uri ->
            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Could not read the selected cover image")
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) error("Could not encode cover image")
                bitmap.recycle()
                stream.toByteArray()
            }
            val artwork = ArtworkFactory.getNew()
            artwork.setBinaryData(bytes); artwork.setMimeType("image/jpeg"); artwork.setPictureType(3)
            tag.deleteArtworkField(); tag.setField(artwork)
        }
        AudioFileIO.write(audio)
        source.copyTo(output, overwrite = true)
        context.contentResolver.openOutputStream(edit.track.uri, "wt")?.use { stream -> output.inputStream().use { it.copyTo(stream) } }
            ?: error("Could not write changes to the selected audio file")
        MediaScannerConnection.scanFile(context, arrayOf(edit.track.path), arrayOf("audio/mpeg"), null)
    } finally {
        source.delete(); output.delete()
    }
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
