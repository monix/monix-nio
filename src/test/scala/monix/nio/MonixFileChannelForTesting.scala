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

import monix.eval.{Callback, Task}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic

class MonixFileChannelForTesting(readingSeq: Vector[Array[Byte]], writeSeq: Atomic[Vector[Array[Byte]]])(implicit s: Scheduler) extends AsyncMonixChannel {
  private val readChannelPosition = Atomic(0)
  private val writeChannelPosition = Atomic(0)
  private val channelClosed = Atomic(false)
  private val readException = Atomic(false)
  private val writeException = Atomic(false)

  def isClosed = channelClosed.get
  def getBytesReadPosition = readChannelPosition.get
  def getBytesWritePosition = writeChannelPosition.get

  def taskCallback(handler: Callback[Int]) = new Callback[Array[Byte]]() {
    override def onSuccess(value: Array[Byte]): Unit = handler.onSuccess(value.length)
    override def onError(ex: Throwable): Unit = handler.onError(ex)
  }

  def createReadException() = readException.set(true)
  def createWriteException() = writeException.set(true)

  def size(): Long = 0 //not really used
  def read(dst: ByteBuffer, position: Long, handler: Callback[Int]) = {
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
  def write(b: ByteBuffer, position: Long, handler: Callback[Int]) = {
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
  def close() = {
    channelClosed.set(true)
  }
}
