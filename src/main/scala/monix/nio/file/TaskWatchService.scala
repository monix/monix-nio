package monix.nio.file

import java.nio.file.WatchEvent.Kind
import java.nio.file.{Path, WatchKey}

import monix.eval.Task
import monix.execution.{Callback, Scheduler}

import scala.concurrent.duration.TimeUnit

/**
  * A `Task` based watch service that watches registered objects for changes and events. For example a file manager may use a watch service
  * to monitor a directory for changes so that it can update its display of the list of files when files are created or deleted.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html java.nio.file.WatchService]]
  * (class available since Java 7 for registering objects for changes and events).
  *
  */
abstract class TaskWatchService {

  protected val watchService: WatchService

  def poll(timeout: Long, timeUnit: TimeUnit): Task[Option[WatchKey]] = {
    Task.create { (scheduler, cb) =>
      implicit val s = scheduler
      watchService.poll(timeout, timeUnit, Callback.forked(cb))
    }
  }

  def poll(): Task[Option[WatchKey]] = {
    Task.create { (scheduler, cb) =>
      implicit val s = scheduler
      watchService.poll(Callback.forked(cb))
    }
  }

  def take(): Task[WatchKey] = {
    Task.create { (scheduler, cb) =>
      implicit val s = scheduler
      watchService.take(Callback.forked(cb))
    }
  }

  def close(): Task[Unit] =
    Task.now(watchService.close())
}

object TaskWatchService {
  def apply(path: Path, events: Kind[_]*)(implicit s: Scheduler): TaskWatchService = {
    new TaskWatchService {
      override val watchService: WatchService = WatchService.apply(path, events: _*)
    }
  }
}
