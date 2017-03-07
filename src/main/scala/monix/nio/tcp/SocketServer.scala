package monix.nio.tcp

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.Executors

import monix.eval.Callback
import monix.execution.UncaughtExceptionReporter

import scala.util.control.NonFatal

private case class SocketServer(
  to: InetSocketAddress,
  reuseAddress: Boolean = true,
  sendBufferSize: Int = 256 * 1024,
  receiveBufferSize: Int = 256 * 1024,
  keepAlive: Boolean = false,
  noDelay: Boolean = false,
  onOpenError: Throwable => Unit = _ => ()) {

  private[this] lazy val server: Either[Throwable, AsynchronousServerSocketChannel] = try {
    val ag = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
    val server = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(ag)
    server.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
    server.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
    server.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
    server.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
    server.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
    Right(server)
  } catch {
    case NonFatal(exc) => onOpenError(exc); Left(exc)
  }

  protected[tcp] def closeChannel()(implicit reporter: UncaughtExceptionReporter): Unit = {
    server.fold(_ => (), s => try s.close() catch {
      case NonFatal(ex) => reporter.reportFailure(ex)
    })
  }

  protected[tcp] def bind(callback: Callback[Unit]): Unit = {
    server.fold(_ => (), s => try {
      s.bind(to)
      callback.onSuccess(())
    } catch {
      case NonFatal(ex) => callback.onError(ex)
    })
  }

  protected[tcp] def accept(dst: ByteBuffer, callback: Callback[AsynchronousSocketChannel]): Unit = {
    val handler = new CompletionHandler[AsynchronousSocketChannel, Null] {
      override def completed(result: AsynchronousSocketChannel, attachment: Null) = {
        callback.onSuccess(result)
      }
      override def failed(exc: Throwable, attachment: Null) = exc match {
        case _: java.nio.channels.AsynchronousCloseException => ()
        case _ => callback.onError(exc)
      }
    }

    server.fold(_ => (), { s =>
      s.accept(null, handler)
    })
  }
}
