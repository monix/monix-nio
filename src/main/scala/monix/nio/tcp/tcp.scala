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

import monix.execution.Scheduler

package object tcp {
  /**
    * Returns a TCP socket [[monix.reactive.Observable Observable]] that can be subscribed to
    * in order to read the incoming bytes asynchronously.
    * It will close the socket on end-of-stream, signalling [[monix.execution.Ack.Stop Stop]]
    * after subscription or by cancelling it directly
    *
    * @param host hostname
    * @param port TCP port number
    * @param bufferSize the size of the buffer used for reading
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]]
    */
  def readAsync(host: String, port: Int, bufferSize: Int = 256 * 1024) =
    new AsyncSocketChannelObservable(host, port, bufferSize)

  /**
    * Returns a TCP socket [[monix.reactive.Observable Observable]] that can be subscribed to
    * in order to read the incoming bytes asynchronously.
    * It will close the socket on end-of-stream, signalling [[monix.execution.Ack.Stop Stop]]
    * after subscription or by cancelling it directly
    *
    * @param taskSocketChannel the underlying [[monix.nio.tcp.TaskSocketChannel TaskSocketChannel]]
    * @param bufferSize the size of the buffer used for reading
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]]
    */
  def readAsync(taskSocketChannel: TaskSocketChannel, bufferSize: Int) = {
    new AsyncSocketChannelObservable(taskSocketChannel, bufferSize, true)
  }

  /**
    * Returns a TCP socket [[monix.reactive.Consumer Consumer]] that can be used
    * to send data asynchronously from an [[monix.reactive.Observable Observable]].
    * The underlying socket will be closed when the
    * [[monix.reactive.Observable Observable]] ends
    *
    * @param host hostname
    * @param port TCP port number
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]
    */
  def writeAsync(host: String, port: Int) =
    new AsyncSocketChannelConsumer(host, port)

  /**
    * Returns a TCP socket [[monix.reactive.Consumer Consumer]] that can be used
    * to send data asynchronously from an [[monix.reactive.Observable Observable]].
    * The underlying socket will be closed when the
    * [[monix.reactive.Observable Observable]] ends
    *
    * @param taskSocketChannel the underlying [[monix.nio.tcp.TaskSocketChannel TaskSocketChannel]]
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]
    */
  def writeAsync(taskSocketChannel: TaskSocketChannel) =
    new AsyncSocketChannelConsumer(taskSocketChannel, true)

  /**
    * Creates a TCP client - an async reader([[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]])
    * and an async writer([[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]) pair
    * that both use the same underlying socket that is not released automatically.
    *
    * In order to release the connection use [[monix.nio.tcp.AsyncSocketChannelClient#close() close()]]
    *
    * @param host hostname
    * @param port TCP port number
    * @param bufferSize the size of the buffer used for reading
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelClient AsyncSocketChannelClient]]
    */
  def readWriteAsync(
    host: String,
    port: Int,
    bufferSize: Int
  )(implicit scheduler: Scheduler): AsyncSocketChannelClient = {

    AsyncSocketChannelClient(host, port, bufferSize)
  }

  /**
    * Creates a TCP client - an async reader([[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]])
    * and an async writer([[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]) pair
    * that both use the same underlying socket that is not released automatically.
    *
    * In order to release the connection use [[monix.nio.tcp.AsyncSocketChannelClient#close() close()]]
    *
    * @param taskSocketChannel the underlying [[monix.nio.tcp.TaskSocketChannel TaskSocketChannel]]
    * @param bufferSize the size of the buffer used for reading
    *
    * @return an [[monix.nio.tcp.AsyncSocketChannelClient AsyncSocketChannelClient]]
    */
  def readWriteAsync(
    taskSocketChannel: TaskSocketChannel,
    bufferSize: Int = 256 * 1024
  )(implicit scheduler: Scheduler): AsyncSocketChannelClient = {

    AsyncSocketChannelClient(taskSocketChannel, bufferSize)
  }

  /**
    * Creates a TCP server
    *
    * @param host hostname
    * @param port TCP port number
    *
    * @return a bound [[monix.nio.tcp.TaskServerSocketChannel TaskServerSocketChannel]]
    */
  def asyncServer(
    host: String,
    port: Int
  )(implicit scheduler: Scheduler) = {

    val server = TaskServerSocketChannel()
    server
      .bind(new java.net.InetSocketAddress(host, port))
      .map(_ => server)
  }

  private[tcp] def asyncChannelWrapper(taskSocketChannel: TaskSocketChannel, closeWhenDone: Boolean) =
    new AsyncChannel {
      override val closeOnComplete = closeWhenDone

      override def read(dst: ByteBuffer, position: Long) = taskSocketChannel.read(dst)
      override def write(b: ByteBuffer, position: Long) = taskSocketChannel.write(b)
      override def close() = taskSocketChannel.close()
    }
}
