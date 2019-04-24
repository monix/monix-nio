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

package monix.nio.file

import java.nio.ByteBuffer

import monix.eval.Task
import monix.execution.{Callback, Scheduler}
import monix.execution.atomic.Atomic
import monix.nio.AsyncChannel

class FileChannelForTesting(
  readingSeq: Vector[Array[Byte]],
  writeSeq: Atomic[Vector[Array[Byte]]])(implicit s: Scheduler) extends TaskFileChannel with AsyncChannel {

  private val asyncFileChannelForTesting =
    new AsyncFileChannelForTesting(readingSeq, writeSeq)

  override val closeOnComplete: Boolean = true
  override val asyncFileChannel: AsyncFileChannel = asyncFileChannelForTesting

  def isClosed = asyncFileChannelForTesting.isClosed
  def getBytesReadPosition = asyncFileChannelForTesting.getBytesReadPosition
  def getBytesWritePosition = asyncFileChannelForTesting.getBytesWritePosition
  def createReadException() = asyncFileChannelForTesting.createReadException()
  def createWriteException() = asyncFileChannelForTesting.createWriteException()

  private final class AsyncFileChannelForTesting(
    readingSeq: Vector[Array[Byte]],
    writeSeq: Atomic[Vector[Array[Byte]]])(implicit s: Scheduler) extends AsyncFileChannel {

    private val readChannelPosition = Atomic(0)
    private val writeChannelPosition = Atomic(0)
    private val channelClosed = Atomic(false)
    private val readException = Atomic(false)
    private val writeException = Atomic(false)

    def isClosed = channelClosed.get
    def getBytesReadPosition = readChannelPosition.get
    def getBytesWritePosition = writeChannelPosition.get
    def createReadException() = readException.set(true)
    def createWriteException() = writeException.set(true)

    def taskCallback(handler: Callback[Throwable, Int]) = new Callback[Throwable, Array[Byte]]() {
      override def onSuccess(value: Array[Byte]): Unit = handler.onSuccess(value.length)
      override def onError(ex: Throwable): Unit = handler.onError(ex)
    }

    override def isOpen: Boolean = !isClosed
    override def flush(writeMetaData: Boolean, cb: Callback[Throwable, Unit]): Unit = ???
    override def size(cb: Callback[Throwable, Long]): Unit = () //not really used
    override def read(dst: ByteBuffer, position: Long, handler: Callback[Throwable, Int]) = {
      if (readException.get) handler.onError(new Exception("Test Exception"))
      else if (readChannelPosition.get < readingSeq.size) {
        val pos = readChannelPosition.getAndIncrement()

        val r = Task {
          val elem = readingSeq(pos)
          dst.put(elem)
          elem
        }
        r.runAsync(taskCallback(handler))
      } else {
        handler.onSuccess(-1)
      }

    }
    override def write(b: ByteBuffer, position: Long, handler: Callback[Throwable, Int]) = {
      if (writeException.get) handler.onError(new Exception("Test Exception"))
      else {
        val pos = writeChannelPosition.getAndIncrement()
        val r = Task {
          val bytes = b.array()
          writeSeq.transform { v =>
            if (v.size > pos) v.updated(pos, bytes)
            else v :+ bytes
          }
          bytes
        }
        r.runAsync(taskCallback(handler))
      }
    }
    override def close() = {
      channelClosed.set(true)
    }
  }
}
