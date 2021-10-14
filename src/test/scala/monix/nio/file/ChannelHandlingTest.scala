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

import java.nio.file.{ Files, Paths }

import minitest.TestSuite
import monix.execution.Callback
import monix.execution.atomic.Atomic
import monix.execution.schedulers.TestScheduler

object ChannelHandlingTest extends TestSuite[TestScheduler] {
  def setup(): TestScheduler = TestScheduler()

  def tearDown(s: TestScheduler): Unit = {
    assert(
      s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }

  //we need these ticks for a complete run of an elem
  private val ticksPerElem = 3

  def tick(n: Int)(implicit s: TestScheduler) = (1 to n) map (_ => s.tickOne())

  test("full parse") { implicit s =>
    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)

    val chunkSize = 2
    val readBytes = Files.readAllBytes(from).grouped(chunkSize).toVector
    val readChannel = new FileChannelForTesting(readBytes, null)

    val writeTo = Atomic(Vector.empty[Array[Byte]])
    val writeChannel = new FileChannelForTesting(null, writeTo)

    val reader = new AsyncFileChannelObservable(readChannel, chunkSize)
    val consumer = new AsyncFileChannelConsumer(writeChannel)

    reader.consumeWith(consumer).runToFuture
    s.tick()
    assert(readChannel.isClosed)
    assert(writeChannel.isClosed)
    assertEquals(writeTo.get().flatten, readBytes.flatten)
    assertEquals(readChannel.getBytesReadPosition, writeChannel.getBytesWritePosition)
  }

  test("cancel a consumer") { implicit s =>
    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)

    val chunkSize = 17
    val readBytes = Files.readAllBytes(from).take(20).grouped(chunkSize).toVector
    val readChannel = new FileChannelForTesting(readBytes, null)

    val writeTo = Atomic(Vector.empty[Array[Byte]])
    val writeChannel = new FileChannelForTesting(null, writeTo)

    val reader = new AsyncFileChannelObservable(readChannel, chunkSize)
    val consumer = new AsyncFileChannelConsumer(writeChannel)

    val cancelable = reader.consumeWith(consumer).runToFuture

    tick(ticksPerElem)
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assertEquals(writeTo.get().size, 1)

    tick(ticksPerElem)

    //check 2 reads have occurred
    assert(writeTo.get().size == 2)
    //cancel the consumer
    cancelable.cancel()
    s.tickOne()

    //assert(s.state.tasks.isEmpty, "TestScheduler should have no pending tasks")
    //no other reads should occur
    s.tick()
    assertEquals(readChannel.getBytesReadPosition, 2)
    assertEquals(writeChannel.getBytesWritePosition, 2)
    assertEquals(writeTo.get().size, 2)
    assert(readChannel.isClosed)
    assert(writeChannel.isClosed)
    //check no other read has occurred

  }

  test("error on read is handled") { implicit s =>
    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)

    val chunkSize = 3
    val readBytes = Files.readAllBytes(from).take(20).grouped(chunkSize).toVector
    val readChannel = new FileChannelForTesting(readBytes, null)

    val writeTo = Atomic(Vector.empty[Array[Byte]])
    val writeChannel = new FileChannelForTesting(null, writeTo)

    val reader = new AsyncFileChannelObservable(readChannel, chunkSize)
    val consumer = new AsyncFileChannelConsumer(writeChannel)

    val callbackErrorCalled = Atomic(false)
    val callback = new Callback[Throwable, Long] {
      override def onSuccess(value: Long): Unit = ()

      override def onError(ex: Throwable): Unit = callbackErrorCalled.set(true)
    }
    reader.consumeWith(consumer).runAsync(callback)

    tick(ticksPerElem)
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assertEquals(writeTo.get().size, 1)
    assertEquals(callbackErrorCalled.get(), false)

    //next read will create an exception
    readChannel.createReadException()
    tick(ticksPerElem)
    assertEquals(callbackErrorCalled.get(), true)
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assert(readChannel.isClosed)
    assert(writeChannel.isClosed)
    s.tick()
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
  }

  test("error on write is handled") { implicit s =>
    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)

    val chunkSize = 3
    val readBytes = Files.readAllBytes(from).take(20).grouped(chunkSize).toVector
    val readChannel = new FileChannelForTesting(readBytes, null)

    val writeTo = Atomic(Vector.empty[Array[Byte]])
    val writeChannel = new FileChannelForTesting(null, writeTo)

    val reader = new AsyncFileChannelObservable(readChannel, chunkSize)
    val consumer = new AsyncFileChannelConsumer(writeChannel)

    val callbackErrorCalled = Atomic(false)
    val callback = new Callback[Throwable, Long] {
      override def onSuccess(value: Long): Unit = ()
      override def onError(ex: Throwable): Unit = callbackErrorCalled.set(true)
    }
    reader.consumeWith(consumer).runAsync(callback)

    tick(ticksPerElem)
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assertEquals(writeTo.get().size, 1)
    assertEquals(callbackErrorCalled.get(), false)

    //next write will create an exception
    writeChannel.createWriteException()
    tick(ticksPerElem)
    tick(ticksPerElem)
    assertEquals(callbackErrorCalled.get(), true)
    assertEquals(readChannel.getBytesReadPosition, 2)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assert(readChannel.isClosed)
    assert(writeChannel.isClosed)
    s.tick()
    assertEquals(readChannel.getBytesReadPosition, 2)
    assertEquals(writeChannel.getBytesWritePosition, 1)
  }
}
