package monix.nio.file

import java.nio.file.WatchKey

import monix.eval.Task
import monix.nio.WatchServiceObservable

import scala.concurrent.duration.TimeUnit

final class AsyncWatchServiceObservable(taskWatchService: TaskWatchService) extends WatchServiceObservable {
  override def watchService = Option {
    asyncWatchServiceWrapper(taskWatchService)
  }

  private[file] def asyncWatchServiceWrapper(taskWatchService: TaskWatchService) = new monix.nio.WatchService {
    override def poll(timeout: Long, timeUnit: TimeUnit): Task[Option[WatchKey]] = taskWatchService.poll(timeout, timeUnit)
    override def poll(): Task[Option[WatchKey]] = taskWatchService.poll()
    override def take(): Task[WatchKey] = taskWatchService.take()
    override def close(): Task[Unit] = taskWatchService.close()
  }
}



