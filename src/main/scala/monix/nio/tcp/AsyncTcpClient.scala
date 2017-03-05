package monix.nio.tcp

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

import monix.eval.{Callback, Task}

import scala.concurrent.duration._

class AsyncTcpClient private (
  host: String,
  port: Int,
  timeout: FiniteDuration,
  bufferSize: Int,
  onOpenError: Throwable => Unit) {

  private[this] val underlyingSocketClient = SocketClient(
    new InetSocketAddress(host, port),
    onOpenError = onOpenError,
    closeOnComplete = false)
  private[this] val connectedSignal = new CountDownLatch(1)
  private[this] val connectCallback = new Callback[Void]() {
    override def onSuccess(value: Void): Unit = {
      connectedSignal.countDown()
    }
    override def onError(ex: Throwable): Unit = {
      connectedSignal.countDown()
      onOpenError(ex)
    }
  }

  def tcpObservable: Task[AsyncTcpClientObservable] = Task {
    connectedSignal.await()
    new AsyncTcpClientObservable(underlyingSocketClient, timeout, bufferSize)
  }

  def tcpConsumer: Task[AsyncTcpClientConsumer] = Task {
    connectedSignal.await()
    new AsyncTcpClientConsumer(underlyingSocketClient, timeout)
  }

  private def init(): Unit = {
    underlyingSocketClient.connect(connectCallback)
  }
}

object AsyncTcpClient {

  def tcpReader(host: String, port: Int, timeout: FiniteDuration = 60.seconds, bufferSize: Int = 256 * 1024) =
    new AsyncTcpClientObservable(host, port, timeout, bufferSize)

  def tcpWriter(host: String, port: Int, timeout: FiniteDuration = 60.seconds) =
    new AsyncTcpClientConsumer(host, port, timeout)

  def apply(
    host: String, port: Int,
    timeout: FiniteDuration = 60.seconds,
    bufferSize: Int = 256 * 1024,
    onOpenError: Throwable => Unit = _ => ()): AsyncTcpClient = {

    val asyncTcpClient = new AsyncTcpClient(host, port, timeout, bufferSize, onOpenError)
    asyncTcpClient.init()

    asyncTcpClient
  }
}
