package okhttp3
import java.io.InputStream
import okio.Source
interface MediaType
class HttpUrl(val scheme:String="https",val host:String="host",val port:Int=443) {
 companion object { fun String.toHttpUrlOrNull():HttpUrl?=HttpUrl() }
 fun resolve(location:String):HttpUrl?=HttpUrl()
}
class Request { class Builder { fun url(u:HttpUrl)=this; fun header(k:String,v:String)=this; fun get()=this; fun head()=this; fun build()=Request() } }
open class ResponseBody {
 open fun contentType():MediaType?=null
 open fun contentLength():Long=0
 open fun source():okio.BufferedSource=object:okio.BufferedSource {override fun close() {}}
 fun byteStream():InputStream=java.io.ByteArrayInputStream(byteArrayOf())
}
class Response : AutoCloseable {
 val code:Int=206
 val message:String="OK"
 val body:ResponseBody?=ResponseBody()
 fun header(k:String):String?=null
 fun newBuilder():Builder=Builder()
 override fun close() {}
 class Builder { fun body(b:ResponseBody)=this; fun build()=Response() }
}
class Call { fun execute():Response=Response(); fun cancel() {} }
class ConnectionPool {fun evictAll() {}}
class Dispatcher {fun cancelAll() {}}
class OkHttpClient {val dispatcher=Dispatcher(); val connectionPool=ConnectionPool(); fun newCall(req:Request)=Call()
 class Builder {fun followRedirects(b:Boolean)=this;fun followSslRedirects(b:Boolean)=this;fun connectTimeout(v:Long,t:java.util.concurrent.TimeUnit)=this;fun readTimeout(v:Long,t:java.util.concurrent.TimeUnit)=this;fun build()=OkHttpClient() }
}
