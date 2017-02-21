package monix.nio

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler

import monix.eval.{Callback, Task}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import monix.nio.file.internal.AsyncMonixFileChannel

class MonixFileChannelForTesting(readingSeq: Vector[Array[Byte]], writeSeq: Atomic[Vector[Array[Byte]]])(implicit s: Scheduler) extends AsyncMonixFileChannel {
  private val readChannelPosition = Atomic(0)
  private val writeChannelPosition = Atomic(0)
  private val channelClosed = Atomic(false)
  private val readException = Atomic(false)
  private val writeException = Atomic(false)

  def isClosed = channelClosed.get
  def getBytesReadPosition = readChannelPosition.get
  def getBytesWritePosition = writeChannelPosition.get

  def taskCallback(handler: CompletionHandler[Integer, Null]) = new Callback[Array[Byte]]() {
    override def onSuccess(value: Array[Byte]): Unit = handler.completed(value.length, null)
    override def onError(ex: Throwable): Unit = handler.failed(ex, null)
  }

  def createReadException() = readException.set(true)
  def createWriteException() = writeException.set(true)

  def size(): Long = 0 //not really used
  def readChannel(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = {
    if (readException.get) handler.failed(new Exception("Test Exception"), null)
    else if (readChannelPosition.get < readingSeq.size) {
      val pos = readChannelPosition.getAndIncrement()

      val r = Task {
        val elem = readingSeq(pos)
        dst.put(elem)
        elem
      }
      r.runAsync(taskCallback(handler))
    } else {
      handler.completed(-1, null)
    }

  }
  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) = {
    if (writeException.get) handler.failed(new Exception("Test Exception"), null)
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
