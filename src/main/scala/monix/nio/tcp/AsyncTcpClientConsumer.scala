package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

import monix.eval.Callback
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Scheduler, UncaughtExceptionReporter}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.{AssignableCancelable, SingleAssignmentCancelable}
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class AsyncTcpClientConsumer private[tcp] (
  host: String,
  port: Int,
  timeout: FiniteDuration = 60.seconds) extends Consumer[Array[Byte], Long] {

  private[this] var socketClient: Option[SocketClient] = None

  private[tcp] def this(client: SocketClient, timeout: FiniteDuration) {
    this(client.to.getHostString, client.to.getPort, timeout)
    this.socketClient = Option(client)
  }

  override def createSubscriber(cb: Callback[Long], s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {
    class AsyncTcpSubscriber extends Subscriber[Array[Byte]] { self =>
      implicit val scheduler = s

      private[this] val callbackCalled = Atomic(false)
      private[this] var written = 0L

      private[this] val connectedSignal = new CountDownLatch(1)
      private[this] val connectCallback = new Callback[Void]() {
        override def onSuccess(value: Void): Unit = {
          connectedSignal.countDown()
        }
        override def onError(ex: Throwable): Unit = {
          connectedSignal.countDown()
          closeChannel()
          self.onError(ex)
        }
      }

      def init() = {
        if (socketClient.isDefined) {
          connectedSignal.countDown()
        }
        else {
          socketClient = Option(SocketClient(new InetSocketAddress(host, port), onOpenError = self.onError))
          socketClient.foreach(_.connect(connectCallback))
        }
      }

      def onCancel(): Unit = {
        callbackCalled.set(true) // the callback should not be called after cancel
        socketClient.collect { case sc if sc.closeOnComplete => closeChannel()}
      }

      override def onComplete(): Unit = {
        socketClient.collect { case sc if sc.closeOnComplete => closeChannel()}
        if (callbackCalled.compareAndSet(expect = false, update = true))
          cb.onSuccess(written)
      }

      override def onError(ex: Throwable): Unit = {
        closeChannel()
        sendError(ex)
      }

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        val promise = Promise[Ack]()
        connectedSignal.await(timeout.length, timeout.unit)

        socketClient.foreach { sc =>
          try {
            sc.writeChannel(ByteBuffer.wrap(elem), new Callback[Int] {
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
          }
          catch {
            case NonFatal(ex) =>
              sendError(ex)
              promise.success(Stop)
          }
        }

        promise.future
      }

      private def sendError(t: Throwable) =
        if (callbackCalled.compareAndSet(expect = false, update = true))
          s.execute(new Runnable {def run() = cb.onError(t)})

      private def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
        socketClient.foreach(_.closeChannel())
      }
    }

    val out = new AsyncTcpSubscriber()
    out.init()

    val cancelable = SingleFunctionCallCancelable(out.onCancel)
    val conn = SingleAssignmentCancelable.plusOne(cancelable)
    (out, conn)
  }
}
