# Changelog

## 0.8.0 — 2026-10-20

### New
- Automatic official-video lookup. When a song has no companion video, the player searches YouTube (via the bundled yt-dlp) and downloads the official upload: the artist's own channel, a VEVO channel, or a verified channel that names the artist and calls the upload official. Lyric videos, live versions, covers, remixes, audio-only uploads and auto-generated "Topic" uploads are skipped. Only H.264 up to 720p is fetched.
- Player menu: **Find official video now**, **Auto-find official videos** (on/off) and **Auto-download on Wi-Fi only** (on/off). Searches that find nothing are not repeated for a week; removing a video stops it from being fetched again.
- When a canvas video is available the album art is replaced by it as soon as the first frame is rendered. The cover is removed from the screen instead of being kept underneath.
- Long titles, artists and albums scroll (Now Playing, mini-player, and the playing row in the library).

### Performance and animation fixes
- Playback position updates no longer recompose the whole Now Playing screen; only the slider, the time labels and the lyrics read it. The slider no longer fights with the position poll while dragging.
- Video renders on a TextureView so rounded clipping, fading and swipe movement apply to it (a SurfaceView ignores all three). Changing the sync offset no longer re-prepares the decoder, drift correction is gentler, the video's audio track is not decoded, and the lyrics view no longer rebuilds the video player each time it is opened.
- Swiping the artwork now follows the finger and springs back. Lyrics scroll smoothly to the active line, and the active line scales in the graphics layer instead of changing text size.
- Album art is decoded once at the displayed size, kept in an LRU cache, and at most two decodes run at once (previously every list row opened its own MediaMetadataRetriever and decoded the full-size picture). Accent colour reuses the same decode.
- The previous song's video is no longer shown for a moment after changing track.

### Notes
- Spotify Canvas is not supported: Spotify has no public API for it.
- Official music videos often start with an intro that the studio track does not have; use **Sync video to song** if the picture is offset.
- yt-dlp can be blocked by YouTube sign-in or age checks; those songs simply keep their album art.

## 0.7.5 — 2026-10-20

### Fixed and improved
- Keep the last known library visible while Android rebuilds its media index, retry the scan, and avoid saving a temporary empty library over the cache.
- Rank Deezer cover results by title and artist similarity, support Deezer's HTTP cover URLs through HTTPS, and report API or image request errors clearly.

## 0.7.1 — 2026-10-20

### Fixed and improved
- Remove the repeatedly rendered full-screen album-art blur that caused player-screen stutter.
- Restore folder-filtered songs when Android's MediaStore paths do not exactly match the selected SAF folder, using its persisted folder grant as a fallback.
- Show a clear empty-library message when a selected folder contains no indexed tracks.
- Clear stale lyrics immediately on track changes and only update the lyric position from the matching current player item.
- Keep precise manual video sync available when a companion video has no decodable audio waveform.
- Refresh the player's companion video after an in-app download completes.

### Notes
- Age-restricted or sign-in-only YouTube videos may be unavailable to yt-dlp; the app does not bypass account or age checks. Choose an accessible video or import a local file instead.
- A video without an audio stream cannot produce an audio waveform; use the manual sync controls.

## 0.7.0 — 2026-10-20

### Fixed and improved
- Reduce expensive artwork blur and remove animated lyric color transitions to improve playback-screen responsiveness.
- Reset synced lyric position and reload the current track's lyrics when playback moves to another song.
- Group albums by Album Artist metadata instead of MediaStore album IDs that can differ across tracks.
- Add manual video sync playback and fine-grained offset buttons, including when waveform decoding is unavailable.
- Apply a selected or cleared album cover to every scanned track in that album.

## 0.6.0 — 2026-10-20

### Fixed and improved
- Explicitly merge Android internet access into the installed app manifest so the in-app cover finder can connect to Deezer.
- Decode 8-bit, 16-bit, packed 24-bit, 32-bit, and float audio output when generating song and video sync waveforms; normalize quiet recordings so the waveform is visible.
- Read embedded Album Artist metadata when Android MediaStore leaves its Album Artist column blank, and use it consistently to group album tracks.
- Add slide and fade transitions when changing screens and when the current track artwork changes.

### Notes
- Waveform sync still needs an audio stream in the companion video; silent video can only be aligned with the timing slider.
- Internet permission is granted at install time by Android; reinstall/update to v0.6.0 to receive the corrected app manifest.

## 0.5.0 — 2026-10-19

### Fixed and improved
- Search Deezer cover art inside the app for a song or album, preview actual image dimensions, and apply or clear embedded MP3 artwork.
- Use album artist metadata to group album tracks consistently.
- Move library options to the header and anchor player actions to the three-dot button; raise the mini-player and align sync controls.
- Keep decoded audio in trimmed companion videos and add a duration fallback for waveform generation.
- Show synced lyrics centered in a scrollable list, animate the active line, seek when a line is tapped, and return to the current line after five seconds of inactivity.
- Swipe up on artwork for lyrics, down to leave the player, and sideways to change tracks.
- Initialize yt-dlp and FFmpeg at startup, show initialization errors, and include all supported Android ABIs.

### Notes
- In-app cover candidates are supplied by Deezer search results and their displayed pixel sizes are read from the image files.
- Video waveforms require an audio track in the selected video; older silent companions can still be synced with the timing slider.

## 0.4.0 — 2026-10-19

### Fixed and improved
- Make `.lrc` discovery work directly beside the MP3 and fall back to the selected SAF folder when Android reports a different storage path. Handle UTF-8/UTF-16 files and standard synced timestamps.
- Improve yt-dlp downloads by retrying with the current stable engine after lookup/download errors, combining video-only formats with audio, creating a consistent MP4 output, and showing actionable errors in the app.
- Add draggable song/video audio waveforms for visual sync, retain source audio in saved video companions for waveform analysis, and keep the fine timing control.
- Use blurred album art behind the now-playing screen and apply the album-derived accent to buttons, sliders, and selected controls.
- Add Google Images song and album searches using Deezer and 1900x1900 terms, and show the selected cover image dimensions before embedding it.
- Show the v0.4.0 changelog once after the first launch of this version.

### Notes
- Google Images opens externally; save a chosen result, then pick it in the tag editor. The app shows its pixel dimensions before saving it into the MP3.
- Waveform alignment is a visual aid; use the timing slider for fine adjustment and listen to confirm.

## 0.3.0 — 2026-10-19

### Fixed and improved
- Open MP3 tag editing directly from the player screen.
- Search recursively in a chosen video folder and show matching files to preview and select.
- Make “Check for matching video” search the selected video folder as well as companion locations.
- Add per-song video timing adjustment, preserve settings, and crop portrait clips into the square visual area.
- Add an optional SponsorBlock cut for YouTube sponsor, intro, and outro segments during manual yt-dlp downloads, plus an in-app yt-dlp updater and clearer errors.
- Keep audio playback running when opening the full player from the mini-player.
- Use album-cover colors for player accents, and move shuffle and library options into opposite ends of the library action row.
- Improve synchronized `.lrc` matching for song-title or audio-filename basenames, case and punctuation differences, multiple timestamps, and `[offset:]` values.
- Keep multiple selected music folders and add support for selecting removable storage folders.
- Make artist, album, and song rows open their respective views and show accurate selected-group counts.

### Notes
- Video sync can be tuned manually per track. SponsorBlock data is available only for supported YouTube videos; other clips need manual sync and trim.
- The changelog is shown once after installing each app version.

## 0.2.0 — 2026-10-19

### Added
- Refreshed the music library and now-playing screen with album artwork, a compact mini-player, and standard previous, play/pause, next, back, seek, and shuffle controls.
- Select a music folder to limit the library scan to that folder; search the visible songs.
- Edit MP3 title, track number, year, genre, artist, album, album artist, composer, and comment tags.
- Choose cover art and embed it in the MP3 file; embedded artwork is used for the track display and static media artwork.
- Read synced `.lrc` lyrics named after the song from the selected music folder.
- Find companion MP4 files and choose video or lyric-video files for a track.
- Enter a video URL, inspect available MP4 formats, choose a format, then explicitly start that download.
- Detect quiet edges in downloaded videos and remove leading/trailing quiet sections when possible; manually trim and save companion clips.

### Notes
- Video downloads are user initiated and require selecting a listed format before downloading.
- If no companion video or embedded cover exists, the player falls back to available static album art or its default artwork.
