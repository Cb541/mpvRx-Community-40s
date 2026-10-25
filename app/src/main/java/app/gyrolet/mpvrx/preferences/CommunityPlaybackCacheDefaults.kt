/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.preferences

/**
 * Initial mpv.conf for a fresh installation of this community fork.
 * AndroidPreferenceStore uses this text only when the mpv.conf preference
 * has never been set. An existing custom or explicitly blank mpv.conf wins.
 * MpvConfigCache writes the effective content into filesDir/mpv.conf.
 *
 * CAUTION: Up to 4096 MiB forward + 2048 MiB backward of disk-backed demuxer
 * cache may be requested; devices with limited free storage may need smaller
 * values. No user settings are rewritten on app upgrades.
 */
internal object CommunityPlaybackCacheDefaults {
  val mpvConf: String = """
# ==========================================
# MPVRX - AGGRESSIVE PLAYBACK CACHE
# ==========================================

# Enable seekable playback caching
cache=yes
cache-on-disk=yes
demuxer-seekable-cache=yes
demuxer-thread=yes

# Large cache capacity
demuxer-max-bytes=4096MiB
demuxer-max-back-bytes=2048MiB

# Aggressive forward prefetch
demuxer-readahead-secs=3600
cache-secs=3600

# Avoid cache-induced playback pauses
cache-pause=no
demuxer-hysteresis-secs=0

# Network buffering
stream-buffer-size=32MiB

# Allow unused cache capacity to be reused
demuxer-donate-buffer=yes

# Remove temporary cache files when no longer needed
demuxer-cache-unlink-files=immediate
""".trimIndent() + "\n"
}
