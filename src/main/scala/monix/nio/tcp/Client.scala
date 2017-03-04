package monix.nio.tcp

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.{Executors, TimeUnit}

import monix.eval.Callback
import monix.execution.UncaughtExceptionReporter
import scala.util.control.NonFatal

private case class Client(
  to: InetSocketAddress,
  reuseAddress: Boolean = true,
  sendBufferSize: Int = 256 * 1024,
  receiveBufferSize: Int = 256 * 1024,
  keepAlive: Boolean = false,
  noDelay: Boolean = false,
  onOpenError: Throwable => Unit = _ => ()) {

  private lazy val socketChannel: Either[Throwable, AsynchronousSocketChannel] = try {
    val ag = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
    val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(ag)
    ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
    ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
    ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
    ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
    ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
    Right(ch)
  } catch {
    case NonFatal(exc) => onOpenError(exc); Left(exc)
  }

  protected[tcp] def closeChannel()(implicit reporter: UncaughtExceptionReporter): Unit = {
    socketChannel.fold(_ => (), c => try c.close() catch {
      case NonFatal(ex) => reporter.reportFailure(ex)
    })
  }

  protected[tcp] def connect(callback: Callback[Void]): Unit = {
    val handler = new CompletionHandler[Void, Null] {
      override def completed(result: Void, attachment: Null) = callback.onSuccess(result)
      override def failed(exc: Throwable, attachment: Null) = callback.onError(exc)
    }
    socketChannel.fold(_ => (), c => c.connect(to, null, handler))
  }

  protected[tcp] def readChannel(dst: ByteBuffer, callback: Callback[Int]): Unit = {
    val handler = new CompletionHandler[Integer, Null] {
      override def completed(result: Integer, attachment: Null) = {
        callback.onSuccess(result)
      }
      override def failed(exc: Throwable, attachment: Null) = exc match {
        case _: java.nio.channels.AsynchronousCloseException => ()
        case _ => callback.onError(exc)
      }
    }

    socketChannel.fold(_ => (), { c =>
      if(c.isOpen) c.read(dst, 0l, TimeUnit.MILLISECONDS, null, handler)
    })
  }
}
