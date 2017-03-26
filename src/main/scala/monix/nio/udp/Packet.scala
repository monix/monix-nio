package monix.nio.udp

import java.net.InetSocketAddress

case class Packet(data: Array[Byte], to: InetSocketAddress)
