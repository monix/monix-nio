package monix.nio.file.internal

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.execution.UncaughtExceptionReporter

import collection.JavaConverters._
import scala.util.control.NonFatal

trait AsyncChannel {
  protected def onOpenError(t: Throwable)
  def channel: AsyncMonixFileChannel

  // close channel errors are not exposed as the operation is performed
  // under the hood, sometimes async, after the communication with
  // the app has been already closed
  protected def closeChannel()(implicit reporter: UncaughtExceptionReporter) =
      try {channel.close()}
      catch {case NonFatal(ex) => reporter.reportFailure(ex)}
}


trait AsyncMonixFileChannel extends AutoCloseable{
  def size(): Long
  def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null])
  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null])
  def close()
}

case object NotInitializedMonixFileChannel extends AsyncMonixFileChannel {
  def size(): Long = 0
  def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = ()
  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = ()
  def close() = ()
}

object AsyncMonixFileChannel {
  def apply(afc: AsynchronousFileChannel): AsyncMonixFileChannel = AsyncMonixFileChannelSupport(afc)
  def openUnsafe(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit
  ) =
    try {
      AsyncMonixFileChannelSupport(
        AsynchronousFileChannel.open(path, options.asJava, service.orNull))
    } catch {
      case NonFatal(exc) =>
        onOpenError(exc)
        NotInitializedMonixFileChannel
    }

  def openRead(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit = _ => ()
  ) = openUnsafe(path, options + StandardOpenOption.READ, service, onOpenError)

  def openWrite(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit = _ => ()
  ) = openUnsafe(path, options + StandardOpenOption.WRITE + StandardOpenOption.CREATE, service, onOpenError)

}

case class AsyncMonixFileChannelSupport(asyncFileChannel: AsynchronousFileChannel) extends AsyncMonixFileChannel{
  override def size() = asyncFileChannel.size()
  def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.read(dst, position, attachment, handler)

  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.write(b, position,  attachment, handler)

  def close() = {
    asyncFileChannel.close()
  }

}

abstract class AsyncReadChannel(val channel: AsyncMonixFileChannel) extends AsyncChannel {
  //protected val channelFlags = StandardOpenOption.READ :: flags

  protected def readChannel (dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    channel.readChannel(dst, position, attachment, handler)

}

abstract class AsyncWriterChannel(val channel: AsyncMonixFileChannel) extends AsyncChannel {

  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    channel.write(b, position,  attachment, handler)
}