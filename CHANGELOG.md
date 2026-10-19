# Changelog

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
