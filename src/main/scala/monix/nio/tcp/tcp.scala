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

import monix.eval.Callback
import monix.execution.Scheduler

package object tcp {
  def readAsync(host: String, port: Int, bufferSize: Int = 256 * 1024) =
    new AsyncTcpClientObservable(host, port, bufferSize)

  def writeAsync(host: String, port: Int) =
    new AsyncTcpClientConsumer(host, port)

  def readWriteAsync(
    host: String,
    port: Int,
    bufferSize: Int = 256 * 1024
  )(implicit scheduler: Scheduler): AsyncTcpClient = {

    val asyncTcpClient = AsyncTcpClient(host, port, bufferSize)
    asyncTcpClient.init()

    asyncTcpClient
  }

  private[tcp] def asyncChannelWrapper(asyncSocketChannel: AsyncSocketChannel) = new AsyncChannel {
    override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
      asyncSocketChannel.read(dst, callback)

    override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
      asyncSocketChannel.write(b, callback)

    override def close(): Unit =
      asyncSocketChannel.close()

    override def closeOnComplete(): Boolean = asyncSocketChannel.closeWhenDone
  }
}
