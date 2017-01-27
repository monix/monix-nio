package monix.io

import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.ExecutorService

package object file {
  def readAsync(path: Path, chunkSize: Int, executorService: Option[ExecutorService] = None): AsyncFileReaderObservable =
    new AsyncFileReaderObservable(path, chunkSize, List.empty, executorService)

  def writeAsync(path: Path, flags: Seq[StandardOpenOption] = Seq.empty, executorService: Option[ExecutorService] = None): AsyncFileWriterConsumer =
    new AsyncFileWriterConsumer(path, flags, executorService)

}
