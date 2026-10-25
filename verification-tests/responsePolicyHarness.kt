import app.gyrolet.mpvrx.domain.seekpreview.PlaybackRangeResponsePolicy as P
fun main() {
  fun ok(status: Int, range: String?, size: String?, encoding: String? = null, start: Long? = null): Boolean =
    P.eligible(status,range,size,encoding,"video/mp4",start) != null
  check(ok(200,null,"12000"))
  check(ok(206,"bytes 2048-3071/12000","1024",start=2048))
  check(!ok(206,"bytes 2048-3071/12000","1023",start=2048))
  check(!ok(206,"bytes 2048-3071/12000","1024",start=2049))
  check(!ok(200,null,"12000",start=2000))
  check(!ok(200,null,null))
  check(!ok(206,"bytes */12000","1024"))
  check(!ok(206,"bytes 0-12000/12000","12001"))
  check(!ok(206,"bytes 0-1023/*","1024"))
  check(!ok(206,"bytes 0-1023/12000","1024",encoding="gzip"))
  check(!ok(416,"bytes 0-1023/12000","1024"))
  println("PASS strict HTTP 200/206 range identity and length validation")
}
