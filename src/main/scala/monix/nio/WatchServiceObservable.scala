package monix.nio

import java.nio.file.WatchEvent

import monix.eval.Task
import monix.execution.Ack.{ Continue, Stop }
import monix.execution.atomic.Atomic
import monix.execution.cancelables.SingleAssignCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.execution.{ Callback, Cancelable, Scheduler }
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.util.control.NonFatal

abstract class WatchServiceObservable extends Observable[Array[WatchEvent[_]]] {
  def watchService: Option[WatchService]

  private[this] val wasSubscribed = Atomic(false)
  override def unsafeSubscribeFn(subscriber: Subscriber[Array[WatchEvent[_]]]): Cancelable = {
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    } else try startPolling(subscriber) catch {
      case NonFatal(e) =>
        subscriber.onError(e)
        Cancelable.empty
    }
  }

  def init(subscriber: Subscriber[Array[WatchEvent[_]]]): Future[Unit] =
    Future.successful(())

  private def startPolling(subscriber: Subscriber[Array[WatchEvent[_]]]): Cancelable = {
    import subscriber.scheduler

    val taskCallback = new Callback[Throwable, Array[WatchEvent[_]]]() {
      override def onSuccess(value: Array[WatchEvent[_]]): Unit = {}
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
    SingleAssignCancelable.plusOne(extraCancelable)
  }

  private def loop(subscriber: Subscriber[Array[WatchEvent[_]]])(implicit scheduler: Scheduler): Task[Array[WatchEvent[_]]] = {
    import scala.jdk.CollectionConverters._
    watchService.map { ws =>
      ws.take()
        .doOnCancel(Task.defer(ws.close()))
        .flatMap { key =>
          val events = key.pollEvents().asScala.toArray
          key.reset()
          Task.fromFuture(subscriber.onNext(events)).flatMap {
            case Continue => loop(subscriber)
            case Stop => emptyTask
          }
        }
    }
  }.getOrElse(emptyTask)

  private val emptyTask = Task.create[Array[WatchEvent[_]]]((_, _) => Cancelable.empty)
}
