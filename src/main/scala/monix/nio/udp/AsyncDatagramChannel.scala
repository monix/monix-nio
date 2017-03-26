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

import java.net._
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import monix.execution.{ Cancelable, Scheduler }

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Either
import scala.util.control.NonFatal

/**
  * An asynchronous channel for reading, writing, and manipulating an UDP socket.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/7/docs/api/java/nio/channels/DatagramChannel.html java.nio.channels.DatagramChannel]]
  *
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
abstract class AsyncDatagramChannel extends AutoCloseable {

  /**
    * $bindDesc
    *
    * @param local $localDesc
    */
  def bind(local: InetSocketAddress): Unit

  /** $localAddressDesc */
  def localAddress(): Option[InetSocketAddress]

  /**
    * $receiveDesc
    *
    * @param maxSize $maxSize
    * @param timeout the receive timeout
    *
    * @return $receiveReturnDesc
    */
  def receive(maxSize: Int, timeout: FiniteDuration): Option[Packet]

  /**
    * $sendDesc
    *
    * @param packet $sendSrcDesc
    */
  def send(packet: Packet): Int
}

object AsyncDatagramChannel {
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
    * @return an [[monix.nio.udp.AsyncDatagramChannel AsyncDatagramChannel]] instance for handling receives and sends.
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
  )(implicit s: Scheduler): AsyncDatagramChannel = {

    NewIOImplementation(reuseAddress, sendBufferSize, receiveBufferSize, allowBroadcast, protocolFamily, multicastInterface, multicastTTL, multicastLoopback)
  }

  private[udp] final case class NewIOImplementation(
      reuseAddress: Boolean = true,
      sendBufferSize: Option[Int] = None,
      receiveBufferSize: Option[Int] = None,
      allowBroadcast: Boolean = true,
      protocolFamily: Option[ProtocolFamily] = None,
      multicastInterface: Option[NetworkInterface] = None,
      multicastTTL: Option[Int] = None,
      multicastLoopback: Boolean = true
  )(implicit scheduler: Scheduler) extends AsyncDatagramChannel {

    private[this] lazy val asyncDatagramChannel: Either[Throwable, DatagramChannel] =
      try {
        val ch = protocolFamily.map(DatagramChannel.open).getOrElse(DatagramChannel.open())

        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        sendBufferSize.foreach(sz => ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sz))
        receiveBufferSize.foreach(sz => ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, sz))
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_BROADCAST, allowBroadcast)
        multicastInterface.foreach(n_if => ch.setOption[NetworkInterface](StandardSocketOptions.IP_MULTICAST_IF, n_if))
        multicastTTL.foreach(ttl => ch.setOption[Integer](StandardSocketOptions.IP_MULTICAST_TTL, ttl))
        ch.setOption[java.lang.Boolean](StandardSocketOptions.IP_MULTICAST_LOOP, multicastLoopback)

        Right(ch)
      } catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          Left(exc)
      }

    override def bind(local: InetSocketAddress): Unit = {
      asyncDatagramChannel.fold(_ => (), c => try c.bind(local) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }

    override def localAddress(): Option[InetSocketAddress] = {
      asyncDatagramChannel.fold(_ => None, c => try Option(c.getLocalAddress).map(_.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def receive(maxSize: Int, timeout: FiniteDuration): Option[Packet] = {
      asyncDatagramChannel.fold(_ => None, c => try {
        val dp = new DatagramPacket(new Array[Byte](maxSize), maxSize)
        c.socket().setSoTimeout(timeout.toMillis.toInt)
        c.socket().receive(dp)
        Option(dp.getSocketAddress).map(o => Packet(dp.getData.take(dp.getLength), o.asInstanceOf[InetSocketAddress]))
      } catch {
        case _: SocketTimeoutException =>
          None
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    override def send(packet: Packet): Int = {
      asyncDatagramChannel.fold(_ => 0, c => try c.send(ByteBuffer.wrap(packet.data), packet.to) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          0
      })
    }

    private[this] val cancelable: Cancelable = Cancelable { () =>
      asyncDatagramChannel.fold(_ => (), c => try c.close() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }
    override def close(): Unit =
      cancelable.cancel()
  }
}
