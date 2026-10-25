import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
fun main() {
 val bytes=ByteArray(4096){ i->(i*31%251).toByte() }
 val root=File("/tmp/mpvrx-block-test-${System.nanoTime()}")
 val fetched=AtomicInteger(0)
 val cache=SeekByteBlockCache(root, fetch={start,end->
   fetched.incrementAndGet()
   Thread.sleep(35)
   val e=minOf(end,bytes.size-1L).toInt()
   SeekByteBlockCache.Block(start,bytes.size.toLong(),bytes.sliceArray(start.toInt()..e),"video/mp4")
 },blockSize=256,maxDiskBytes=2048,maxMemoryBytes=512)
 try {
   val pool=Executors.newFixedThreadPool(7)
   val futures=(0..6).map { pool.submit(Callable {cache.prime(150)}) }
   futures.forEach { check(it.get()) }
   pool.shutdown()
   check(fetched.get()==1) { "Concurrent clients fetched duplicate blocks: ${fetched.get()}" }
   val slice=cache.stream(150,850).use {it.readBytes()}
   check(slice.contentEquals(bytes.sliceArray(150..850)))
   val count=fetched.get()
   val again=cache.stream(150,850).use {it.readBytes()}
   check(again.contentEquals(slice) && fetched.get()==count)
   println("PASS: concurrent range dedupe; cross-block and repeated reads")
   cache.close()
   check(!root.exists())
   println("PASS: session disk cleanup")
 }finally{root.deleteRecursively()}
}
