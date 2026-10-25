# Bundled default mpv.conf (community build)

The following settings are **included in the APK** and automatically become the effective `mpv.conf` for **fresh installs**. Users may edit them in the app. Existing preferences are not replaced during upgrades.

```ini
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
```

On fresh install, the `AdvancedPreferences.mpvConf` preference resolves to this default. `MpvConfigCache.ensureCurrent()` writes the resolved config to the app-private `filesDir/mpv.conf` on player startup. **No manual setup is required** for new users.

> Storage caution: 4096MiB forward + 2048MiB backward are cache capacity limits, not guaranteed pre-allocations. With disk caching enabled, large video sessions may consume many GiB of free storage.
