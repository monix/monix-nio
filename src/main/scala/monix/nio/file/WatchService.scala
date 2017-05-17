package monix.nio.file

import java.nio.file.StandardWatchEventKinds.{ ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY }
import java.nio.file.WatchEvent.Kind
import java.nio.file.{ Path, WatchEvent, WatchKey }

import com.sun.nio.file.SensitivityWatchEventModifier
import monix.eval.Callback
import monix.execution.{ Cancelable, Scheduler }

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.TimeUnit
import scala.util.control.NonFatal

/**
  * A watch service that watches registered objects for changes and events. For example a file manager may use a watch service
  * to monitor a directory for changes so that it can update its display of the list of files when files are created or deleted.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html java.nio.file.WatchService]]
  * (class available since Java 7 for registering objects for changes and events).
  *
  */
abstract class WatchService extends AutoCloseable {
  def poll(timeout: Long, timeUnit: TimeUnit, cb: Callback[Option[WatchKey]]): Unit

  def poll(timeout: Long, timeUnit: TimeUnit): Future[Option[WatchKey]] = {
    val p = Promise[Option[WatchKey]]()
    poll(timeout, timeUnit, Callback.fromPromise(p))
    p.future
  }

  def poll(cb: Callback[Option[WatchKey]]): Unit

  def poll(): Future[Option[WatchKey]] = {
    val p = Promise[Option[WatchKey]]()
    poll(Callback.fromPromise(p))
    p.future
  }

  def take(cb: Callback[WatchKey]): Unit

  def take(): Future[WatchKey] = {
    val p = Promise[WatchKey]()
    take(Callback.fromPromise(p))
    p.future
  }
}

object WatchService {
  val SupportedEvents: Set[Kind[_]] = Set(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)

  def apply(path: Path, events: Kind[_]*)(implicit scheduler: Scheduler): WatchService = {
    val watcher = path.getFileSystem.newWatchService()
    val watchFor = if (events.isEmpty) SupportedEvents else events

    path.register(
      watcher,
      watchFor.toArray,
      SensitivityWatchEventModifier.HIGH.asInstanceOf[WatchEvent.Modifier]
    )

    new NIOWatcherServiceImplementation(watcher)
  }

  private final class NIOWatcherServiceImplementation(watcher: java.nio.file.WatchService)(implicit scheduler: Scheduler) extends WatchService {
    override def poll(timeout: Long, timeUnit: TimeUnit, cb: Callback[Option[WatchKey]]): Unit = {
      try {
        val key = Option(watcher.poll(timeout, timeUnit))
        cb.onSuccess(key)
      } catch {
        case NonFatal(ex) =>
          cb.onError(ex)
      }
    }

    override def poll(cb: Callback[Option[WatchKey]]): Unit = {
      try {
        val key = Option(watcher.poll())
        cb.onSuccess(key)
      } catch {
        case NonFatal(ex) =>
          cb.onError(ex)
      }
    }

    override def take(cb: Callback[WatchKey]): Unit = {
      try {
        val key = watcher.take()
        cb.onSuccess(key)
      } catch {
        case NonFatal(ex) =>
          cb.onError(ex)
      }
    }

    override def close(): Unit = cancelable.cancel()

    private[this] val cancelable: Cancelable =
      Cancelable { () =>
        try watcher.close() catch {
          case NonFatal(ex) => scheduler.reportFailure(ex)
        }
      }
  }
}
