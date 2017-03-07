package monix.nio

import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.nio.file.internal.AsyncFileChannel

package object file {
  def readAsync(
    path: Path,
    chunkSize: Int,
    executorService: Option[ExecutorService] = None,
    onOpenError: Throwable => Unit = _ =>()): AsyncFileReaderObservable = {

    require(chunkSize > 1)
    val channel = AsyncFileChannel.openRead(path, Set.empty, executorService, onOpenError)
    new AsyncFileReaderObservable(channel, chunkSize)
  }

  def writeAsync(
    path: Path,
    flags: Seq[StandardOpenOption] = Seq.empty,
    executorService: Option[ExecutorService] = None,
    onOpenError: Throwable => Unit = _ =>()): AsyncFileWriterConsumer = {

    appendAsync(path, 0, flags, executorService, onOpenError)
  }

  def appendAsync(
    path: Path,
    startPosition: Long,
    flags: Seq[StandardOpenOption] = Seq.empty,
    executorService: Option[ExecutorService] = None,
    onOpenError: Throwable => Unit = _ =>()): AsyncFileWriterConsumer = {

    val channel = AsyncFileChannel.openWrite(path, flags.toSet, executorService, onOpenError)
    new AsyncFileWriterConsumer(channel, startPosition)
  }
}
