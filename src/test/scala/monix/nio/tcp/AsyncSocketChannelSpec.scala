package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import minitest.SimpleTestSuite

import scala.util.{ Failure, Success }

class AsyncSocketChannelSpec extends SimpleTestSuite {

  test("simple write test") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val asyncSocketChannel = AsyncSocketChannel()
    val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))

    val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
    val writeF = connectF.flatMap(_ => asyncSocketChannel.write(bytes, None))

    writeF.onComplete {
      case Success(nr) =>
        println(f"Bytes written: $nr%d")

      case Failure(exc) =>
        println(s"ERR: $exc")
    }
  }

  test("simple read test") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val asyncSocketChannel = AsyncSocketChannel()
    val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))

    val buff = ByteBuffer.allocate(1024)
    val writeF = connectF.flatMap(_ => asyncSocketChannel.read(buff, None))

    writeF.onComplete {
      case Success(nr) =>
        println(f"Bytes read: $nr%d")

      case Failure(exc) =>
        println(s"ERR: $exc")
    }
  }
}
