package com.example.canvasplayer

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.media3.common.PlaybackException
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

/** Keeps the muted video within [toleranceMs] of the audio position (wrapping at the loop point). */
private fun syncVideo(video: ExoPlayer, audio: Player, offsetMs: Long, toleranceMs: Long) {
    val duration = video.duration
    if (duration <= 0 || duration == C.TIME_UNSET) return
    val target = Math.floorMod(audio.currentPosition - offsetMs, duration)
    val diff = abs(video.currentPosition - target)
    if (minOf(diff, duration - diff) > toleranceMs) video.seekTo(target)
}

/**
 * Renders into a TextureView (SurfaceView would ignore Compose's clip/alpha and glitch the animations).
 * The view is sized to the video's own aspect ratio, centred — the whole frame always fits:
 * never cropped, never stretched. Every numeric edge (unknown size, zero width, rotation
 * metadata, infinite/NaN ratios) is guarded so this can never crash the screen.
 */
@Composable
fun LoopingVideo(uri: Uri, audio: Player, offsetMs: Long, modifier: Modifier = Modifier, fill: Boolean = false, onFirstFrame: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    var videoAspect by remember(uri) { mutableStateOf(0f) }
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
    val texture = remember { TextureView(ctx).also { vp.setVideoTextureView(it) } }

    // Declared first so it is disposed last: listeners are removed before the player is released.
    DisposableEffect(vp) { onDispose { runCatching { vp.release() } } }

    DisposableEffect(vp) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameCallback() }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val h = videoSize.height.toFloat()
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                if (h <= 0f || w <= 0f) return
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val raw = if (rotated) h / w else w / h
                if (raw.isFinite() && raw > 0f) videoAspect = raw.coerceIn(0.2f, 5f)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) syncVideo(vp, audio, offset, 300)
            }
            override fun onPlayerError(error: PlaybackException) {
                // A broken stream/file must never kill the screen: stop quietly, album art stays.
                runCatching { vp.stop() }
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
        runCatching {
            vp.setMediaItem(MediaItem.fromUri(uri))
            vp.prepare()
        } // a missing decoder/module must leave album art, never crash the screen
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

    // Fit by explicit sizing: compute the largest video-shaped rect that fits and size the view to it.
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val viewModifier = if (videoAspect > 0f) {
            if (fill) { // cover: scale up until the area is filled; the excess is cropped by the window
                var vw = maxWidth
                var vh = maxWidth / videoAspect
                if (vh < maxHeight) { vh = maxHeight; vw = maxHeight * videoAspect }
                Modifier.size(vw, vh)
            } else { // contain: the whole frame always fits, centred
                var vw = maxWidth
                var vh = maxWidth / videoAspect
                if (vh > maxHeight) { vh = maxHeight; vw = maxHeight * videoAspect }
                Modifier.size(vw, vh)
            }
        } else Modifier.fillMaxSize()
        AndroidView(factory = { texture }, modifier = viewModifier)
    }
}
