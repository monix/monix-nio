package monix.nio.tcp

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.reactive.Observable
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class TcpIntegrationSpec extends FlatSpec with Matchers {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  "monix - tcp socket client" should "connect to a TCP (HTTP) source successfully" in {
    val p = Promise[Boolean]()
    val t = Task {
      val tcpObservable = AsyncTcpClient.tcpReader("monix.io", 443)
      tcpObservable.subscribe(
        _ => Stop,
        err => p.failure(err))
    }.map { c =>
      c.cancel()
      p.success(true)
    }
    t.runAsync

    Await.result(p.future, 5.seconds) shouldBe true
  }

  "monix - tcp socket client" should "write to a TCP (HTTP) connection successfully" in {
    val data = Array.fill(8)("monix".getBytes())
    val chunkSize = 2 // very small chunks for testing

    val p = Promise[Boolean]()
    val callback = new Callback[Long] {
      override def onSuccess(value: Long): Unit = p.success(data.flatten.length == value)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    Task {
      val tcpConsumer = AsyncTcpClient.tcpWriter("monix.io", 443)
      Observable
        .fromIterable(data)
        .flatMap(all => Observable.fromIterator(all.grouped(chunkSize)))
        .consumeWith(tcpConsumer)
        .runAsync(callback)
    }.runAsync

    val result = Await.result(p.future, 5.seconds)
    assert(result, true)
  }

  "monix - tcp socket client" should "be able to make a HTTP GET request and pipe the response back" in {
    val p = Promise[String]()
    val asyncTcpClient = AsyncTcpClient("httpbin.org", 80)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map { _.subscribe(
      (bytes: Array[Byte]) => {
        recv.append(new String(bytes, "UTF-8"))
        if(recv.toString.contains("monix")) {
          p.success(recv.toString)
          Stop // stop as soon as the response is received
        }
        else {
          Continue
        }
      },
      err => p.failure(err))
    }.runAsync

    /* trigger a response to be read */
    val request = "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
    asyncTcpClient.tcpConsumer.map { writer =>
      val data = request.getBytes("UTF-8").grouped(256 * 1024).toArray
      Observable.fromIterable(data).consumeWith(writer).runAsync
    }.runAsync

    val result = Await.result(p.future, 10.seconds)
    result.length should be > 0
    result.startsWith("HTTP/1.1 200 OK") shouldBe true
    result.contains("monix") shouldBe true
  }

  "monix - tcp socket client" should "be able to reuse the same socket and make multiple requests" in {
    val p = Promise[String]()
    val asyncTcpClient = AsyncTcpClient("httpbin.org", 80)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map { reader =>
      reader.subscribe(
        (bytes: Array[Byte]) => {
          recv.append(new String(bytes, "UTF-8"))

          if (recv.toString.contains("monix2")) {
            /* stop as soon as the second response is received */
            p.success(recv.toString)
            Stop
          }
          else {
            Continue
          }
        },
        err => p.failure(err))
    }.runAsync

    // test with small chunks (2 bytes) to test order
    def data(request: String) = request.getBytes("UTF-8").grouped(2).toArray

    val writeT = for {
      writer <- asyncTcpClient.tcpConsumer
      r1 = data("GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n")
      _ <- Observable.fromIterable(r1).consumeWith(writer)
      r2 = data("GET /get?tcp=monix2 HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n")
      _ <- Observable.fromIterable(r2).consumeWith(writer)
    } yield {
      ()
    }

    // trigger response to be read
    writeT.runAsync


    val result = Await.result(p.future, 20.seconds)
    result.length should be > 0
    result.startsWith("HTTP/1.1 200 OK") shouldBe true
    result.contains("monix") shouldBe true
    result.contains("monix2") shouldBe true
  }
}
