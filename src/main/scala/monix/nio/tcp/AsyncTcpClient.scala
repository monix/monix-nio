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

import monix.eval.{Callback, Task}

import scala.util.control.NonFatal

class AsyncTcpClient private (
  host: String, port: Int,
  onOpenError: Throwable => Unit,
  bufferSize: Int)(implicit ec: scala.concurrent.ExecutionContext) {

  private[this] val underlyingSocketClient = SocketClient(
    new java.net.InetSocketAddress(host, port),
    onOpenError = onOpenError,
    closeWhenDone = false)
  private[this] val connectedSignal = scala.concurrent.Promise[Unit]()
  private[this] val connectCallback = new Callback[Void]() {
    override def onSuccess(value: Void): Unit = {
      connectedSignal.success(())
    }
    override def onError(ex: Throwable): Unit = {
      connectedSignal.failure(ex)
      onOpenError(ex)
    }
  }

  private[this] lazy val asyncTcpClientObservable =
    new AsyncTcpClientObservable(underlyingSocketClient, bufferSize)
  private[this] lazy val asyncTcpClientConsumer =
    new AsyncTcpClientConsumer(underlyingSocketClient)

  /**
    * The TCP client reader.
    * It is the one responsible to close the connection
    * when used together with a writer ([[monix.nio.tcp.AsyncTcpClientConsumer]]),
    * by using a [[monix.reactive.observers.Subscriber]]
    * and signal [[monix.execution.Ack.Stop]] or cancel it
    */
  def tcpObservable: Task[AsyncTcpClientObservable] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientObservable)
  }

  /**
    * The TCP client writer
    */
  def tcpConsumer: Task[AsyncTcpClientConsumer] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientConsumer)
  }

  private def init(): Unit =
    try {
      underlyingSocketClient.connect(connectCallback)
    }
    catch {
      case NonFatal(ex) => onOpenError(ex)
    }
}

object AsyncTcpClient {

  def tcpReader(host: String, port: Int, bufferSize: Int = 256 * 1024) =
    new AsyncTcpClientObservable(host, port, bufferSize)

  def tcpWriter(host: String, port: Int) =
    new AsyncTcpClientConsumer(host, port)

  def apply(
    host: String, port: Int,
    onOpenError: Throwable => Unit,
    bufferSize: Int = 256 * 1024)(implicit ec: scala.concurrent.ExecutionContext): AsyncTcpClient = {

    val asyncTcpClient = new AsyncTcpClient(host, port, onOpenError, bufferSize)
    asyncTcpClient.init()

    asyncTcpClient
  }
}
