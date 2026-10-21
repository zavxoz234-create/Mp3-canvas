package com.example.canvasplayer

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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

/**
 * Art box. The static cover is shown until the canvas video has rendered its first frame, then the video
 * fades in and the cover is removed from the composition entirely (no hidden image kept alive underneath).
 * If the video cannot play, the cover simply stays.
 */
@Composable
fun ArtBox(videoUri: Uri?, audio: Player, offsetMs: Long = 0L, modifier: Modifier = Modifier, staticArt: @Composable () -> Unit) {
    val reveal = remember(videoUri) { Animatable(0f) }
    var firstFrame by remember(videoUri) { mutableStateOf(false) }
    LaunchedEffect(firstFrame) { if (firstFrame) reveal.animateTo(1f, tween(260)) }
    val coverVisible by remember(reveal) { derivedStateOf { reveal.value < 1f } }
    Box(modifier) {
        if (videoUri == null || coverVisible) staticArt()
        if (videoUri != null) {
            LoopingVideo(videoUri, audio, offsetMs, Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }) { firstFrame = true }
        }
    }
}

/**
 * Renders into a TextureView instead of PlayerView's default SurfaceView. A SurfaceView ignores Compose's
 * rounded clip, alpha and translation, which is what made the artwork animations glitch while video played.
 * The view centre-crops the video itself so portrait or wide clips fill the square.
 */
private class CropTextureView(context: Context) : TextureView(context) {
    private var videoW = 0
    private var videoH = 0

    fun setVideoSize(w: Int, h: Int) {
        videoW = w; videoH = h
        applyCrop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    private fun applyCrop() {
        val w = width.toFloat(); val h = height.toFloat()
        if (videoW <= 0 || videoH <= 0 || w <= 0f || h <= 0f) return
        // Fit the whole video inside the view, centered — never crop, never stretch.
        val scale = minOf(w / videoW, h / videoH)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((w - videoW * scale) / 2f, (h - videoH * scale) / 2f)
        setTransform(m)
    }

/** Keeps the muted video within [toleranceMs] of the audio position (wrapping at the loop point). */. 
private fun syncVideo(video: ExoPlayer, audio: Player, offsetMs: Long, toleranceMs: Long) {
    val duration = video.duration
    if (duration <= 0 || duration == C.TIME_UNSET) return
    val target = Math.floorMod(audio.currentPosition - offsetMs, duration)
    val diff = abs(video.currentPosition - target)
    if (minOf(diff, duration - diff) > toleranceMs) video.seekTo(target)
}

@Composable
fun LoopingVideo(uri: Uri, audio: Player, offsetMs: Long, modifier: Modifier = Modifier, onFirstFrame: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    val offset by rememberUpdatedState(offsetMs)
    val vp = remember {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, false) // never steal audio focus
            // The canvas is silent: do not decode its audio at all, and never decode more than 720p.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(1280, 720)
                .build()
        }
    }
    val texture = remember { CropTextureView(ctx).also { vp.setVideoTextureView(it) } }

    // Declared first so it is disposed last: listeners are removed before the player is released.
    DisposableEffect(vp) { onDispose { vp.release() } }

    DisposableEffect(vp) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameCallback() }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                texture.setVideoSize((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) syncVideo(vp, audio, offset, 300)
            }
        }
        vp.addListener(l)
        onDispose { vp.removeListener(l) }
    }

    DisposableEffect(audio, vp) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                vp.playWhenReady = isPlaying
                if (isPlaying) syncVideo(vp, audio, offset, 300)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) syncVideo(vp, audio, offset, 300)
            }
        }
        audio.addListener(l)
        onDispose { audio.removeListener(l) }
    }

    // Only the media item is (re)prepared here. Changing the sync offset must NOT restart the decoder.
    LaunchedEffect(uri) {
        vp.setMediaItem(MediaItem.fromUri(uri))
        vp.prepare()
        vp.playWhenReady = audio.isPlaying
        while (true) { // slow drift correction; seeking too eagerly causes visible stutter
            delay(2_000)
            if (audio.isPlaying) syncVideo(vp, audio, offset, 500)
        }
    }

    // Debounced so dragging the sync slider does not seek on every pixel.
    LaunchedEffect(offsetMs) {
        delay(150)
        syncVideo(vp, audio, offsetMs, 0)
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) vp.pause()
            if (e == Lifecycle.Event.ON_START) { vp.playWhenReady = audio.isPlaying; syncVideo(vp, audio, offset, 300) }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    AndroidView(factory = { texture }, modifier = modifier)
}
