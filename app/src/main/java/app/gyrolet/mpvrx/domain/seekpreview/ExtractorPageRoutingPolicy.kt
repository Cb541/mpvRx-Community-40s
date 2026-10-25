/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/**
 * Website/watch URLs are NOT byte-addressable video files. libmpv must receive
 * the original URL to run its bundled yt-dlp hook. Rewriting a video webpage to
 * 127.0.0.1 through SharedPlaybackHttpBridge prevents the extractor from ever
 * seeing the real website.
 *
 * Protect the existing direct-media fast path: CDN URLs, signed links,
 * Stremio/AIOStreams routes and media manifests remain on the shared proxy.
 * This only affects URL routing; no decoder, video buffer or preview behavior.
 */
internal object ExtractorPageRoutingPolicy {
  // Recognizable video webpage services (including the user's original YouTube
  // fix). Use exact domain or DOT-BOUNDARY subdomain matching, never contains().
  // Do not add CDN-specific hosts here (googlevideo.com, phncdn.com, etc.).
  private val extractorWebsiteDomains = setOf(
    "youtube.com", "youtu.be", "youtube-nocookie.com",
    "pornhub.com", "pornhub.org", "pornhubpremium.com",
    "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
    "youporn.com", "tube8.com", "spankbang.com", "eporner.com",
    "redgifs.com", "streamable.com",
    "vimeo.com", "dailymotion.com", "dai.ly", "twitch.tv",
    "tiktok.com", "instagram.com", "facebook.com", "fb.watch",
    "twitter.com", "x.com", "reddit.com", "redd.it",
    "rumble.com", "odysee.com", "bitchute.com", "kick.com",
    "bilibili.com", "b23.tv", "nicovideo.jp", "medal.tv",
    "soundcloud.com", "bandcamp.com", "audiomack.com",
  )

  private val directMediaSuffixes = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts",
    "flv", "wmv", "mpg", "mpeg", "3gp", "m3u8", "m3u", "mpd",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
  )

  /**
   * encodedPath is supplied from okhttp's HttpUrl (never the query text).
   * For an unknown host, only clearly webpage-like files (.html/.php) bypass
   * the proxy, so extensionless Stremio/streaming provider URLs are unaffected.
   */
  fun requiresOriginalUrl(hostname: String, encodedPath: String = ""): Boolean {
    val host = hostname.trim().trimEnd('.').lowercase()
    if (host.isEmpty()) return false
    val path = encodedPath.lowercase().substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
    if (extension in directMediaSuffixes) return false
    if (extractorWebsiteDomains.any { host == it || host.endsWith(".$it") }) return true
    // This helps yt-dlp's generic webpage extractor without diverting opaque
    // tokenized links (often extensionless) away from the fast byte cache.
    return extension == "html" || extension == "htm" || extension == "php"
  }
}
