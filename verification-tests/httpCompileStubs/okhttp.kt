package okhttp3
import java.io.InputStream
class HttpUrl(val scheme:String="https",val host:String="host",val port:Int=443,val encodedPath:String="/"){
  companion object { fun String.toHttpUrlOrNull():HttpUrl? = HttpUrl() }
  fun resolve(location:String):HttpUrl?=HttpUrl()
}
class Request { class Builder {fun url(u:HttpUrl)=this;fun header(k:String,v:String)=this;fun get()=this;fun head()=this;fun build()=Request()} }
class Response : AutoCloseable {
  val code:Int=206
  val message:String="OK"
  val body:ResponseBody?=ResponseBody()
  fun header(k:String):String?=null
  override fun close(){}
}
class ResponseBody { fun contentLength():Long=10L; fun byteStream():InputStream=java.io.ByteArrayInputStream(byteArrayOf()) }
class Call {fun execute():Response=Response()}
class ConnectionPool {fun evictAll(){}}
class Dispatcher {fun cancelAll(){}}
class OkHttpClient { val dispatcher=Dispatcher(); val connectionPool=ConnectionPool(); fun newCall(req:Request)=Call()
  class Builder { fun followRedirects(b:Boolean)=this;fun followSslRedirects(b:Boolean)=this;fun connectTimeout(v:Long,t:java.util.concurrent.TimeUnit)=this;fun readTimeout(v:Long,t:java.util.concurrent.TimeUnit)=this;fun build()=OkHttpClient() }
}
