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
import java.nio.file.WatchEvent.Kind
import java.nio.file.{ Path, StandardOpenOption }

import monix.execution.Scheduler

package object file {
  def readAsync(path: Path, chunkSize: Int)(implicit s: Scheduler): AsyncFileChannelObservable = {
    require(chunkSize > 1)

    val channel = TaskFileChannel(path, StandardOpenOption.READ)
    new AsyncFileChannelObservable(channel, chunkSize)
  }

  def writeAsync(
    path: Path,
    flags: Seq[StandardOpenOption] = Seq.empty)(implicit s: Scheduler): AsyncFileChannelConsumer = {

    appendAsync(path, 0, flags)
  }

  def appendAsync(
    path: Path,
    startPosition: Long,
    flags: Seq[StandardOpenOption] = Seq.empty)(implicit s: Scheduler): AsyncFileChannelConsumer = {

    val flagsWithWriteOptions = flags :+ StandardOpenOption.WRITE :+ StandardOpenOption.CREATE
    val channel = TaskFileChannel(path, flagsWithWriteOptions: _*)
    new AsyncFileChannelConsumer(channel, startPosition)
  }

  def watchAsync(
    path: Path,
    events: Seq[Kind[_]] = Seq.empty)(implicit s: Scheduler): WatchServiceObservable = {
    val watcher = TaskWatchService(path, events: _*)
    new AsyncWatchServiceObservable(watcher)
  }

  private[file] def asyncChannelWrapper(taskFileChannel: TaskFileChannel) = new AsyncChannel {
    override val closeOnComplete: Boolean = true

    override def read(dst: ByteBuffer, position: Long) = taskFileChannel.read(dst, position)
    override def write(b: ByteBuffer, position: Long) = taskFileChannel.write(b, position)
    override def close() = taskFileChannel.close()
  }
}
