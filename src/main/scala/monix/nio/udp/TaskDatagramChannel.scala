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

package monix.nio.udp

import java.net.{ InetSocketAddress, NetworkInterface, ProtocolFamily }
import java.nio.ByteBuffer

import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.FiniteDuration

/**
  * A `Task` based asynchronous channel for reading, writing, and manipulating an UDP socket.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/7/docs/api/java/nio/channels/DatagramChannel.html java.nio.channels.DatagramChannel]]
  *
  * @example {{{
  *   val ch = TaskDatagramChannel()
  *   ch
  *     .send(Packet("Hello world!".getBytes("UTF-8")), new InetSocketAddress("localhost", 2115)))
  *     .map { len =>
  *       println(len)
  *       ch.close()
  *     }
  *     .runAsync
  * }}}
  * @define bindDesc Binds the channel's socket to a local address
  * @define localDesc the local address to bind the socket, or null to bind to an automatically assigned socket address
  * @define localAddressDesc Asks the socket address that this channel's socket is bound to
  * @define receiveDesc Receives a [[monix.nio.udp.Packet packet]] via this channel.
  *         If a datagram is not available after the timeout then this method returns [[scala.None]].
  * @define maxSize if the value is smaller than the size required to hold the datagram,
  *         then the remainder of the datagram is silently discarded.
  * @define receiveReturnDesc the [[monix.nio.udp.Packet packet]], or [[scala.None]] if no datagram was available before timeout
  * @define sendDesc Sends a datagram via this channel
  * @define sendSrcDesc the [[monix.nio.udp.Packet packet]] to be sent
  * @define sendReturnDesc the number of bytes sent. May be zero if there was insufficient room for the datagram
  *         in the underlying output buffer
  */
abstract class TaskDatagramChannel {

  /** $asyncSocketChannelDesc */
  protected val asyncDatagramChannel: AsyncDatagramChannel

  /**
    * $bindDesc
    *
    * @param local $localDesc
    */
  def bind(local: InetSocketAddress): Task[Unit] = Task {
    asyncDatagramChannel.bind(local)
  }

  /** $localAddressDesc */
  def localAddress(): Task[Option[InetSocketAddress]] =
    Task.now(asyncDatagramChannel.localAddress())

  /**
    * $receiveDesc
    *
    * @param maxSize $maxSize
    * @param timeout the receive timeout
    *
    * @return $receiveReturnDesc
    */
  def receive(maxSize: Int, timeout: FiniteDuration): Task[Option[Packet]] = Task {
    asyncDatagramChannel.receive(maxSize, timeout)
  }

  /**
    * $sendDesc
    *
    * @param packet $sendSrcDesc
    */
  def send(packet: Packet): Task[Int] = Task {
    asyncDatagramChannel.send(packet)
  }

  /** $closeDesc */
  def close(): Task[Unit] =
    Task.now(asyncDatagramChannel.close())
}

object TaskDatagramChannel {
  /**
    * Opens an UDP channel
    *
    * @param reuseAddress       [[java.net.ServerSocket#setReuseAddress]]
    * @param sendBufferSize     [[java.net.Socket#setSendBufferSize]]
    * @param receiveBufferSize  [[java.net.Socket#setReceiveBufferSize]]
    * @param allowBroadcast     [[java.net.DatagramSocket#setBroadcast]]
    * @param protocolFamily     the parameter is used to specify the [[java.net.ProtocolFamily]].
    *                           If the datagram channel is to be used for IP multicasting then this should correspond
    *                           to the address type of the multicast groups that this channel will join.
    * @param multicastInterface [[java.net.MulticastSocket#setInterface]]
    * @param multicastTTL       [[java.net.MulticastSocket#setTimeToLive]]
    * @param multicastLoopback  [[java.net.MulticastSocket#setLoopbackMode]]
    *
    * @param s                 is the `Scheduler` used for asynchronous computations
    *
    * @return an [[monix.nio.udp.TaskDatagramChannel TaskDatagramChannel]] instance for handling receives and sends.
    */
  def apply(
    reuseAddress: Boolean = true,
    sendBufferSize: Option[Int] = None,
    receiveBufferSize: Option[Int] = None,
    allowBroadcast: Boolean = true,
    protocolFamily: Option[ProtocolFamily] = None,
    multicastInterface: Option[NetworkInterface] = None,
    multicastTTL: Option[Int] = None,
    multicastLoopback: Boolean = true
  )(implicit s: Scheduler): TaskDatagramChannel = {

    new TaskDatagramChannel {
      override val asyncDatagramChannel =
        AsyncDatagramChannel(reuseAddress, sendBufferSize, receiveBufferSize, allowBroadcast, protocolFamily, multicastInterface, multicastTTL, multicastLoopback)
    }
  }
}
