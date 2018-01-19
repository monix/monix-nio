package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import monix.eval.{ Callback, Task }
import monix.execution.Scheduler

import scala.concurrent.duration.Duration

/**
  * A `Task` based asynchronous channel for reading, writing, and manipulating a TCP socket.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousSocketChannel.html java.nio.channels.AsynchronousSocketChannel]]
  * (class available since Java 7 for doing async I/O on sockets).
  *
  * @example {{{
  *   val taskSocketChannel = TaskSocketChannel()
  *   val writeT =
  *     for {
  *       _ <- taskSocketChannel.connect(new InetSocketAddress("google.com", 80))
  *       written <- taskSocketChannel.write(ByteBuffer.wrap("Hello world!".getBytes("UTF-8")), None)
  *     } yield {
  *       written
  *     }
  *
  *   writeT.runAsync(new Callback[Int] {
  *     override def onSuccess(value: Int): Unit = println(f"Bytes written: $value%d")
  *     override def onError(ex: Throwable): Unit = println(s"ERR: $ex")
  *   })
  * }}}
  * @define asyncSocketChannelDesc The underlying [[monix.nio.tcp.AsyncSocketChannel AsyncSocketChannel]]
  * @define connectDesc Connects this channel.
  * @define remoteDesc the remote address to which this channel is to be connected
  * @define localAddressDesc Asks the socket address that this channel's socket is bound to
  * @define remoteAddressDesc Asks the remote address to which this channel's socket is connected
  * @define readDesc Reads a sequence of bytes from this channel into the given buffer
  * @define readDestDesc is the buffer holding the bytes read on completion
  * @define readReturnDesc the number of bytes read or -1 if no bytes could be read
  *         because the channel has reached end-of-stream
  * @define writeDesc Writes a sequence of bytes to this channel from the given buffer
  * @define writeSrcDesc is the buffer holding the sequence of bytes to write
  * @define writeReturnDesc the number of bytes that were written
  * @define timeout an optional maximum time for the I/O operation to complete
  * @define stopReadingDesc Indicates that this channel will not read more data
  *         - end-of-stream indication
  * @define stopWritingDesc Indicates that this channel will not write more data
  *         - end-of-stream indication
  * @define closeDesc Closes this channel
  */
abstract class TaskSocketChannel {

  /** $asyncSocketChannelDesc */
  protected val asyncSocketChannel: AsyncSocketChannel

  /**
    * $connectDesc
    *
    * @param remote $remoteDesc
    */
  def connect(remote: InetSocketAddress): Task[Unit] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      asyncSocketChannel.connect(remote, cb)
    }

  /** $localAddressDesc */
  def localAddress(): Task[Option[InetSocketAddress]] =
    Task.now(asyncSocketChannel.localAddress())

  /**
    * $remoteAddressDesc
    */
  def remoteAddress(): Task[Option[InetSocketAddress]] =
    Task.now(asyncSocketChannel.remoteAddress())

  /**
    * $readDesc
    *
    * @param dst $readDestDesc
    * @param timeout $timeout
    *
    * @return $readReturnDesc
    */
  def read(dst: ByteBuffer, timeout: Option[Duration] = None): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      asyncSocketChannel.read(dst, cb, timeout)
    }

  /**
    * $writeDesc
    *
    * @param src $writeSrcDesc
    * @param timeout $timeout
    *
    * @return $writeReturnDesc
    */
  def write(src: ByteBuffer, timeout: Option[Duration] = None): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      asyncSocketChannel.write(src, cb, timeout)
    }

  /** $stopReadingDesc */
  def stopReading(): Task[Unit] =
    Task.now(asyncSocketChannel.stopReading())

  /** $stopWritingDesc */
  def stopWriting(): Task[Unit] =
    Task.now(asyncSocketChannel.stopWriting())

  /** $closeDesc */
  def close(): Task[Unit] =
    Task.now(asyncSocketChannel.close())
}

object TaskSocketChannel {
  /**
    * Opens a socket channel for the given [[java.net.InetSocketAddress]]
    *
    * @param reuseAddress      [[java.net.ServerSocket#setReuseAddress]]
    * @param sendBufferSize    [[java.net.Socket#setSendBufferSize]]
    * @param receiveBufferSize [[java.net.Socket#setReceiveBufferSize]] [[java.net.ServerSocket#setReceiveBufferSize]]
    * @param keepAlive         [[java.net.Socket#setKeepAlive]]
    * @param noDelay           [[java.net.Socket#setTcpNoDelay]]
    *
    * @param s                 is the `Scheduler` used for asynchronous computations
    *
    * @return a [[monix.nio.tcp.TaskSocketChannel TaskSocketChannel]] instance for handling reads and writes.
    */
  def apply(
    reuseAddress: Boolean = true,
    sendBufferSize: Option[Int] = None,
    receiveBufferSize: Option[Int] = None,
    keepAlive: Boolean = false,
    noDelay: Boolean = false)(implicit s: Scheduler): TaskSocketChannel = {

    new TaskSocketChannel {
      override val asyncSocketChannel =
        AsyncSocketChannel(reuseAddress, sendBufferSize, receiveBufferSize, keepAlive, noDelay)
    }
  }
}
