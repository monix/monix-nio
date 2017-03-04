package monix.nio.tcp

import monix.execution.Ack.Continue
import org.scalatest.{FlatSpec, Matchers}

class TcpSpec extends FlatSpec with Matchers {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  "Tcp client" should "read a stream and close successfully" in {
    val recv = new StringBuilder("")

    val tcpObservable = new AsyncTcpClient("localhost", 9000)
    val c = tcpObservable.subscribe(
      { (bytes: Array[Byte]) =>
        recv.append(new String(bytes, "UTF-8"))
        Continue
      },
      err => Console.err.println(err),
      () => Console.out.println("Completed"))

    Thread.sleep(5000)

    c.cancel()
    recv.toString() should be ("OK\n")
  }
}
