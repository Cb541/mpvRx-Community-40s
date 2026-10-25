import app.gyrolet.mpvrx.domain.seekpreview.LargeForegroundRangePolicy as P

fun main() {
 val gb=21_000_000_000L
 check(P.requested(0L,null,gb)?.end == 8L*1024*1024-1)
 check(P.requested(gb-100,null,gb)?.bytes == 100L)
 check(P.requested(300L,500L,gb)?.bytes == 201L)
 check(P.requested(300L,100L,gb) == null)
 check(P.requested(0L,null,19_000_000_000L) == null)
 // A 5GB media file now also gets progressive-first-byte foreground reads,
 // but existing 20GB-specific policy remains unchanged.
 val medium=5_100_000_000L
 check(P.requestedManual(0L,null,medium)?.end == 8L*1024*1024-1)
 check(P.requestedManual(0L,null,medium)?.bytes == 8L*1024*1024)
 check(P.requestedManual(medium-100L,null,medium)?.bytes == 100L)
 check(P.requestedManual(1_048_000L,1_048_500L,medium)?.bytes == 501L)
 check(P.requestedManual(100L,99L,medium) == null)
 check(P.requestedManual(medium,null,medium) == null)
 check(P.requestedManual(0L,null,0L) == null)
 check(P.validated("bytes 0-4095/$medium",null,206,
   P.requestedManual(0L,4095L,medium)!!,4096L) != null)
 check(P.validated("bytes 0-4094/$medium",null,206,
   P.requestedManual(0L,4095L,medium)!!,4096L) == null)

 val span = P.requested(600L,null,gb)!!
 val correct="bytes ${span.start}-${span.end}/${span.total}"
 check(P.validated(correct,null,206,span,span.bytes) == span)
 check(P.validated(correct,"identity",206,span,-1L) == span)
 check(P.validated(correct,"gzip",206,span,span.bytes) == null)
 check(P.validated(correct,null,200,span,span.bytes) == null)
 check(P.validated("bytes 601-${span.end}/$gb",null,206,span,span.bytes) == null)
 check(P.validated("bytes ${span.start}-${span.end}/$gb",null,206,span,5L) == null)
 check(P.validated("bytes ${span.start}-${span.end}/*",null,206,span,span.bytes) == null)
 println("PASS large foreground progressive range policy / strict 206 validation")
}
