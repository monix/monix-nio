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

import monix.eval.{ Callback, Task }
import monix.execution.Scheduler

/**
 * A TCP client composed of an async reader([[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]])
 * and an async writer([[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]) pair
 * that both are using the same underlying socket.
 * The reader will be the one in charge of closing the underlying socket by
 * signalling [[monix.execution.Ack.Stop Stop]] after subscription or by cancelling it directly.
 * In case `end-of-stream` is received the socket is closed automatically.
 *
 * @param host hostname
 * @param port TCP port number
 * @param bufferSize the size of the buffer used for reading
 *
 * @return an [[monix.nio.tcp.AsyncSocketChannelClient AsyncSocketChannelClient]]
 */
final case class AsyncSocketChannelClient(
    host: String,
    port: Int,
    bufferSize: Int
)(implicit scheduler: Scheduler) {
  private[this] val underlyingAsyncSocketClient = AsyncSocketChannel()

  private[this] lazy val asyncTcpClientObservable =
    new AsyncSocketChannelObservable(underlyingAsyncSocketClient, bufferSize)
  private[this] lazy val asyncTcpClientConsumer =
    new AsyncSocketChannelConsumer(underlyingAsyncSocketClient, closeWhenDone = false)

  private[this] val connectedSignal = scala.concurrent.Promise[Unit]()
  private[this] val connectCallback = new Callback[Unit]() {
    override def onSuccess(value: Unit): Unit = {
      connectedSignal.success(())
    }
    override def onError(ex: Throwable): Unit = {
      connectedSignal.failure(ex)
      scheduler.reportFailure(ex)
    }
  }
  underlyingAsyncSocketClient.connect(new java.net.InetSocketAddress(host, port), connectCallback)

  /**
   * Returns the underlying TCP client reader.
   * It is the one in charge of closing the underlying socket by
   * signalling [[monix.execution.Ack.Stop Stop]] after subscription or by cancelling it directly,
   * if no `end-of-stream` is received
   * See [[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]]
   */
  def tcpObservable: Task[AsyncSocketChannelObservable] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientObservable)
  }

  /**
   * Returns the underlying TCP client writer.
   * See [[monix.nio.tcp.AsyncSocketChannelConsumer AsyncSocketChannelConsumer]]
   */
  def tcpConsumer: Task[AsyncSocketChannelConsumer] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientConsumer)
  }
}
