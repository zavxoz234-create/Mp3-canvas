package com.example.canvasplayer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Audio only. The session exposes static artwork, so lock screen / notification never show video. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** Art box: video if available, otherwise static album art. */
@Composable
fun ArtBox(videoUri: Uri?, staticArt: Uri, audio: Player, offsetMs: Long = 0L, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (videoUri != null) LoopingVideo(videoUri, audio, offsetMs)
        else AsyncImage(
            model = staticArt, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun LoopingVideo(uri: Uri, audio: Player, offsetMs: Long = 0L) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val vp = remember {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, false) // never steal audio focus
        }
    }

    LaunchedEffect(uri, offsetMs) {
        vp.setMediaItem(MediaItem.fromUri(uri))
        vp.prepare()
        vp.playWhenReady = audio.isPlaying
        while (true) { // keep the video aligned with the audio position
            val dur = vp.duration
            if (dur > 0 && dur != C.TIME_UNSET) {
                val target = Math.floorMod(audio.currentPosition - offsetMs, dur)
                if (abs(vp.currentPosition - target) > 400) vp.seekTo(target)
            }
            delay(1500)
        }
    }

    DisposableEffect(audio) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { vp.playWhenReady = isPlaying }
        }
        audio.addListener(l)
        onDispose { audio.removeListener(l) }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) vp.pause()
            if (e == Lifecycle.Event.ON_START) vp.playWhenReady = audio.isPlaying
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vp.release() }
    }

    AndroidView(factory = { context ->
        PlayerView(context).apply {
            player = vp
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }, modifier = Modifier.fillMaxSize())
}
