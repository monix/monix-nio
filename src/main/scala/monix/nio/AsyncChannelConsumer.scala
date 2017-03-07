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
  def init(subscriber: AsyncChannelSubscriber): Future[Unit] = Future.successful(())

  class AsyncChannelSubscriber(consumerCallback: Callback[Long])(implicit val scheduler: Scheduler)
    extends Subscriber[Array[Byte]] { self =>

    private[this] lazy val initFuture = init(self)
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
        consumerCallback.onSuccess(position)
    }

    override def onError(ex: Throwable): Unit = {
      closeChannel()
      sendError(ex)
    }


    protected[nio] def onCancel(): Unit = {
      callbackCalled.set(true) // the callback should not be called after cancel
      channel.collect { case sc if sc.closeOnComplete => closeChannel()}
    }

    protected[nio] def sendError(t: Throwable) = if (callbackCalled.compareAndSet(expect = false, update = true))
      scheduler.execute(new Runnable { def run() = consumerCallback.onError(t)})


    private[this] val channelOpen = Atomic(true)
    protected[nio] def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
      try {
        val open = channelOpen.getAndSet(false)
        if (open) channel.foreach(_.close())
      }
      catch {
        case NonFatal(ex) => reporter.reportFailure(ex)
      }
    }
  }

  override def createSubscriber(cb: Callback[Long], s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {
    val out = new AsyncChannelSubscriber(cb)(s)

    val cancelable = SingleFunctionCallCancelable(out.onCancel)
    val conn = SingleAssignmentCancelable.plusOne(cancelable)
    (out, conn)
  }
}

