Seek listener Kotlin compilation fix for the mpvRx thumbnail-restoration patch

Cause:
SeekPreviewStore.kt invokes PlaybackSession.addPlaybackSeekListener and
PlaybackSession.removePlaybackSeekListener, but your PlaybackSession.kt came
from the earlier source version that only signaled seeks via a timestamp.

Install:
1. unzip this ZIP at the root of your mpvRx 2.7.2 source tree.
2. python3 APPLY-SEEK-LISTENER-COMPILE-FIX.py
3. bash BUILD-MOBILE-APK.sh --install

The Python script makes a timestamped backup of PlaybackSession.kt and changes
only the seek-listener definitions + the 3 timestamp notification sites.
It preserves the newer playback URL/keyframe fixes, thumbnail proxy, decoder,
cache, and UI already present in your source tree.

If the script reports that the file differs, do not overwrite it with an older
PlaybackSession.kt. Ask to inspect your local file first.
