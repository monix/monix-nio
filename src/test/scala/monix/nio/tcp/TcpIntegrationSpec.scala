package monix.nio.tcp

import monix.eval.{Callback, Task}
import monix.execution.Ack.Continue
import monix.reactive.Observable
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class TcpIntegrationSpec extends FlatSpec with Matchers {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  "monix - tcp socket client" should "connect to a TCP (HTTP) source successfully" in {
    val p = Promise[Boolean]()

    Task {
      val tcpObservable = AsyncTcpClient.tcpReader("monix.io", 443, 3.seconds)
      tcpObservable.subscribe(
        { (bytes: Array[Byte]) =>
          Console.out.println(s"monix: tcp socket client: ${new String(bytes, "UTF-8")}")
          p.success(true)
          Continue
        },
        err => p.failure(err),
        () => p.success(true))
    }.map(c => { c.cancel(); p.success(true)}).runAsync

    val result = Await.result(p.future, 5.seconds)
    assert(result, true)
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
      val tcpConsumer = AsyncTcpClient.tcpWriter("httpbin.org", 80, 3.seconds)
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

    val asyncTcpClient = AsyncTcpClient("httpbin.org", 80, 3.seconds)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map { reader =>
      val c = reader.subscribe(
        { (bytes: Array[Byte]) =>
          recv.append(new String(bytes, "UTF-8"))
          Continue
        },
        err => p.failure(err)
      )

      Thread.sleep(5000) // wait for 5 seconds for data

      c.cancel // close the socket
      p.success(recv.toString)
    }.runAsync

    /* trigger a response to be read */
    val request = "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
    asyncTcpClient.tcpConsumer.map { writer =>
      val data = request.getBytes("UTF-8").grouped(64).toArray
      Observable.fromIterable(data).consumeWith(writer).runAsync
    }.runAsync

    val result = Await.result(p.future, 10.seconds)
    result.length should be > 0
    result.startsWith("HTTP/1.1 200 OK") shouldBe true
    result.contains("monix") shouldBe true
  }
}
