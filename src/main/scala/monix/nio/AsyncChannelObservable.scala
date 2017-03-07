package monix.nio

import java.nio.ByteBuffer

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Cancelable, UncaughtExceptionReporter}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.util.control.NonFatal

abstract protected[nio] class AsyncChannelObservable[T <: AsyncMonixChannel] extends Observable[Array[Byte]] {

  protected def bufferSize: Int

  protected def channel: Option[T]

  protected def init(subscriber: Subscriber[Array[Byte]]): Future[Unit] =
    Future.successful(())


  private[this] val wasSubscribed = Atomic(false)
  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    }
    else try startReading(subscriber) catch {
      case NonFatal(e) =>
        subscriber.onError(e)
        closeChannel()
        Cancelable.empty
    }
  }

  private def startReading(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    val taskCallback = new Callback[Array[Byte]]() {
      override def onSuccess(value: Array[Byte]): Unit = {
        closeChannel()
      }
      override def onError(ex: Throwable): Unit = {
        closeChannel()
        subscriber.onError(ex)
      }
    }

    val cancelable = Task
      .fromFuture(init(subscriber))
      .flatMap { _ =>
        loop(subscriber, 0)
      }
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(taskCallback)

    val singleFunctionCallCancelable = SingleFunctionCallCancelable(() => {
      cancelable.cancel()
      closeChannel()
    })
    SingleAssignmentCancelable.plusOne(singleFunctionCallCancelable)
  }

  private def createReadTask(buff: ByteBuffer, position: Long) =
    Task.async[Int] { (scheduler, callback) =>
      try {
        channel.foreach(_.read(buff, position, callback))
      } catch {
        case NonFatal(ex) => callback.onError(ex)
      }
      Cancelable(() => closeChannel()(scheduler))
    }

  private[this] val buffer = ByteBuffer.allocate(bufferSize)
  private def loop(
    subscriber: Subscriber[Array[Byte]],
    position: Long)(implicit rep: UncaughtExceptionReporter): Task[Array[Byte]] = {

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


  private[this] val channelOpen = Atomic(true)
  protected def closeChannel()(implicit reporter: UncaughtExceptionReporter) =
    try {
      val open = channelOpen.getAndSet(false)
      if (open) channel.foreach(_.close())
    }
    catch {
      case NonFatal(ex) => reporter.reportFailure(ex)
    }
}
