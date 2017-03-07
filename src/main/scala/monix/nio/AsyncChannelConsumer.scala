package monix.nio

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
import scala.util.control.NonFatal

abstract class AsyncChannelConsumer[T <: AsyncMonixChannel] extends Consumer[Array[Byte], Long] {
  protected def channel: Option[T]
  protected def withInitialPosition: Long = 0l
  def init(): Future[Unit] = Future.successful(())

  override def createSubscriber(cb: Callback[Long], s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {
    class AsyncChannelSubscriber extends Subscriber[Array[Byte]] {
      implicit val scheduler = s

      private[this] lazy val initFuture = init()
      private[this] val callbackCalled = Atomic(false)
      private[this] var position = withInitialPosition

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        def write(): Future[Ack] = {
          val promise = Promise[Ack]()
          channel.foreach { sc =>
            try {
              sc.write(ByteBuffer.wrap(elem), position, new Callback[Int] {
                override def onError(exc: Throwable) = {
                  closeChannel()
                  sendError(exc)
                  promise.success(Stop)
                }

                override def onSuccess(result: Int): Unit = {
                  position += result
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

        if (initFuture.value.isEmpty) {
          initFuture.flatMap(_ => write())
        }
        else {
          write()
        }
      }

      override def onComplete(): Unit = {
        channel.collect { case sc if sc.closeOnComplete => closeChannel()}
        if (callbackCalled.compareAndSet(expect = false, update = true))
          cb.onSuccess(position)
      }

      override def onError(ex: Throwable): Unit = {
        closeChannel()
        sendError(ex)
      }


      protected[nio] def onCancel(): Unit = {
        callbackCalled.set(true) // the callback should not be called after cancel
        channel.collect { case sc if sc.closeOnComplete => closeChannel()}
      }

      protected def sendError(t: Throwable) = if (callbackCalled.compareAndSet(expect = false, update = true))
        scheduler.execute(new Runnable { def run() = cb.onError(t)})


      private[this] val channelOpen = Atomic(true)
      protected def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
        try {
          val open = channelOpen.getAndSet(false)
          if (open) channel.foreach(_.close())
        }
        catch {
          case NonFatal(ex) => reporter.reportFailure(ex)
        }
      }
    }

    val out = new AsyncChannelSubscriber()

    val cancelable = SingleFunctionCallCancelable(out.onCancel)
    val conn = SingleAssignmentCancelable.plusOne(cancelable)
    (out, conn)
  }
}

