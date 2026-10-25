# Seek strip adaptation

This modification adapts the seek strip layout and presentation from the user-supplied
NuvioTV-Fork 1.1.0-beta-nt4.2 archive. Its SeekStripLayout.kt and corresponding tests
are ported with package changes; SeekThumbnailStrip.kt adapts the fixed centre frame,
sliding tiles and spacing algorithm to mpvRx's Compose Material 3 controls.

NuvioTV-Fork's original source is GPL-3.0; its license is reproduced in LICENSE-NuvioTV.
mpvRx remains AGPL-3.0-or-later. Original authors retain their copyrights.

The Nuvio ExoPlayer demuxing / range-reading / full-title coverage engine is NOT included.
SeekPreviewStore is an mpvRx-specific local extraction adapter over its existing
PlaybackSession.grabThumbnailFast native decoder. It generates previews on demand,
prioritizes the landing picture, opportunistically decodes HTTP frames with up to two independent platform retrievers, serializes native fallback decoding and caches nearby strip pictures.
No thumbnail service, API key, or upload is involved. Streaming videos still require
reads from their original playback source.
