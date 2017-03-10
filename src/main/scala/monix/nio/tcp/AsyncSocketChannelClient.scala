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

import scala.util.control.NonFatal

final case class AsyncSocketChannelClient(
    host: String,
    port: Int,
    bufferSize: Int
)(implicit scheduler: Scheduler) {

  private[this] val underlyingAsyncSocketClient = AsyncSocketChannel(
    new java.net.InetSocketAddress(host, port),
    closeWhenDone = false
  )
  private[this] val connectedSignal = scala.concurrent.Promise[Unit]()
  private[this] val connectCallback = new Callback[Void]() {
    override def onSuccess(value: Void): Unit = {
      connectedSignal.success(())
    }
    override def onError(ex: Throwable): Unit = {
      connectedSignal.failure(ex)
      scheduler.reportFailure(ex)
    }
  }

  private[this] lazy val asyncTcpClientObservable =
    new AsyncSocketChannelObservable(underlyingAsyncSocketClient, bufferSize)
  private[this] lazy val asyncTcpClientConsumer =
    new AsyncSocketChannelConsumer(underlyingAsyncSocketClient)

  try {
    underlyingAsyncSocketClient.connect(connectCallback)
  } catch {
    case NonFatal(ex) =>
      scheduler.reportFailure(ex)
  }

  /**
   * The TCP client reader.
   * It is the one responsible for closing the connection
   * when used together with a writer ([[monix.nio.tcp.AsyncSocketChannelConsumer]]),
   * by using a [[monix.reactive.observers.Subscriber]]
   * and signal [[monix.execution.Ack.Stop]] or cancel it
   */
  def tcpObservable: Task[AsyncSocketChannelObservable] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientObservable)
  }

  /**
   * The TCP client writer
   */
  def tcpConsumer: Task[AsyncSocketChannelConsumer] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientConsumer)
  }
}
