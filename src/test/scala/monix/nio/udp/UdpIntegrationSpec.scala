package monix.nio.udp

import java.net.InetSocketAddress

import minitest.SimpleTestSuite
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Ack.{ Continue, Stop }
import monix.reactive.Observable

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

object UdpIntegrationSpec extends SimpleTestSuite {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  test("send and receive UDP packets successfully") {
    val data = Array.fill(8)("monix")

    val writes = (ch: TaskDatagramChannel, to: InetSocketAddress) => Observable
      .fromIterable(data)
      .mapEval(data => ch.send(Packet(data.getBytes, to)))

    val readsPromise = Promise[String]()
    val recv = new StringBuilder("")
    val reads = (ch: TaskDatagramChannel, maxSize: Int) => Observable
      .repeatEval(ch.receive(maxSize, 2.seconds))
      .mapEval(t => t)
      .map { packet =>
        packet.foreach(p => recv.append(new String(p.data)))
        packet
      }
      .guaranteeCaseF(_ => readsPromise.success(recv.mkString))
      .subscribe(_.fold[Ack](Stop)(_ => Continue))

    val program = for {
      ch <- bind("localhost", 2115).map { ch =>
        reads(ch, 64)
        ch
      }
      sent <- writes(ch, new InetSocketAddress("localhost", 2115)).sumL
      received <- Task.fromFuture(readsPromise.future)
      _ <- ch.close()
    } yield sent == 40 & received == data.mkString("")

    val result = Await.result(program.runToFuture, 10.seconds)
    assert(result)
  }
}
