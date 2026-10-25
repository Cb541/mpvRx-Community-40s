/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Session-scoped JPEG cache. Mirrors the Apple TV player's disposable seek-disk
 * spool: recreating a UI keeps the same spool; changing/stopping video retires it.
 * Nothing here stores video URLs, tokens, HTTP headers or image metadata on disk.
 */
internal object SeekPreviewSessionDisk {
  private val lock = Any()
  private val janitor = Executors.newSingleThreadExecutor { command ->
    Thread(command, "mpvrx-seek-disk-cleanup").apply { isDaemon = true }
  }
  private val processEpoch = UUID.randomUUID().toString().substring(0, 12)
  private var root: File? = null
  private var activeGeneration = 0L

  fun attach(context: Context, generation: Long, source: String): File = synchronized(lock) {
    val targetRoot = File(context.cacheDir, "seek-session-v2")
    if (root == null || root?.absolutePath != targetRoot.absolutePath) {
      // Crash recovery: old sessions are always disposable; do this off the UI thread.
      root = targetRoot
      val stale = targetRoot.listFiles()?.toList().orEmpty()
      janitor.execute { stale.forEach { it.deleteRecursively() } }
    }
    if (activeGeneration != generation) {
      activeGeneration = generation
      retireExceptLocked(generation)
    }
    val fingerprint = MessageDigest.getInstance("SHA-256")
      .digest(source.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    File(targetRoot, "$processEpoch-g$generation-$fingerprint").apply { mkdirs() }
  }

  /** Called by PlaybackSession on media replacement/stop and terminal end-of-file. */
  fun retireExcept(generation: Long) = synchronized(lock) {
    activeGeneration = generation
    retireExceptLocked(generation)
  }

  fun isActive(generation: Long): Boolean = synchronized(lock) { activeGeneration == generation && generation > 0L }

  fun retireAll() = synchronized(lock) {
    activeGeneration = 0L
    retireExceptLocked(0L)
  }

  private fun retireExceptLocked(generation: Long) {
    val currentRoot = root ?: return
    val old = currentRoot.listFiles()?.filter {
      generation == 0L || !it.name.startsWith("$processEpoch-g$generation-")
    }.orEmpty()
    if (old.isNotEmpty()) janitor.execute { old.forEach { it.deleteRecursively() } }
  }
}
