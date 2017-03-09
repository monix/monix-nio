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

import java.nio.ByteBuffer
import java.nio.file.{ Path, StandardOpenOption }

import monix.eval.Callback
import monix.execution.Scheduler

package object file {
  def readAsync(path: Path, chunkSize: Int)(implicit s: Scheduler): AsyncFileReaderObservable = {
    require(chunkSize > 1)

    val channel = AsyncFileChannel(path.toFile, StandardOpenOption.READ)
    new AsyncFileReaderObservable(channel, chunkSize)
  }

  def writeAsync(
    path: Path,
    flags: Seq[StandardOpenOption] = Seq.empty
  )(implicit s: Scheduler): AsyncFileWriterConsumer = {

    appendAsync(path, 0, flags)
  }

  def appendAsync(
    path: Path,
    startPosition: Long,
    flags: Seq[StandardOpenOption] = Seq.empty
  )(implicit s: Scheduler): AsyncFileWriterConsumer = {

    val flagsWithWriteOptions = flags :+ StandardOpenOption.WRITE :+ StandardOpenOption.CREATE
    val channel = AsyncFileChannel(path.toFile, flagsWithWriteOptions: _*)
    new AsyncFileWriterConsumer(channel, startPosition)
  }

  private[file] def asyncChannelWrapper(asyncFileChannel: AsyncFileChannel) = new AsyncChannel {
    override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
      asyncFileChannel.read(dst, position, callback)

    override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
      asyncFileChannel.write(b, position, callback)

    override def close(): Unit =
      asyncFileChannel.close()

    override def closeOnComplete(): Boolean = true
  }
}
