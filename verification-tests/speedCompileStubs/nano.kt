package fi.iki.elonen
import java.io.InputStream
open class NanoHTTPD(host:String, port:Int) {
  enum class Method { GET, HEAD, POST }
  open class Response {
    interface IStatus { fun getRequestStatus():Int; fun getDescription():String }
    enum class Status : IStatus {
      SERVICE_UNAVAILABLE, NOT_FOUND, METHOD_NOT_ALLOWED, BAD_REQUEST, OK, PARTIAL_CONTENT;
      override fun getRequestStatus()=400
      override fun getDescription()="Error"
    }
    fun addHeader(k:String,v:String){}
  }
  interface IHTTPSession { val uri:String; val headers:Map<String,String>; val method:Method }
  val listeningPort:Int=8000
  fun start(timeout:Int, daemon:Boolean){}
  fun stop(){}
  open fun serve(session:IHTTPSession):Response=Response()
  fun newFixedLengthResponse(s:Response.IStatus, mime:String, stream:InputStream,size:Long):Response=Response()
  fun newFixedLengthResponse(s:Response.IStatus,mime:String,text:String):Response=Response()
  fun newChunkedResponse(s:Response.IStatus,mime:String,stream:InputStream):Response=Response()
  companion object { const val SOCKET_READ_TIMEOUT=5000; const val MIME_PLAINTEXT="text/plain" }
}
