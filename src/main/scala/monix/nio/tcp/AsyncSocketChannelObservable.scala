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

/**
 * A TCP socket [[monix.reactive.Observable Observable]] that can be subscribed to
 * in order to read the incoming bytes asynchronously.
 * The underlying socket is closed on `end-of-stream`, on signalling [[monix.execution.Ack.Stop Stop]]
 * after subscription or by cancelling it directly
 *
 * @param host hostname
 * @param port TCP port number
 * @param bufferSize the size of the buffer used for reading
 */
final class AsyncSocketChannelObservable private[tcp] (
    host: String, port: Int,
    override val bufferSize: Int = 256 * 1024
) extends AsyncChannelObservable {

  private[this] val connectedSignal = Promise[Unit]()
  private[this] var asyncSocketChannel: Option[AsyncSocketChannel] = None

  private[tcp] def this(asc: AsyncSocketChannel, buffSize: Int) {
    this("", 0, buffSize)
    this.asyncSocketChannel = Option(asc)
  }

  override lazy val channel = asyncSocketChannel.map(asc => asyncChannelWrapper(asc, closeWhenDone = true))

  override def init(subscriber: Subscriber[Array[Byte]]) = {
    import subscriber.scheduler

    if (asyncSocketChannel.isDefined) {
      connectedSignal.success(())
    } else {
      val connectCallback = new Callback[Unit]() {
        override def onSuccess(value: Unit): Unit = {
          connectedSignal.success(())
        }
        override def onError(ex: Throwable): Unit = {
          connectedSignal.failure(ex)
          closeChannel()
          subscriber.onError(ex)
        }
      }
      asyncSocketChannel = Option(AsyncSocketChannel())
      asyncSocketChannel.foreach(_.connect(new InetSocketAddress(host, port), connectCallback))
    }

    connectedSignal.future
  }
}
