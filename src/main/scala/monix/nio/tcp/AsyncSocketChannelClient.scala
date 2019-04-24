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

import monix.eval.Task
import monix.execution.Scheduler

/**
  * A TCP client composed of an async reader([[monix.nio.tcp.AsyncSocketChannelObservable AsyncSocketChannelObservable]])
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
  *
  * @define tcpObservableDesc Returns the underlying TCP client [[monix.nio.tcp.AsyncSocketChannelObservable reader]]
  * @define tcpConsumerDesc Returns the underlying TCP client [[monix.nio.tcp.AsyncSocketChannelConsumer writer]]
  * @define stopReadingDesc Indicates that this channel will not read more data
  *         - end-of-stream indication
  * @define stopWritingDesc Indicates that this channel will not write more data
  *         - end-of-stream indication
  * @define closeDesc Closes this channel
  */
final class AsyncSocketChannelClient(
  host: String,
  port: Int,
  bufferSize: Int)(implicit scheduler: Scheduler) {

  private var taskSocketChannel: Option[TaskSocketChannel] = None
  private def this(tsc: TaskSocketChannel, bufferSize: Int)(implicit scheduler: Scheduler) {
    this("", 0, bufferSize)
    this.taskSocketChannel = Option(tsc)
  }

  private[this] val connectedSignal = scala.concurrent.Promise[Unit]()
  def init(): Unit =
    if (taskSocketChannel.isDefined) {
      connectedSignal.success(())
    } else {
      taskSocketChannel = Some(TaskSocketChannel())
      taskSocketChannel
        .get
        .connect(new java.net.InetSocketAddress(host, port))
        .map(connectedSignal.success)
        .onErrorHandle { t =>
          scheduler.reportFailure(t)
          connectedSignal.failure(t)
        }
        .runToFuture
    }

  private[this] lazy val asyncTcpClientObservable =
    new AsyncSocketChannelObservable(taskSocketChannel.get, bufferSize, closeWhenDone = false)
  /** $tcpObservableDesc */
  def tcpObservable: Task[AsyncSocketChannelObservable] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientObservable)
  }

  private[this] lazy val asyncTcpClientConsumer =
    new AsyncSocketChannelConsumer(taskSocketChannel.get, closeWhenDone = false)
  /** $tcpConsumerDesc */
  def tcpConsumer: Task[AsyncSocketChannelConsumer] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientConsumer)
  }

  /** $stopReadingDesc */
  def stopReading() =
    taskSocketChannel.fold(Task.pure(()))(_.stopReading())

  /** $stopWritingDesc */
  def stopWriting() =
    taskSocketChannel.fold(Task.pure(()))(_.stopWriting())

  /** $closeDesc */
  def close(): Task[Unit] =
    taskSocketChannel.fold(Task.pure(()))(_.close())
}

object AsyncSocketChannelClient {

  def apply(
    host: String,
    port: Int,
    bufferSize: Int)(implicit scheduler: Scheduler): AsyncSocketChannelClient = {

    val client = new AsyncSocketChannelClient(host, port, bufferSize)
    client.init()
    client
  }

  def apply(
    taskSocketChannel: TaskSocketChannel,
    bufferSize: Int)(implicit scheduler: Scheduler): AsyncSocketChannelClient = {

    val client = new AsyncSocketChannelClient(taskSocketChannel, bufferSize)
    client.init()
    client
  }
}
