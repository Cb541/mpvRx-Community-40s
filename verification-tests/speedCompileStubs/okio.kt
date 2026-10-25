package okio
import java.io.Closeable
interface Source : Closeable
interface BufferedSource : Source
open class ForwardingSource(val delegate: Source) : Source { override fun close() = delegate.close() }
fun Source.buffer(): BufferedSource = object: BufferedSource { override fun close() = this@buffer.close() }
