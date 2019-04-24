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

package monix.nio.tcp

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.{Executors, TimeUnit}

import monix.execution.{Callback, Cancelable, Scheduler}

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/**
  * An asynchronous channel for reading, writing, and manipulating a TCP socket.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousSocketChannel.html java.nio.channels.AsynchronousSocketChannel]]
  * (class available since Java 7 for doing async I/O on sockets).
  *
  * @example {{{
  *   val asyncSocketChannel = AsyncSocketChannel()
  *
  *   val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))
  *
  *   val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
  *   val writeF = connectF.flatMap(_ => asyncSocketChannel.write(bytes, None))
  *
  *   writeF.onComplete {
  *     case Success(nr) =>
  *       println(f"Bytes written: $nr%d")
  *
  *    case Failure(exc) =>
  *       println(s"ERR: $exc")
  *   }
  * }}}
  * @define callbackDesc is the callback to be called with the result, once
  *         this asynchronous operation is complete
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
  */
abstract class AsyncSocketChannel extends AutoCloseable {

  /**
    * $connectDesc
    *
    * @param remote $remoteDesc
    * @param cb $callbackDesc
    */
  def connect(remote: InetSocketAddress, cb: Callback[Throwable, Unit]): Unit

  /**
    * $connectDesc
    *
    * @param remote $remoteDesc
    */
  def connect(remote: InetSocketAddress): Future[Unit] = {
    val p = Promise[Unit]()
    connect(remote, Callback.fromPromise(p))
    p.future
  }

  /** $localAddressDesc */
  def localAddress(): Option[InetSocketAddress]

  /** $remoteAddressDesc */
  def remoteAddress(): Option[InetSocketAddress]

  /**
    * $readDesc
    *
    * @param dst $readDestDesc
    * @param cb $callbackDesc . For this method it signals $readReturnDesc
    * @param timeout $timeout
    */
  def read(dst: ByteBuffer, cb: Callback[Throwable, Int], timeout: Option[Duration] = None): Unit

  /**
    * $readDesc
    *
    * @param dst $readDestDesc
    * @param timeout $timeout
    *
    * @return $readReturnDesc
    */
  def read(dst: ByteBuffer, timeout: Option[Duration]): Future[Int] = {
    val p = Promise[Int]()
    read(dst, Callback.fromPromise(p), timeout)
    p.future
  }

  /**
    * $writeDesc
    *
    * @param src $writeSrcDesc
    * @param cb $callbackDesc . For this method it signals $writeReturnDesc
    * @param timeout $timeout
    */
  def write(src: ByteBuffer, cb: Callback[Throwable, Int], timeout: Option[Duration] = None): Unit

  /**
    * $writeDesc
    *
    * @param src $writeSrcDesc
    * @param timeout $timeout
    *
    * @return $writeReturnDesc
    */
  def write(src: ByteBuffer, timeout: Option[Duration]): Future[Int] = {
    val p = Promise[Int]()
    write(src, Callback.fromPromise(p), timeout)
    p.future
  }

  /** $stopReadingDesc */
  def stopReading(): Unit

  /** $stopWritingDesc */
  def stopWriting(): Unit
}

object AsyncSocketChannel {
  /**
    * Opens a TCP socket channel
    *
    * @param reuseAddress      [[java.net.ServerSocket#setReuseAddress]]
    * @param sendBufferSize    [[java.net.Socket#setSendBufferSize]]
    * @param receiveBufferSize [[java.net.Socket#setReceiveBufferSize]] [[java.net.ServerSocket#setReceiveBufferSize]]
    * @param keepAlive         [[java.net.Socket#setKeepAlive]]
    * @param noDelay           [[java.net.Socket#setTcpNoDelay]]
    *
    * @param s                 is the `Scheduler` used for asynchronous computations
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannel AsyncSocketChannel]] instance for handling reads and writes.
    */
  def apply(
    reuseAddress: Boolean = true,
    sendBufferSize: Option[Int] = None,
    receiveBufferSize: Option[Int] = None,
    keepAlive: Boolean = false,
    noDelay: Boolean = false)(implicit s: Scheduler): AsyncSocketChannel = {

    NewIOImplementation(reuseAddress, sendBufferSize, receiveBufferSize, keepAlive, noDelay)
  }

  private lazy val acg =
    AsynchronousChannelGroup.withCachedThreadPool(Executors.newCachedThreadPool(), -1)

  private val connectHandler = new CompletionHandler[Void, Callback[Throwable, Unit]] {
    override def completed(result: Void, cb: Callback[Throwable, Unit]) = cb.onSuccess(())
    override def failed(exc: Throwable, cb: Callback[Throwable, Unit]) = cb.onError(exc)
  }

  private[this] val rwHandler = new CompletionHandler[Integer, Callback[Throwable, Int]] {
    override def completed(result: Integer, cb: Callback[Throwable, Int]): Unit = cb.onSuccess(result)
    override def failed(exc: Throwable, cb: Callback[Throwable, Int]): Unit = cb.onError(exc)
  }

  private[tcp] final case class NewIOImplementation(
    reuseAddress: Boolean = true,
    sendBufferSize: Option[Int] = None,
    receiveBufferSize: Option[Int] = None,
    keepAlive: Boolean = false,
    noDelay: Boolean = false)(implicit scheduler: Scheduler) extends AsyncSocketChannel {

    private[this] var existingAsyncSocketChannelO: Option[AsynchronousSocketChannel] = None

    private[tcp] def this(asyncSocketChannel: AsynchronousSocketChannel)(implicit scheduler: Scheduler) {
      this()
      this.existingAsyncSocketChannelO = Option(asyncSocketChannel)
    }

    private[this] lazy val asyncSocketChannel: Either[Throwable, AsynchronousSocketChannel] =
      existingAsyncSocketChannelO match {
        case Some(asc) =>
          Right(asc)

        case None =>
          try {
            val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(acg)

            ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
            sendBufferSize.foreach(sz => ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sz))
            receiveBufferSize.foreach(sz => ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, sz))
            ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
            ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)

            Right(ch)
          } catch {
            case NonFatal(exc) =>
              scheduler.reportFailure(exc)
              Left(exc)
          }
      }

    override def connect(remote: InetSocketAddress, cb: Callback[Throwable, Unit]): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.connect(remote, cb, connectHandler) catch {
        case NonFatal(exc) =>
          cb.onError(exc)
      })
    }

    override def localAddress(): Option[InetSocketAddress] = {
      asyncSocketChannel.fold(_ => None, c => try Option(c.getLocalAddress).map(_.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def remoteAddress(): Option[InetSocketAddress] = {
      asyncSocketChannel.fold(_ => None, c => try Option(c.getRemoteAddress).map(_.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def read(dst: ByteBuffer, cb: Callback[Throwable, Int], timeout: Option[Duration]): Unit = {
      asyncSocketChannel.fold(_ => (), { c =>
        try {
          c.read(
            dst,
            timeout.map(_.length).getOrElse(0l),
            timeout.map(_.unit).getOrElse(TimeUnit.MILLISECONDS),
            cb,
            rwHandler)
        } catch {
          case NonFatal(exc) =>
            cb.onError(exc)
        }
      })
    }

    override def write(src: ByteBuffer, cb: Callback[Throwable, Int], timeout: Option[Duration]): Unit = {
      asyncSocketChannel.fold(_ => (), { c =>
        try {
          c.write(
            src,
            timeout.map(_.length).getOrElse(0l),
            timeout.map(_.unit).getOrElse(TimeUnit.MILLISECONDS),
            cb,
            rwHandler)
        } catch {
          case NonFatal(exc) =>
            cb.onError(exc)
        }
      })
    }

    override def stopReading(): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.shutdownInput() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }

    override def stopWriting(): Unit = {
      asyncSocketChannel.fold(_ => (), c => try c.shutdownOutput() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }

    private[this] val cancelable: Cancelable = Cancelable { () =>
      asyncSocketChannel.fold(_ => (), c => try c.close() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }
    override def close(): Unit =
      cancelable.cancel()
  }
}
