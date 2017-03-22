package monix.nio

import java.nio.file.WatchEvent

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.util.control.NonFatal

abstract class WatchServiceObservable extends Observable[WatchEvent[_]] {
  def watchService: Option[WatchService]

  private[this] val wasSubscribed = Atomic(false)
  override def unsafeSubscribeFn(subscriber: Subscriber[WatchEvent[_]]): Cancelable = {
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    } else try startPolling(subscriber) catch {
      case NonFatal(e) =>
        subscriber.onError(e)
        Cancelable.empty
    }
  }

  def init(subscriber: Subscriber[WatchEvent[_]]): Future[Unit] =
    Future.successful(())

  private def startPolling(subscriber: Subscriber[WatchEvent[_]]): Cancelable = {
    import subscriber.scheduler

    val taskCallback = new Callback[WatchEvent[_]]() {
      override def onSuccess(value: WatchEvent[_]): Unit = {}
      override def onError(ex: Throwable): Unit = {
        subscriber.onError(ex)
      }
    }
    val cancelable = Task
      .fromFuture(init(subscriber))
      .flatMap { _ =>
        loop(subscriber)
      }
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(taskCallback)

    val extraCancelable = Cancelable(() => {
      cancelable.cancel()
    })
    SingleAssignmentCancelable.plusOne(extraCancelable)
  }

  private def loop(subscriber: Subscriber[WatchEvent[_]])(implicit scheduler: Scheduler): Task[WatchEvent[_]] = {
    import collection.JavaConverters._
    watchService.map { ws =>
      ws.take().map { key =>
        key.pollEvents().asScala foreach { event =>
          Task.fromFuture(subscriber.onNext(event)).flatMap {
            case Continue => loop(subscriber)
            case Stop => emptyTask
          }
        }
        key.reset()
      }
      emptyTask
    }
  }.getOrElse(emptyTask)

  private val emptyTask: Task[WatchEvent[_]] = Task.create[WatchEvent[_]]((_, _) => Cancelable.empty)
}
