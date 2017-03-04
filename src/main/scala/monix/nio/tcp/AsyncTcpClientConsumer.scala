package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import monix.eval.Callback
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Scheduler, UncaughtExceptionReporter}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.{AssignableCancelable, SingleAssignmentCancelable}
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.{Future, Promise}

class AsyncTcpClientConsumer(host: String, port: Int) extends Consumer[Array[Byte], Long] {

  override def createSubscriber(cb: Callback[Long], s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {
    class AsyncTcpSubscriber extends Subscriber[Array[Byte]] { self =>
      implicit val scheduler = s

      private[this] val client: Client = {
        val socket = Client(new InetSocketAddress(host, port), onOpenError = self.onError)
        val connectCallback = new Callback[Void]() {
          override def onSuccess(value: Void): Unit = {}
          override def onError(ex: Throwable): Unit = {
            closeChannel()
            self.onError(ex)
          }
        }

        socket.connect(connectCallback)
        socket
      }
      private[this] val callbackCalled = Atomic(false)
      private[this] var written = 0L


      override def onComplete(): Unit = {
        closeChannel()
        if (callbackCalled.compareAndSet(expect = false, update = true))
          cb.onSuccess(written)
      }

      override def onError(ex: Throwable): Unit = {
        closeChannel()
        sendError(ex)
      }

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        val promise = Promise[Ack]()
        client.writeChannel(ByteBuffer.wrap(elem), new Callback[Int] {
          override def onError(exc: Throwable) = {
            closeChannel()
            sendError(exc)
            promise.success(Stop) // We have an ERROR we STOP the consumer
          }

          override def onSuccess(result: Int): Unit = {
            written += result
            promise.success(Continue)
          }
        })

        promise.future
      }

      private def sendError(t: Throwable) =
        if (callbackCalled.compareAndSet(expect = false, update = true))
          s.execute(new Runnable {def run() = cb.onError(t)})

      private def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
        client.closeChannel()(reporter)
      }

      def onCancel() = {
        callbackCalled.set(true) // the callback should not be called after cancel
        closeChannel()
      }
    }

    val out = new AsyncTcpSubscriber()

    val cancelable = SingleFunctionCallCancelable(out.onCancel)
    val conn = SingleAssignmentCancelable.plusOne(cancelable)
    (out, conn)
  }
}
