/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Deletes ONLY disposable, video-scoped cache directories (never downloads,
 * settings, shaders, cookies, or the application's other caches).
 *
 * On video STOP/EOF the native player and preview proxy have already released
 * their readers. Renaming the three owned roots makes them inaccessible to the
 * next video immediately. Their potentially multi-gigabyte contents are then
 * unlinked by one serial background janitor so the UI is never blocked.
 *
 * If the process dies during a delete, the next app initialization sweeps the
 * renamed roots and leftover active roots from the previous process.
 */
internal object PlaybackMediaCacheCleanup {
  private val ownedRoots = listOf(
    "mpvrx-shared-transport",  // Shared playback / thumbnail HTTP byte ranges.
    "seek-session-v2",        // Per-video 12-second thumbnail JPEGs.
    "nuvio-seek-read-ahead",  // Temporary mpv playback read-ahead.
  )
  private const val retiredMark = ".retired-"
  private val janitor = Executors.newSingleThreadExecutor { job ->
    Thread(job, "mpvrx-video-cache-janitor").apply { isDaemon = true }
  }

  /** Called only after the video stops, or after libmpv has been destroyed. */
  @Synchronized
  fun afterPlaybackStopped(cacheDir: File?) {
    retireKnownRoots(cacheDir)
  }

  /** Called before creating libmpv for this process, when no video is active. */
  @Synchronized
  fun beforeCoreInitialization(cacheDir: File?) {
    retireKnownRoots(cacheDir)
  }

  private fun retireKnownRoots(cacheDir: File?) {
    val base = cacheDir?.takeIf { it.isDirectory } ?: return
    val pending = mutableListOf<File>()
    // Leftover .retired folders may be from a crash during the last cleanup.
    base.listFiles()?.filterTo(pending) { entry ->
      ownedRoots.any { root -> entry.name.startsWith("$root$retiredMark") }
    }
    for (name in ownedRoots) {
      val active = File(base, name)
      if (!active.exists()) continue
      // Rename atomically within the same internal cache filesystem. Never
      // asynchronously delete a path a new playback session may reuse.
      val retired = File(base, "$name$retiredMark${UUID.randomUUID()}")
      if (active.renameTo(retired)) {
        pending.add(retired)
        // mpv keeps a fixed demuxer-cache-dir path for its core lifetime.
        // Re-create it before the next video can start on this same core.
        if (name == "nuvio-seek-read-ahead") active.mkdirs()
      }
      // If rename unexpectedly fails, leave the original alone rather than
      // accidentally deleting live files from a newer playback session.
    }
    if (pending.isNotEmpty()) {
      janitor.execute {
        pending.forEach { target -> runCatching { target.deleteRecursively() } }
      }
    }
  }
}
