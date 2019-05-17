package monix.nio.tcp

import java.net.InetSocketAddress

import monix.eval.Task
import monix.execution.{ Callback, Scheduler }

/**
  * A `Task` based asynchronous channel for stream-oriented listening sockets.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousServerSocketChannel.html java.nio.channels.AsynchronousServerSocketChannel]]
  * (class available since Java 7 for doing async I/O on sockets).
  *
  * @example {{{
  *   val server = TaskServerSocketChannel()
  *
  *   val writeT = for {
  *     _ <- server.bind(new InetSocketAddress(InetAddress.getByName(null), 9000))
  *     conn <- server.accept()
  *     written <- conn.writeL(java.nio.ByteBuffer.wrap("Hello world!".getBytes))
  *     _ <- Task.eval(conn.stopWriting())
  *     _ <- server.close()
  *   } yield {
  *     written
  *   }
  *
  *   writeT.runAsync(new Callback[Int] {
  *     override def onError(ex: Throwable) = println(ex)
  *     override def onSuccess(value: Int) = println(f"Bytes sent: $value%d")
  *   })
  *
  * }}}
  * @define asyncServerSocketChannelDesc The underlying [[monix.nio.tcp.AsyncServerSocketChannel AsyncServerSocketChannel]]
  * @define acceptDesc Accepts a connection
  * @define bindDesc Binds the channel's socket to a local address and configures the socket to listen for connections
  * @define localDesc the local address to bind the socket, or null to bind to an automatically assigned socket address
  * @define backlogDesc the maximum number of pending connections. If the backlog parameter has the value 0,
  *         or a negative value, then an implementation specific default is used.
  * @define localAddressDesc Asks the socket address that this channel's socket is bound to
  * @define closeDesc Closes this channel
  */
abstract class TaskServerSocketChannel {

  /** $asyncServerSocketChannelDesc */
  protected val asyncServerSocketChannel: AsyncServerSocketChannel

  /** $acceptDesc */
  def accept(): Task[TaskSocketChannel] =
    Task.create { (_, cb) =>
      asyncServerSocketChannel.accept(new Callback[Throwable, AsyncSocketChannel] {
        override def onError(ex: Throwable): Unit = cb.onError(ex)
        override def onSuccess(value: AsyncSocketChannel): Unit = cb.onSuccess(
          new TaskSocketChannel {
            override val asyncSocketChannel: AsyncSocketChannel = value
          })
      })
    }

  /**
    * $bindDesc
    *
    * @param local $localDesc
    * @param backlog $backlogDesc
    */
  def bind(local: InetSocketAddress, backlog: Int = 0): Task[Unit] =
    Task.now(asyncServerSocketChannel.bind(local, backlog))

  /** $localAddressDesc */
  def localAddress(): Task[Option[InetSocketAddress]] =
    Task.now(asyncServerSocketChannel.localAddress())

  /** $closeDesc */
  def close(): Task[Unit] =
    Task.now(asyncServerSocketChannel.close())
}

object TaskServerSocketChannel {
  /**
    * Opens a server-socket channel for the given [[java.net.InetSocketAddress]]
    *
    * @param reuseAddress [[java.net.ServerSocket#setReuseAddress]]
    * @param receiveBufferSize [[java.net.Socket#setReceiveBufferSize]] [[java.net.ServerSocket#setReceiveBufferSize]]
    *
    * @param s is the `Scheduler` used for asynchronous computations
    *
    * @return an [[monix.nio.tcp.TaskServerSocketChannel TaskServerSocketChannel]] instance for handling connections.
    */
  def apply(
    reuseAddress: Boolean = true,
    receiveBufferSize: Option[Int] = None)(implicit s: Scheduler): TaskServerSocketChannel = {

    new TaskServerSocketChannel {
      override val asyncServerSocketChannel = AsyncServerSocketChannel(reuseAddress, receiveBufferSize)
    }
  }
}
