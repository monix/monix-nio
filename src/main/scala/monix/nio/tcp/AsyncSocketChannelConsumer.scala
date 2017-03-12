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
import monix.nio.AsyncChannelConsumer

import scala.concurrent.Promise

/**
  * A TCP socket [[monix.reactive.Consumer Consumer]] that can be used
  * to send data asynchronously from an [[monix.reactive.Observable Observable]].
  * The underlying socket will be closed when the
  * [[monix.reactive.Observable Observable]] ends
  *
  * @param host hostname
  * @param port TCP port number
  */
final class AsyncSocketChannelConsumer private[tcp] (host: String, port: Int) extends AsyncChannelConsumer {
  private[this] var asyncSocketChannel: Option[AsyncSocketChannel] = None
  private[this] var closeOnComplete = true

  private[tcp] def this(asc: AsyncSocketChannel, closeWhenDone: Boolean) {
    this("", 0)
    this.asyncSocketChannel = Option(asc)
    this.closeOnComplete = closeWhenDone
  }

  override lazy val channel = asyncSocketChannel.map(asc => asyncChannelWrapper(asc, closeOnComplete))

  override def init(subscriber: AsyncChannelSubscriber) = {
    import subscriber.scheduler

    val connectedPromise = Promise[Unit]()
    if (asyncSocketChannel.isDefined) {
      connectedPromise.success(())
    } else {
      val connectCallback = new Callback[Unit]() {
        override def onSuccess(value: Unit): Unit = {
          connectedPromise.success(())
        }
        override def onError(ex: Throwable): Unit = {
          connectedPromise.failure(ex)
          subscriber.closeChannel()
          subscriber.onError(ex)
        }
      }
      asyncSocketChannel = Option(AsyncSocketChannel())
      asyncSocketChannel.foreach(_.connect(new InetSocketAddress(host, port), connectCallback))
    }

    connectedPromise.future
  }
}
