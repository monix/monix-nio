package monix.nio

import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

import monix.nio.file.internal.AsyncMonixFileChannel

package object file {
  def readAsync(path: Path, chunkSize: Int, executorService: Option[ExecutorService] = None): AsyncFileReaderObservable ={
    val channel = AsyncMonixFileChannel.openRead(path, Set.empty, executorService)
    new AsyncFileReaderObservable(channel, chunkSize)
  }

  def writeAsync(path: Path, flags: Seq[StandardOpenOption] = Seq.empty, executorService: Option[ExecutorService] = None): AsyncFileWriterConsumer = {
    val channel = AsyncMonixFileChannel.openWrite(path, flags.toSet, executorService)
    new AsyncFileWriterConsumer(channel)
  }

}
