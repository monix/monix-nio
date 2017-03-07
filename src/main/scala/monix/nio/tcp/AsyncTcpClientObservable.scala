package monix.nio.tcp

import java.net.InetSocketAddress

import monix.eval.Callback
import monix.nio._
import monix.reactive.observers.Subscriber

import scala.concurrent.{Future, Promise}

final class AsyncTcpClientObservable private[tcp] (
  host: String, port: Int,
  buffSize: Int = 256 * 1024) extends AsyncChannelObservable[SocketClient] {

  override def bufferSize: Int = buffSize

  private[this] val connectedSignal = Promise[Unit]()
  private[this] var socketClient: Option[SocketClient] = None

  private[tcp] def this(client: SocketClient, buffSize: Int) {
    this(client.to.getHostString, client.to.getPort, buffSize)
    this.socketClient = Option(client)
  }

  override protected def channel: Option[SocketClient] = socketClient

  override def init(subscriber: Subscriber[Array[Byte]]): Future[Unit] = {
    import subscriber.scheduler
    if (socketClient.isDefined) {
      connectedSignal.success(())
    }
    else {
      socketClient = Option(SocketClient(new InetSocketAddress(host, port), onOpenError = subscriber.onError))
      val connectCallback = new Callback[Void]() {
        override def onSuccess(value: Void): Unit = {
          connectedSignal.success(())
        }
        override def onError(ex: Throwable): Unit = {
          connectedSignal.failure(ex)
          closeChannel()
          subscriber.onError(ex)
        }
      }

      socketClient.foreach(_.connect(connectCallback))
    }

    connectedSignal.future
  }
}
