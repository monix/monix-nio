package monix.nio.tcp

import java.net.InetSocketAddress

import monix.eval.Callback
import monix.nio.AsyncChannelConsumer

import scala.concurrent.Promise
import scala.util.control.NonFatal

class AsyncTcpClientConsumer private[tcp] (host: String, port: Int) extends AsyncChannelConsumer[SocketClient] {
  private[this] var socketClient: Option[SocketClient] = None

  private[tcp] def this(client: SocketClient) {
    this(client.to.getHostString, client.to.getPort)
    this.socketClient = Option(client)
  }

  override protected def channel = socketClient


  override def init(subscriber: AsyncChannelSubscriber) = {
    import subscriber.scheduler

    val connectedPromise = Promise[Unit]()
    val connectCallback = new Callback[Void]() {
      override def onSuccess(value: Void): Unit = {
        connectedPromise.success(())
      }
      override def onError(ex: Throwable): Unit = {
        connectedPromise.failure(ex)
        subscriber.closeChannel()
        subscriber.onError(ex)
      }
    }

    try {
      if (socketClient.isDefined) connectedPromise.success(())
      else {
        socketClient = Option(SocketClient(new InetSocketAddress(host, port), onOpenError = subscriber.onError))
        socketClient.foreach(_.connect(connectCallback))
      }
    }
    catch {
      case NonFatal(ex) => subscriber.sendError(ex)
    }
    connectedPromise.future
  }
}
