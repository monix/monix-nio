package monix.io.file

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.eval.Callback
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Scheduler}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.AssignableCancelable
import monix.io.cancelables.CallbackCancelable
import monix.io.file.internal.AsyncWriterChannel
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.{Future, Promise}

class AsyncFileWriterConsumer(
  path: Path,
  flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE),
  executorService: Option[ExecutorService] = None) extends Consumer[Array[Byte], Long]{

  override def createSubscriber(
    cb: Callback[Long],
    s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {

    class AsyncFileSubscriber extends AsyncWriterChannel(path, flags, executorService) with  Subscriber[Array[Byte]] {
      implicit val scheduler = s

      private[this] var position = 0
      final private val callbackCalled = Atomic(false)

      private def sendError(t: Throwable) =
        if (callbackCalled.compareAndSet(false, true))
          s.execute(new Runnable {def run() = cb.onError(t)})

      def onComplete(): Unit = {
        closeChannel()
        if (callbackCalled.compareAndSet(false, true))
          cb.onSuccess(position)
      }

      def onError(ex: Throwable): Unit = {
        closeChannel()
        sendError(ex)
      }

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        val promise = Promise[Ack]()

        write(ByteBuffer.wrap(elem), position, null, new CompletionHandler[Integer, Null] {
          def failed(exc: Throwable, attachment: Null) = {
            //We have an ERROR we STOP the consumer
            closeChannel()
            sendError(exc)
            promise.success(Stop)//stop input
          }

          override def completed(result: Integer, attachment: Null): Unit = {
            position +=result.toInt
            promise.success(Continue)
          }})

        promise.future
      }

      def onCancel() = {
        callbackCalled.set(true) //the callback should not be called after cancel
        closeChannel()
      }

      override protected def onOpenError(t: Throwable): Unit = sendError(t)
    }

    val out = new AsyncFileSubscriber()
    val conn = CallbackCancelable(out.onCancel)
    (out,conn)
  }
}
