package monix.nio.tcp

import monix.eval.{Callback, Task}

class AsyncTcpClient private (
  host: String, port: Int,
  bufferSize: Int,
  onOpenError: Throwable => Unit)(implicit ec: scala.concurrent.ExecutionContext) {

  private[this] val underlyingSocketClient = SocketClient(
    new java.net.InetSocketAddress(host, port),
    onOpenError = onOpenError,
    closeOnComplete = false)
  private[this] val connectedSignal = scala.concurrent.Promise[Unit]()
  private[this] val connectCallback = new Callback[Void]() {
    override def onSuccess(value: Void): Unit = {
      connectedSignal.success(())
    }
    override def onError(ex: Throwable): Unit = {
      connectedSignal.failure(ex)
      onOpenError(ex)
    }
  }

  private[this] lazy val asyncTcpClientObservable =
    new AsyncTcpClientObservable(underlyingSocketClient, bufferSize)
  private[this] lazy val asyncTcpClientConsumer =
    new AsyncTcpClientConsumer(underlyingSocketClient)

  /**
    * The TCP client reader.
    * It is the one responsible to close the connection
    * when used together with a writer ([[monix.nio.tcp.AsyncTcpClientConsumer]]),
    * by using a [[monix.reactive.observers.Subscriber]]
    * and signal [[monix.execution.Ack.Stop]] or cancel it
    */
  def tcpObservable: Task[AsyncTcpClientObservable] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientObservable)
  }

  /**
    * The TCP client writer
    */
  def tcpConsumer: Task[AsyncTcpClientConsumer] = Task.fromFuture {
    connectedSignal.future.map(_ => asyncTcpClientConsumer)
  }

  private def init(): Unit = {
    underlyingSocketClient.connect(connectCallback)
  }
}

object AsyncTcpClient {

  def tcpReader(host: String, port: Int, bufferSize: Int = 256 * 1024) =
    new AsyncTcpClientObservable(host, port, bufferSize)

  def tcpWriter(host: String, port: Int) =
    new AsyncTcpClientConsumer(host, port)

  def apply(
    host: String, port: Int,
    bufferSize: Int = 256 * 1024,
    onOpenError: Throwable => Unit = _ => ())(implicit ec: scala.concurrent.ExecutionContext): AsyncTcpClient = {

    val asyncTcpClient = new AsyncTcpClient(host, port, bufferSize, onOpenError)
    asyncTcpClient.init()

    asyncTcpClient
  }
}
