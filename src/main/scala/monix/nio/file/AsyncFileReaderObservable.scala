package monix.nio.file

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Cancelable, UncaughtExceptionReporter}
import monix.execution.atomic.Atomic
import monix.nio.cancelables.CallbackCancelable
import monix.nio.file.internal._
import monix.reactive.Observable
import monix.reactive.exceptions.MultipleSubscribersException
import monix.reactive.observers.Subscriber

import scala.util.control.NonFatal

class AsyncFileReaderObservable(channel: AsyncMonixFileChannel, size: Int)
  extends AsyncReadChannel(channel) with Observable[Array[Byte]]{
  private[this] val wasSubscribed = Atomic(false)
  private[this] val buffer = ByteBuffer.allocate(size)

  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(MultipleSubscribersException(this.getClass.getName))
      Cancelable.empty
    }
    else {
      try {
        val taskCallback = new Callback[Array[Byte]]() {
          override def onSuccess(value: Array[Byte]): Unit = {
            closeChannel()
          }

          override def onError(ex: Throwable): Unit = {
            closeChannel()
            subscriber.onError(ex)
          }
        }

        val c = Task.defer(loop(subscriber, 0))
          .executeWithOptions(_.enableAutoCancelableRunLoops)
          .runAsync(taskCallback)
        CallbackCancelable(() => {
          closeChannel()
          c.cancel()
        })
      }
      catch {
        case NonFatal(e) =>
          subscriber.onError(e)
          closeChannel()
          Cancelable.empty
      }
    }
  }

  def createReadTask(buff: ByteBuffer, position: Long): Task[Integer] = {
    Task.create { (scheduler, callback) =>
      val handler = new CompletionHandler[Integer, Null] {
        override def completed(result: Integer, attachment: Null): Unit =
          callback.onSuccess(result)
        override def failed(exc: Throwable, attachment: Null): Unit =
          callback.onError(exc)
      }

      try {
        readChannel(buff, position, null, handler)
      } catch {
        case NonFatal(ex) => callback.onError(ex)
      }
      Cancelable(() => closeChannel()(scheduler))
    }
  }

  def loop(
    subscriber: Subscriber[Array[Byte]],
    position: Long)
    (implicit rep: UncaughtExceptionReporter): Task[Array[Byte]] = {

    buffer.clear()
    createReadTask(buffer, position).flatMap { result =>
      val bytes = Bytes(buffer, result)
      bytes match {
        case EmptyBytes =>
          subscriber.onComplete()
          Task.now(Array.empty)

        case NonEmptyBytes(arr) =>
          Task.fromFuture(subscriber.onNext(arr)).flatMap {
            case Continue =>
              loop(subscriber, position + result)

            case Stop =>
              Task.now(Array.empty)
          }
      }

    }
  }

  // This method could be called inside the try catch block
  // where readChannel is called, so it only needs to ensure
  // the propagation of the Throwable
  override protected def onOpenError(t: Throwable): Unit = throw t
}