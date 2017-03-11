package monix.nio.tcp

import monix.eval.Task
import monix.execution.Scheduler

/**
 * A TCP server
 *
 * @param host hostname
 * @param port TCP port number
 * @return an [[monix.nio.tcp.AsyncTcpServer AsyncTcpServer]]
 */
final case class AsyncTcpServer(host: String, port: Int)(implicit scheduler: Scheduler) {
  private[this] val underlyingAsyncServerSocketClient = AsyncServerSocketChannel()

  /**
   * @return the underlying [[monix.nio.tcp.AsyncServerSocketChannel AsyncServerSocketChannel]]
   */
  def tcpServer: Task[AsyncServerSocketChannel] =
    Task(underlyingAsyncServerSocketClient.bind(new java.net.InetSocketAddress(host, port)))
      .map(_ => underlyingAsyncServerSocketClient)
}
