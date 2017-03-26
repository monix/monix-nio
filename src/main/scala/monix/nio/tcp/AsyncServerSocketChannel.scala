package monix.nio.tcp

import java.net.{ InetSocketAddress, StandardSocketOptions }
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{ AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler }
import java.util.concurrent.Executors

import monix.eval.Callback
import monix.execution.{ Cancelable, Scheduler }

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
  * An asynchronous channel for stream-oriented listening sockets.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousServerSocketChannel.html java.nio.channels.AsynchronousServerSocketChannel]]
  * (class available since Java 7 for doing async I/O on sockets).
  *
  * @example {{{
  *   val server = AsyncServerSocketChannel()
  *   server.bind(new InetSocketAddress(InetAddress.getByName(null), 9000))
  *
  *   val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
  *   val writeF = server
  *     .accept()
  *     .flatMap { conn =>
  *       val writeF0 = conn.write(bytes, None)
  *       conn.stopWriting()
  *       writeF0
  *     }
  *     .map { sentLen =>
  *        server.close()
  *        sentLen
  *     }
  *
  *   writeF.onComplete {
  *     case Success(nr) =>
  *       println(f"Bytes sent: $nr%d")
  *
  *     case Failure(exc) =>
  *       println(s"ERR: $exc")
  *   }
  * }}}
  *
  * @define callbackDesc is the callback to be called with the result, once
  *         this asynchronous operation is complete
  * @define acceptDesc Accepts a connection
  * @define bindDesc Binds the channel's socket to a local address and configures the socket to listen for connections
  * @define localDesc the local address to bind the socket, or null to bind to an automatically assigned socket address
  * @define backlogDesc the maximum number of pending connections. If the backlog parameter has the value 0,
  *         or a negative value, then an implementation specific default is used.
  * @define localAddressDesc Asks the socket address that this channel's socket is bound to
  */
abstract class AsyncServerSocketChannel extends AutoCloseable {

  /**
    * $acceptDesc
    *
    * @param cb $callbackDesc
    */
  def accept(cb: Callback[AsyncSocketChannel]): Unit

  /** $acceptDesc */
  def accept(): Future[AsyncSocketChannel] = {
    val p = Promise[AsyncSocketChannel]()
    accept(Callback.fromPromise(p))
    p.future
  }

  /**
    * $bindDesc
    *
    * @param local $localDesc
    * @param backlog $backlogDesc
    */
  def bind(local: InetSocketAddress, backlog: Int = 0): Unit

  /** $localAddressDesc */
  def localAddress(): Option[InetSocketAddress]
}

object AsyncServerSocketChannel {
  /**
    * Opens a TCP server-socket channel
    *
    * @param reuseAddress [[java.net.ServerSocket#setReuseAddress]]
    * @param receiveBufferSize [[java.net.Socket#setReceiveBufferSize]] [[java.net.ServerSocket#setReceiveBufferSize]]
    *
    * @param s is the `Scheduler` used for asynchronous computations
    *
    * @return an [[monix.nio.tcp.AsyncServerSocketChannel AsyncServerSocketChannel]] instance for handling connections.
    */
  def apply(
    reuseAddress: Boolean = true,
    receiveBufferSize: Option[Int] = None
  )(implicit s: Scheduler): AsyncServerSocketChannel = {

    NewIOImplementation(reuseAddress, receiveBufferSize)
  }

  private lazy val acg =
    AsynchronousChannelGroup.withCachedThreadPool(Executors.newCachedThreadPool(), -1)

  private final case class NewIOImplementation(
      reuseAddress: Boolean = true,
      receiveBufferSize: Option[Int] = None
  )(implicit scheduler: Scheduler) extends AsyncServerSocketChannel {

    private[this] lazy val asynchronousServerSocketChannel: Either[Throwable, AsynchronousServerSocketChannel] = try {
      val server = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(acg)

      server.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      receiveBufferSize.foreach(sz => server.setOption[Integer](StandardSocketOptions.SO_RCVBUF, sz))

      Right(server)
    } catch {
      case NonFatal(exc) =>
        scheduler.reportFailure(exc)
        Left(exc)
    }

    private[this] val acceptHandler: CompletionHandler[AsynchronousSocketChannel, Callback[AsyncSocketChannel]] = {
      new CompletionHandler[AsynchronousSocketChannel, Callback[AsyncSocketChannel]] {
        override def completed(result: AsynchronousSocketChannel, attachment: Callback[AsyncSocketChannel]) =
          attachment.onSuccess(new AsyncSocketChannel.NewIOImplementation(result))
        override def failed(exc: Throwable, attachment: Callback[AsyncSocketChannel]) =
          attachment.onError(exc)
      }
    }
    override def accept(cb: Callback[AsyncSocketChannel]): Unit = {
      asynchronousServerSocketChannel.fold(_ => (), s => try s.accept(cb, acceptHandler) catch {
        case NonFatal(exc) =>
          cb.onError(exc)
      })
    }

    override def bind(local: InetSocketAddress, backlog: Int = 0): Unit = {
      asynchronousServerSocketChannel.fold(_ => (), s => try s.bind(local, backlog) catch {
        case NonFatal(ex) =>
          scheduler.reportFailure(ex)
      })
    }

    override def localAddress(): Option[InetSocketAddress] = {
      asynchronousServerSocketChannel.fold(_ => None, c => try Option(c.getLocalAddress).map(_.asInstanceOf[InetSocketAddress]) catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
          None
      })
    }

    private[this] val cancelable: Cancelable = Cancelable { () =>
      asynchronousServerSocketChannel.fold(_ => (), c => try c.close() catch {
        case NonFatal(exc) =>
          scheduler.reportFailure(exc)
      })
    }
    override def close(): Unit =
      cancelable.cancel()
  }
}
