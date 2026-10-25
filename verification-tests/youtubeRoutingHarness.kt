package app.gyrolet.mpvrx.domain.seekpreview

fun main() {
  for (host in listOf(
      "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
      "YouTube.COM", "youtube.com.", "youtu.be", "YOUTU.BE",
      "www.youtube-nocookie.com", "youtube-nocookie.com"
  )) check(ExtractorPageRoutingPolicy.requiresOriginalUrl(host)) { "Must bypass proxy: $host" }
  for (host in listOf(
      "rr3---sn.example.googlevideo.com", "googlevideo.com", "r3---sn.abc.googlevideo.com",
      "aiostreams.elfhosted.com", "cdn.example.com", "192.168.0.10",
      "fake-youtube.com", "youtube.com.evil.example", "youtube-nocookie.com.evil.example",
      "www.youtube.co", "youtube.be", "youtube.fake"
  )) check(!ExtractorPageRoutingPolicy.requiresOriginalUrl(host)) { "Must not bypass proxy: $host" }
  println("YouTube routing: 10 extractor hosts bypass localhost, 12 direct/CDN hosts preserved")
}
