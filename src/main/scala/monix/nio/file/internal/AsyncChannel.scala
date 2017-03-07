package monix.nio.file.internal

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.eval.Callback
import monix.execution.UncaughtExceptionReporter
import monix.execution.atomic.Atomic
import monix.execution.exceptions.APIContractViolationException
import monix.nio.AsyncMonixChannel

import collection.JavaConverters._
import scala.util.control.NonFatal

private[internal] case object NotInitializedMonixFileChannel extends AsyncMonixChannel {
  def size(): Long = 0
  def close() = ()
  override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
    callback.onError(APIContractViolationException(s"can't read on ${this.getClass.getName}"))
  override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
    callback.onError(APIContractViolationException(s"can't read on ${this.getClass.getName}"))
}

private[file] object AsyncMonixFileChannel {
  def openUnsafe(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit
  ) =
    try {
      AsyncMonixFileChannelWrapper(
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

private[internal] case class AsyncMonixFileChannelWrapper(asyncFileChannel: AsynchronousFileChannel) extends AsyncMonixChannel{
  override def size() = asyncFileChannel.size()
  def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.read(dst, position, attachment, handler)

  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.write(b, position,  attachment, handler)

  def close() = {
    asyncFileChannel.close()
  }

  override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit = {
    val handler = new CompletionHandler[Integer, Null] {
      override def completed(result: Integer, attachment: Null) = callback.onSuccess(result)
      override def failed(exc: Throwable, attachment: Null) = callback.onError(exc)
    }
    asyncFileChannel.read(dst, position, null, handler)
  }

  override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit = {
    val handler = new CompletionHandler[Integer, Null] {
      override def completed(result: Integer, attachment: Null) = callback.onSuccess(result)
      override def failed(exc: Throwable, attachment: Null) = callback.onError(exc)
    }
    asyncFileChannel.write(b, position, null, handler)
  }
}


private[nio] abstract class AsyncWriterChannel(val channel: AsyncMonixChannel) {
  private val channelOpen = Atomic(true)

  // close channel errors are not exposed as the operation is performed
  // under the hood, sometimes async, after the communication with
  // the app has been already closed
  protected def closeChannel()(implicit reporter: UncaughtExceptionReporter) =
  try {
    val open = channelOpen.getAndSet(false)
    if (open) channel.close()
  }
  catch {
    case NonFatal(ex) => reporter.reportFailure(ex)
  }

  protected def write(b: ByteBuffer, position: Long, callback: Callback[Int]) =
    channel.write(b, position,  callback)
}