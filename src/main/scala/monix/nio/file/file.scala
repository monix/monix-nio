/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.nio

import java.nio.file.{ Path, StandardOpenOption }
import java.util.concurrent.ExecutorService

import monix.nio.file.internal.AsyncMonixFileChannel

package object file {
  def readAsync(path: Path, chunkSize: Int, executorService: Option[ExecutorService] = None, onOpenError: Throwable => Unit = _ => ()): AsyncFileReaderObservable = {
    require(chunkSize > 1)
    val channel = AsyncMonixFileChannel.openRead(path, Set.empty, executorService, onOpenError)
    new AsyncFileReaderObservable(channel, chunkSize)
  }

  def writeAsync(path: Path, flags: Seq[StandardOpenOption] = Seq.empty, executorService: Option[ExecutorService] = None, onOpenError: Throwable => Unit = _ => ()): AsyncFileWriterConsumer = {
    appendAsync(path, 0, flags, executorService, onOpenError)
  }

  def appendAsync(path: Path, startPosition: Long, flags: Seq[StandardOpenOption] = Seq.empty, executorService: Option[ExecutorService] = None, onOpenError: Throwable => Unit = _ => ()): AsyncFileWriterConsumer = {
    val channel = AsyncMonixFileChannel.openWrite(path, flags.toSet, executorService, onOpenError)
    new AsyncFileWriterConsumer(channel, startPosition)
  }

}
