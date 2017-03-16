package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import minitest.SimpleTestSuite

import scala.concurrent.Await
import scala.concurrent.duration._

object AsyncSocketChannelSpec extends SimpleTestSuite {

  test("simple connect and write test") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val asyncSocketChannel = AsyncSocketChannel()
    val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))

    val data = "Hello world!".getBytes("UTF-8")
    val bytes = ByteBuffer.wrap(data)
    val writeF = connectF
      .flatMap(_ => asyncSocketChannel.write(bytes, Some(4.seconds)))
      .map { result =>
        asyncSocketChannel.stopWriting()
        asyncSocketChannel.close()
        result
      }

    assertEquals(Await.result(writeF, 5.seconds), data.length)
  }

  test("simple connect and read test") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val asyncSocketChannel = AsyncSocketChannel()
    val connectF = asyncSocketChannel.connect(new InetSocketAddress("google.com", 80))

    val buff = ByteBuffer.allocate(0)
    val readF = connectF
      .flatMap(_ => asyncSocketChannel.read(buff, Some(4.seconds)))
      .map { _ =>
        asyncSocketChannel.stopReading()
        asyncSocketChannel.close()
        0
      }

    assertEquals(Await.result(readF, 5.seconds), 0)
  }
}
