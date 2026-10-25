package app.gyrolet.mpvrx.domain.seekpreview

fun main() {
  fun extractor(host: String, path: String = "/watch", expected: Boolean = true) {
    val result = ExtractorPageRoutingPolicy.requiresOriginalUrl(host, path)
    check(result == expected) { "host=$host path=$path expected=$expected got=$result" }
  }

  // A video PAGE needs yt-dlp to see the original domain and URL.
  listOf(
    "pornhub.com", "www.pornhub.com", "m.pornhub.com", "PornHub.COM.",
    "pornhub.org", "www.pornhubpremium.com", "xvideos.com", "www.xnxx.com",
    "xhamster.com", "redtube.com", "spankbang.com", "eporner.com",
    "youtube.com", "music.youtube.com", "youtu.be", "youtube-nocookie.com",
    "www.vimeo.com", "player.vimeo.com", "dailymotion.com", "tiktok.com",
    "instagram.com", "streamable.com", "twitch.tv", "redd.it", "reddit.com",
    "rumble.com", "twitter.com", "x.com", "soundcloud.com", "bandcamp.com",
  ).forEach { extractor(it) }
  extractor("www.pornhub.com", "/view_video.php")
  extractor("www.pornhub.com", "/embed/abcdef")
  extractor("cdn.example.org", "/video/embed.html")
  extractor("cdn.example.org", "/view_video.php")

  // Known direct-media paths must *never* be forced through yt-dlp.
  for (extension in listOf("mp4", "mkv", "webm", "m3u8", "mpd", "mp3", "ts")) {
    extractor("www.pornhub.com", "/static/file.$extension", false)
    extractor("youtube.com", "/stream/file.$extension", false)
  }
  for (host in listOf(
    "googlevideo.com", "rr3---sn.googlevideo.com", "phncdn.com", "cdn.phncdn.com",
    "cdn.example.com", "aiostreams.elfhosted.com", "premiumize.me", "torbox.app",
    "pornhub.com.evil.example", "notpornhub.com", "pornhub.org.evil.example",
    "fake-youtube.com", "youtube.com.evil.example", "xvideos.com.attacker.tld",
    "192.168.1.20", "127.0.0.1", "localhost",
  )) {
    extractor(host, "/opaque-signed-video-token", false)
  }
  extractor("cdn.example.org", "/video/opaque-token", false)
  extractor("aiostreams.elfhosted.com", "/stremio/opaque/path", false)
  extractor("", "/view_video.php", false)
  println("PASS: webpage URLs reach yt-dlp; direct CDN, Stremio and media URLs retain fast transport")
}
