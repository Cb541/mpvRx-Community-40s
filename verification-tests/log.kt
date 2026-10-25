package android.util
object Log { val schedulerEvents = java.util.concurrent.ConcurrentLinkedQueue<String>(); fun d(tag: String,msg:String):Int { if (msg.startsWith("ahead-two-pass mode=")) schedulerEvents.add(msg); println("$tag $msg");return 0 }; fun i(tag:String,msg:String):Int=0;fun w(tag:String,msg:String):Int{println("$tag WARN $msg");return 0} }
open class LruCache<K,V>(val max:Int) {
  val map=HashMap<K,V>()
  open fun sizeOf(key:K,value:V):Int=1
  fun get(key:K):V?=synchronized(map){map[key]}
  fun put(key:K,value:V):V?=synchronized(map){map.put(key,value)}
  fun evictAll()=synchronized(map){map.clear()}
}
