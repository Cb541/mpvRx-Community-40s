import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
fun main() {
 val temp = Files.createTempDirectory("mpvrx-cache-only-").toFile()
 val networkCalls = AtomicInteger(0)
 val source = ByteArray(5 * 1024 * 1024) { (it * 17).toByte() }
 val cache = SeekByteBlockCache(temp, fetch = { a,b ->
   networkCalls.incrementAndGet()
   val length=(b-a+1).toInt()
   SeekByteBlockCache.Block(a,source.size.toLong(),source.copyOfRange(a.toInt(),a.toInt()+length),"video/mp4")
 })
 try {
   check(cache.length() == null)
   check(cache.cachedContiguousEnd(0,10) == null)
   val one = source.copyOfRange(0,1024*1024)
   check(cache.offerPlaybackBlock(0,source.size.toLong(),one,"video/mp4"))
   val expectedEnd = (1024*1024-1).toLong()
   check(cache.cachedContiguousEnd(123,3_000_000) == expectedEnd)
   cache.streamCachedOnly(300,2047).use { check(it.readBytes().contentEquals(source.copyOfRange(300,2048))) }
   val before=networkCalls.get()
   cache.streamCachedOnly(1024*1024L,1024*1024L+50).use { stream ->
     var failed=false
     try { stream.read() } catch (_: IOException) { failed=true }
     check(failed)
   }
   check(networkCalls.get()==before) { "CACHE-ONLY READER MADE A NETWORK REQUEST" }
   check(cache.cachedContiguousEnd(1024*1024L,2_000_000L)==null)
   val second=source.copyOfRange(1024*1024,2*1024*1024)
   check(cache.offerPlaybackBlock(1024*1024L,source.size.toLong(),second,"video/mp4"))
   check(cache.cachedContiguousEnd(300,3_000_000)==(2*1024*1024L-1))
   cache.streamCachedOnly(500_000,1_800_000).use {
     check(it.readBytes().contentEquals(source.copyOfRange(500_000,1_800_001)))
   }
   check(networkCalls.get()==0)
   println("PASS: cache-only preview never calls origin fetch; spans and misses validated")
 } finally { cache.close(); check(!temp.exists()) }
}
