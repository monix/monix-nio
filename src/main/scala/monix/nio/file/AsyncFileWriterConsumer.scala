package monix.nio.file

import java.nio.ByteBuffer

import monix.eval.Callback
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Scheduler}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.{AssignableCancelable, SingleAssignmentCancelable}
import monix.nio.AsyncMonixChannel
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.nio.file.internal.{AsyncMonixFileChannel, AsyncWriterChannel}
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.{Future, Promise}

class AsyncFileWriterConsumer(channel: AsyncMonixChannel, startPosition: Long = 0) extends Consumer[Array[Byte], Long]{

  override def createSubscriber(
    cb: Callback[Long],
    s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {

    class AsyncFileSubscriber extends AsyncWriterChannel(channel) with  Subscriber[Array[Byte]] {
      implicit val scheduler = s

      private[this] var position = startPosition
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

        write(ByteBuffer.wrap(elem), position, new Callback[Int]{
          def onError(exc: Throwable) = {
            //We have an ERROR we STOP the consumer
            closeChannel()
            sendError(exc)
            promise.success(Stop)//stop input
          }

          def onSuccess(result: Int): Unit = {
            position +=result.toInt
            promise.success(Continue)
          }})

        promise.future
      }

      def onCancel() = {
        callbackCalled.set(true) //the callback should not be called after cancel
        closeChannel()
      }
    }

    val out = new AsyncFileSubscriber()

    val myCancelable = SingleFunctionCallCancelable(out.onCancel)
    val conn = SingleAssignmentCancelable.plusOne(myCancelable)
    (out,conn)
  }
}
