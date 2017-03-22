package monix.nio

import java.nio.file.WatchKey
import monix.eval.Task
import scala.concurrent.duration.TimeUnit

private[nio] trait WatchService {
  def poll(timeout: Long, timeUnit: TimeUnit): Task[Option[WatchKey]]
  def poll(): Task[Option[WatchKey]]
  def take(): Task[WatchKey]
  def close(): Task[Unit]
}
