# Changelog

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
