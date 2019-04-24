package monix.nio.tcp

import java.net.{InetAddress, InetSocketAddress}

import minitest.SimpleTestSuite
import monix.eval.Task
import monix.execution.Ack.{Continue, Stop}
import monix.execution.Callback
import monix.execution.atomic.Atomic
import monix.reactive.{Consumer, Observable}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

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
          () => rp.success(true))
      }

      writeT.runAsync(new Callback[Throwable, Int] {
        override def onError(ex: Throwable) = wp.failure(ex)
        override def onSuccess(value: Int) = wp.success(value == data.array().length)
      })
      readT.runToFuture
    }.runToFuture

    val result = Await.result(Future.sequence(Seq(rp.future, wp.future)), 5.seconds)
    assert(result.forall(r => r))
  }

  test("write to a TCP connection successfully") {
    val data = Array.fill(8)("monix".getBytes())
    val chunkSize = 2 // very small chunks for testing

    val recv = new StringBuilder()
    val pw = Promise[Boolean]()
    val callbackW = new Callback[Throwable, Unit] {
      override def onSuccess(value: Unit): Unit = pw.success(data.flatten.length == recv.length)
      override def onError(ex: Throwable): Unit = pw.failure(ex)
    }

    val server = TaskServerSocketChannel()
    val readT = for {
      _ <- server.bind(new InetSocketAddress(InetAddress.getByName(null), 9000))
      conn <- server.accept()
      read <- readAsync(conn, chunkSize)
        .guaranteeCaseF(_ => server.close())
        .consumeWith(Consumer.foreach(recvBytes => recv.append(new String(recvBytes))))
    } yield {
      read
    }
    readT.runAsync(callbackW)

    val pr = Promise[Boolean]()
    val callbackR = new Callback[Throwable, Long] {
      override def onSuccess(value: Long): Unit = pr.success(data.flatten.length == value)
      override def onError(ex: Throwable): Unit = pr.failure(ex)
    }

    val tcpConsumer = writeAsync("localhost", 9000)
    Observable
      .fromIterable(data)
      .flatMap(all => Observable.fromIterator(Task(all.grouped(chunkSize))))
      .consumeWith(tcpConsumer)
      .runAsync(callbackR)

    val result = Await.result(Future.sequence(Seq(pr.future, pw.future)), 5.seconds)
    assert(result.forall(r => r))
  }

  test("the TCP server should handle 1000 clients, 16 at a time, by echoing back the received message correctly") {
    val echoed = Atomic(true)
    val noOfClients = 1000
    val program = asyncServer(InetAddress.getByName(null).getHostName, 9000).flatMap { taskServerSocketChannel =>
      val handlers = Observable
        .fromIterable(1 to noOfClients)
        .mapEval(_ => taskServerSocketChannel.accept())
        .mapParallelUnordered(4) { tsc =>
          for {
            conn <- Task.now(readWriteAsync(tsc))
            reader <- conn.tcpObservable
            writer <- conn.tcpConsumer
            written <- reader.guaranteeCaseF(_ => conn.stopWriting()).consumeWith(writer)
            _ <- conn.close()
          } yield {
            written
          }
        }

      val clients = Observable
        .fromIterable(1 to noOfClients)
        .mapParallelUnordered(16) { i =>
          val client = readWriteAsync("localhost", 9000, 8)
          val msg = s"Hello Monix - $i!"
          val data = msg.getBytes.grouped(3).toArray
          // writing
          val writeT = client.tcpConsumer.flatMap { writer =>
            Observable.fromIterable(data).consumeWith(writer).flatMap(_ => client.stopWriting())
          }
          // reading the echo
          val rp = Promise[Boolean]()
          val echo = new StringBuilder()
          val readT = for {
            reader <- client.tcpObservable
            _ <- Task.now(reader
              .guaranteeCaseF(_ => client.close())
              .subscribe(
                bytes => { echo.append(new String(bytes)); Continue },
                err => rp.failure(err),
                () => rp.success(echo.toString() == msg)))
          } yield {}
          writeT.flatMap(_ => readT.flatMap(_ => Task.fromFuture(rp.future)))
        }

      val doneP = Promise[Boolean]()
      Task {
        handlers
          .guaranteeCaseF(_ => taskServerSocketChannel.close())
          .publish
          .connect()
      }.runToFuture

      Task {
        clients.subscribe(
          r => {
            echoed.compareAndSet(expect = true, update = r)
            Continue
          },
          err => doneP.failure(err),
          () => {
            taskServerSocketChannel.close()
            doneP.success(echoed.get)
          })
      }.runToFuture

      Task.fromFuture(doneP.future)
    }
    assert(Await.result(program.runToFuture, 10.seconds))
  }

  test("be able to make a HTTP GET request and pipe the response back") {
    val p = Promise[String]()
    val asyncTcpClient = readWriteAsync("httpbin.org", 80, 256 * 1024)

    val recv = new StringBuffer("")
    asyncTcpClient.tcpObservable.map {
      _
        .guaranteeCaseF[Task](_ => asyncTcpClient.close().map(_ => p.success(recv.toString))) // cleanup
        .subscribe(
          (bytes: Array[Byte]) => {
            recv.append(new String(bytes, "UTF-8"))
            if (recv.toString.contains("monix")) {
              Stop // stop as soon as the response is received
            } else {
              Continue
            }
          },
          err => p.failure(err))
    }.runToFuture

    /* trigger a response to be read */
    val request = "GET /get?tcp=monix HTTP/1.1\r\nHost: httpbin.org\r\nConnection: keep-alive\r\n\r\n"
    asyncTcpClient.tcpConsumer.flatMap { writer =>
      val data = request.getBytes("UTF-8").grouped(256 * 1024).toArray
      Observable.fromIterable(data).consumeWith(writer)
    }.runToFuture

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
        .guaranteeCaseF[Task](_ => asyncTcpClient.close().map(_ => p.success(recv.toString))) // cleanup
        .subscribe(
          (bytes: Array[Byte]) => {
            recv.append(new String(bytes, "UTF-8"))
            if (recv.toString.contains("monix2")) {
              Stop // stop as soon as the second response is received
            } else {
              Continue
            }
          },
          err => p.failure(err))
    }.runToFuture

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
    writeT.runToFuture

    val result = Await.result(p.future, 20.seconds)
    assert(result.length > 0)
    assert(result.startsWith("HTTP/1.1 200 OK"))
    assert(result.contains("monix"))
    assert(result.contains("monix2"))
  }
}
