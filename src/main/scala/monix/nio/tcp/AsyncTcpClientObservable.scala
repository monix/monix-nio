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

import java.net.InetSocketAddress

import monix.eval.Callback
import monix.nio._
import monix.reactive.observers.Subscriber

import scala.concurrent.Promise

final class AsyncTcpClientObservable private[tcp] (
  host: String, port: Int,
  buffSize: Int = 256 * 1024) extends AsyncChannelObservable[SocketClient] {

  override def bufferSize = buffSize

  private[this] val connectedSignal = Promise[Unit]()
  private[this] var socketClient: Option[SocketClient] = None

  private[tcp] def this(client: SocketClient, buffSize: Int) {
    this(client.to.getHostString, client.to.getPort, buffSize)
    this.socketClient = Option(client)
  }

  override protected def channel = socketClient

  override def init(subscriber: Subscriber[Array[Byte]]) = {
    import subscriber.scheduler
    if (socketClient.isDefined) {
      connectedSignal.success(())
    }
    else {
      socketClient = Option(SocketClient(new InetSocketAddress(host, port), onOpenError = subscriber.onError))
      val connectCallback = new Callback[Void]() {
        override def onSuccess(value: Void): Unit = {
          connectedSignal.success(())
        }
        override def onError(ex: Throwable): Unit = {
          connectedSignal.failure(ex)
          closeChannel()
          subscriber.onError(ex)
        }
      }

      socketClient.foreach(_.connect(connectCallback))
    }

    connectedSignal.future
  }
}
