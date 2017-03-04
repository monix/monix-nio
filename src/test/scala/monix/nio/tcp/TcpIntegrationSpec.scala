package monix.nio.tcp

import monix.eval.Callback
import monix.execution.Ack.Continue
import monix.reactive.Observable
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class TcpIntegrationSpec extends FlatSpec with Matchers {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  "Tcp client" should "read a stream from a TCP connection" in {
    /*
    new ProcessBuilder("/bin/sh", "-c", "echo monix | nc -l 9001").start()

    val recv = new StringBuilder("")

    val tcpObservable = new AsyncTcpClientObservable("localhost", 9001)
    val c = tcpObservable.subscribe(
      { (bytes: Array[Byte]) =>
        recv.append(new String(bytes, "UTF-8"))
        Continue
      },
      err => Console.err.println(err),
      () => Console.out.println("Completed"))

    c.cancel()
    recv.toString.trim should be ("monix")
    */
  }

  "Tcp client" should "write to a TCP connection" in {
    /*
    new ProcessBuilder("/bin/sh", "-c", "nc -l 9002").start()

    val data = Array.fill(32)("monix".getBytes())
    val chunkSize = 2

    val p = Promise[Boolean]()
    val callback = new Callback[Long] {
      override def onSuccess(value: Long): Unit = p.success(data.flatten.length == value)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    val tcpConsumer = new AsyncTcpClientConsumer("localhost", 9002)
    Observable
      .fromIterable(data)
      .flatMap(all => Observable.fromIterator(all.grouped(chunkSize))) // batch it
      .consumeWith(tcpConsumer)
      .runAsync(callback)

    val result = Await.result(p.future, 3.seconds)
    assert(result, true)
    */
  }
}
