package monix.nio.tcp

import java.net.{ InetAddress, InetSocketAddress }

import minitest.SimpleTestSuite
import monix.eval.{ Callback, Task }
import monix.execution.Ack.{ Continue, Stop }
import monix.reactive.{ Consumer, Observable }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

object TcpIntegrationSpec extends SimpleTestSuite {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  test("connect and read from a TCP source successfully") {
    val wp = Promise[Boolean]()
    val rp = Promise[Boolean]()
    val data = java.nio.ByteBuffer.wrap("Hello world!".getBytes)

    asyncServer(InetAddress.getByName(null).getHostName, 9000).map { taskServerSocketChannel =>
      val writeT = for {
        conn <- taskServerSocketChannel.accept()
        written <- conn.write(data)
        _ <- conn.stopWriting()
        _ <- taskServerSocketChannel.close()
      } yield {
        written
      }

      val readT = Task {
        val tcpObservable = readAsync("localhost", 9000, 2)
        tcpObservable.subscribe(
          (bytes: Array[Byte]) => {
            val chunk = new String(bytes, "UTF-8")
            if (chunk.endsWith("\n")) {
              rp.success(true)
              Stop
            } else
              Continue
          },
          err => rp.failure(err),
          () => rp.success(true)
        )
      }

      writeT.runAsync(new Callback[Int] {
        override def onError(ex: Throwable) = wp.failure(ex)
        override def onSuccess(value: Int) = wp.success(value == data.array().length)
      })
      readT.runAsync
    }.runAsync

    val result = Await.result(Future.sequence(Seq(rp.future, wp.future)), 5.seconds)
    assert(result.forall(r => r))
  }

  test("write to a TCP connection successfully") {
    val data = Array.fill(8)("monix".getBytes())
    val chunkSize = 2 // very small chunks for testing

    val recv = new StringBuilder()
    val pw = Promise[Boolean]()
    val callbackW = new Callback[Unit] {
      override def onSuccess(value: Unit): Unit = pw.success(data.flatten.length == recv.length)
      override def onError(ex: Throwable): Unit = pw.failure(ex)
    }

    val server = TaskServerSocketChannel()
    val readT = for {
      _ <- server.bind(new InetSocketAddress(InetAddress.getByName(null), 9000))
      conn <- server.accept()
      read <- readAsync(conn, chunkSize)
        .doOnTerminateEval(_ => server.close())
        .consumeWith(Consumer.foreach(recvBytes => recv.append(new String(recvBytes))))
    } yield {
      read
    }
    readT.runAsync(callbackW)

    val pr = Promise[Boolean]()
    val callbackR = new Callback[Long] {
      override def onSuccess(value: Long): Unit = pr.success(data.flatten.length == value)
      override def onError(ex: Throwable): Unit = pr.failure(ex)
    }

    val tcpConsumer = writeAsync("localhost", 9000)
    Observable
      .fromIterable(data)
      .flatMap(all => Observable.fromIterator(all.grouped(chunkSize)))
      .consumeWith(tcpConsumer)
      .runAsync(callbackR)

    val result = Await.result(Future.sequence(Seq(pr.future, pw.future)), 5.seconds)
    assert(result.forall(r => r))
  }

  /*
  test("server - handle 10000 clients (echo test)") {
    val program = asyncServer(InetAddress.getByName(null).getHostName, 9000).flatMap { taskServerSocketChannel =>
      val handlers = Observable
        .fromIterable(1 to 2)
        .mapAsync(16) { _ =>
          Task {
            val echoT = for {
              conn <- taskServerSocketChannel.accept().map(tsc => readWriteAsync(tsc))
              reader <- conn.tcpObservable
              writer <- conn.tcpConsumer
              written <- reader.doOnTerminate(_ => { println("echo"); conn.stopWriting() }).consumeWith(writer)
              _ <- conn.close()
            } yield {
              println("handler - " + written)
              written
            }
            echoT.runAsync(new Callback[Long] {
              override def onError(ex: Throwable): Unit = ex.printStackTrace()
              override def onSuccess(value: Long): Unit = println("ok1 - " + value)
            })
          }
        }

      val clients = Observable
        .fromIterable(1 to 2)
        .mapAsync(16) { i =>
          val client = readWriteAsync("localhost", 9000, 8)

          // writing
          val data = s"Hello Monix - $i!".getBytes.grouped(8).toArray
          val writeT = client.tcpConsumer.flatMap { writer =>
            Observable.fromIterable(data).consumeWith(writer).flatMap(len => client.stopWriting().map(_ => len))
          }

          // reading the echo
          val rp = Promise[Boolean]()
          val readT = for {
            reader <- client.tcpObservable
            written <- Task.now(
              reader
                .doOnTerminate(_ => { println("Terminated"); client.close() })
                .subscribe(
                  bytes => {
                    println(new String(bytes)); Continue
                  },
                  err => { println(err); rp.failure(err) },
                  () => rp.success(true)
                )
            )
          } yield {
            written
          }

          writeT.runAsync(new Callback[Long] {
            override def onError(ex: Throwable): Unit = ex.printStackTrace()
            override def onSuccess(value: Long): Unit = println("ok2 - " + value)
          })
          readT.runAsync(new Callback[Cancelable] {
            override def onError(ex: Throwable): Unit = ex.printStackTrace()
            override def onSuccess(value: Cancelable): Unit = println("ok3")
          })
          Task.fromFuture(rp.future)
        }

      //clients.toListL.foreach()
      val doneP = Promise[Boolean]()
      handlers.doOnTerminate(_ => println("server done")).publish.connect()
      clients
        .doOnTerminate(_ => { println("TOO SOON"); taskServerSocketChannel.close() })
        .subscribe(
          (r: Boolean) => { println(r); Continue },
          err => doneP.failure(err),
          () => { println("DONE"); doneP.success(true) }
        )
      Task.fromFuture(doneP.future)
    }

    assert(Await.result(program.runAsync, 10.seconds))
  }*/

  test("be able to make a HTTP GET request and pipe the response back") {
    val p = Promise[String]()
    val asyncTcpClient = readWriteAsync("httpbin.org", 80, 256 * 1024)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map {
      _
        .doOnTerminateEval(_ => asyncTcpClient.close().map(_ => p.success(recv.toString))) // cleanup
        .subscribe(
          (bytes: Array[Byte]) => {
            recv.append(new String(bytes, "UTF-8"))
            if (recv.toString.contains("monix")) {
              Stop // stop as soon as the response is received
            } else {
              Continue
            }
          },
          err => p.failure(err)
        )
    }.runAsync

    /* trigger a response to be read */
    val request = "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
    asyncTcpClient.tcpConsumer.flatMap { writer =>
      val data = request.getBytes("UTF-8").grouped(256 * 1024).toArray
      Observable.fromIterable(data).consumeWith(writer)
    }.runAsync

    val result = Await.result(p.future, 10.seconds)
    assert(result.length > 0)
    assert(result.startsWith("HTTP/1.1 200 OK"))
    assert(result.contains("monix"))
  }

  test("be able to reuse the same socket and make multiple requests") {
    val p = Promise[String]()
    val asyncTcpClient = readWriteAsync("httpbin.org", 80, 256 * 1024)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map { reader =>
      reader
        .doOnTerminateEval(_ => asyncTcpClient.close().map(_ => p.success(recv.toString))) // cleanup
        .subscribe(
          (bytes: Array[Byte]) => {
            recv.append(new String(bytes, "UTF-8"))
            if (recv.toString.contains("monix2")) {
              Stop // stop as soon as the second response is received
            } else {
              Continue
            }
          },
          err => p.failure(err)
        )
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
    assert(result.length > 0)
    assert(result.startsWith("HTTP/1.1 200 OK"))
    assert(result.contains("monix"))
    assert(result.contains("monix2"))
  }
}
