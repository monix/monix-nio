package monix.nio

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.nio.file.{Files, Paths}

import com.typesafe.scalalogging.LazyLogging
import minitest.TestSuite
import monix.eval.{Callback, Task}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import monix.execution.schedulers.TestScheduler
import monix.nio.file.{AsyncFileReaderObservable, AsyncFileWriterConsumer}
import monix.nio.file.internal.AsyncMonixFileChannel

import scala.util.control.NonFatal

object TestCancelConsumer extends TestSuite[TestScheduler] with LazyLogging{
  def setup(): TestScheduler = {

    TestScheduler()
  }
  def tearDown(s: TestScheduler): Unit = {
    assert(s.state.tasks.isEmpty,
      "TestScheduler should have no pending tasks")
  }
  class TestMonixFileChannel(readingSeq: Vector[Array[Byte]], writeSeq: Atomic[Vector[Array[Byte]]])(implicit s: Scheduler) extends AsyncMonixFileChannel {
    private val readChannelPosition = Atomic(0)
    private val writeChannelPosition = Atomic(0)
    private val channelClosed = Atomic(false)

    def isClosed = channelClosed.get
    def getBytesReadPosition() = readChannelPosition.get
    def getBytesWritePosition() = writeChannelPosition.get

    def taskCallback(handler: CompletionHandler[Integer, Null]) = new Callback[Array[Byte]]() {
      override def onSuccess(value: Array[Byte]): Unit = handler.completed(value.length, null)
      override def onError(ex: Throwable): Unit = handler.failed(ex, null)
    }

    def size(): Long = 0
    def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = {
      val pos = readChannelPosition.getAndIncrement()
      logger.info(s"readChannelPosition = ${readChannelPosition.get}")
      val r = Task {
        logger.info(s"readChannelPosition task")
        val elem = readingSeq(pos)
        dst.put(elem)
        elem
      }

      r.runAsync(taskCallback(handler))

    }
    def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = {
      val pos = writeChannelPosition.getAndIncrement()
      val r = Task {
        logger.info(s"XXX write task")
        val bytes = b.array()
        writeSeq.transform{ v =>
          if (v.size > pos) v.updated(pos, bytes)
          else v :+ bytes
        }
        bytes
      }
      logger.info(s"XXX write ${new String(b.array())}")
      r.runAsync(taskCallback(handler))
    }
    def close() = {
      channelClosed.set(true)
    }
  }


  test ("cancel a consumer") { implicit s =>
    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)

    val chunkSize = 3
    val readBytes = Files.readAllBytes(from).take(20).grouped(chunkSize).toVector
    val readChannel = new TestMonixFileChannel(readBytes, null)

    val writeTo = Atomic(Vector.empty[Array[Byte]])
    val writeChannel = new TestMonixFileChannel(null, writeTo)

    val reader = new AsyncFileReaderObservable(readChannel, chunkSize)
    val consumer = new AsyncFileWriterConsumer(writeChannel)

    val cancelable = reader.consumeWith(consumer).runAsync

    s.tickOne() //observable defer runnable
    s.tickOne() //read
    s.tickOne() //async read task execution and write
    //s.tickOne() //async write
    //s.tickOne() //observable continue
    assertEquals(readChannel.getBytesReadPosition, 1)
    assertEquals(writeChannel.getBytesWritePosition, 1)
    assertEquals(writeTo.get.size, 1)

    //s.tickOne() //observable defer runnable
    //s.tickOne() //read
    s.tickOne() //async read task execution and write
    s.tickOne() //async write
    s.tickOne() //observable continue
    //check 2 reads have occurred
    assert(writeTo.get.size==2)
    //cancel the consumer
    cancelable.cancel()
    s.tickOne()

    //assert(s.state.tasks.isEmpty, "TestScheduler should have no pending tasks")
    //no other reads should occurre
    s.tick()
    assertEquals(readChannel.getBytesReadPosition, 2)
    assertEquals(writeChannel.getBytesWritePosition, 2)
    assertEquals(writeTo.get.size, 2)
    assert(writeChannel.isClosed)
    assert(readChannel.isClosed)
    //check no other read has occurred

  }
}
