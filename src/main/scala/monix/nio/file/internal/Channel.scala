package monix.nio.file.internal

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.execution.UncaughtExceptionReporter

import collection.JavaConverters._
import scala.util.control.NonFatal

trait AsyncChannel {
  protected def channelFlags: List[StandardOpenOption]
  protected def executorService: Option[ExecutorService]
  protected def onOpenError(t: Throwable)
  protected def path: Path

  // close channel errors are not exposed as the operation is performed
  // under the hood, sometimes async, after the communication with
  // the app has been already closed
  protected def closeChannel()(implicit reporter: UncaughtExceptionReporter) =
    internalChannel.fold (_ => (), c =>
      try {c.close()}
      catch {case NonFatal(ex) => reporter.reportFailure(ex)})

  protected lazy val internalChannel: Either[Throwable, AsynchronousFileChannel] =
    try {
      Right(AsynchronousFileChannel.open(path, channelFlags.toSet.asJava, executorService.orNull))
    } catch {
      case NonFatal(exc) =>
        onOpenError(exc)
        Left(exc)
    }

}

abstract class AsyncReadChannel(
  val path: Path,
  flags: List[StandardOpenOption],
  val executorService: Option[ExecutorService] = None) extends AsyncChannel {
  protected val channelFlags = StandardOpenOption.READ :: flags

  protected def readChannel (dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    internalChannel.fold (_ => (), c => c.read(dst, position, attachment, handler))

}

abstract class AsyncWriterChannel(
  val path: Path,
  flags: Seq[StandardOpenOption],
  val executorService: Option[ExecutorService] = None) extends AsyncChannel {
  protected val channelFlags = StandardOpenOption.WRITE :: flags.toList

  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    internalChannel.fold (_ => (), c => c.write(b, position,  attachment, handler))
}