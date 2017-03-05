package monix.nio.tcp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, UncaughtExceptionReporter}
import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.nio.{Bytes, EmptyBytes, NonEmptyBytes}
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Promise
import scala.util.control.NonFatal

class AsyncTcpClientObservable private[tcp] (
  host: String, port: Int,
  buffSize: Int = 256 * 1024) extends Observable[Array[Byte]] {

  private[tcp] var socketClient: Option[SocketClient] = None

  private[tcp] def this(client: SocketClient, buffSize: Int) {
    this(client.to.getHostString, client.to.getPort, buffSize)
    this.socketClient = Option(client)
  }

  private[this] val buffer = ByteBuffer.allocate(buffSize)
  private[this] val wasSubscribed = Atomic(false)
  private[this] val connectedSignal = Promise[Unit]()

  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    }
    else {
      try {
        init(subscriber)
        startReading(subscriber)
      }
      catch {
        case NonFatal(e) =>
          subscriber.onError(e)
          closeChannel()
          Cancelable.empty
      }
    }
  }

  private def init(subscriber: Subscriber[Array[Byte]]) = {
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
  }

  private def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
    socketClient.foreach(_.closeChannel())
  }

  private def startReading(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    val taskCallback = new Callback[Array[Byte]]() {
      override def onSuccess(value: Array[Byte]): Unit = {
        closeChannel()
      }

      override def onError(ex: Throwable): Unit = {
        closeChannel()
        subscriber.onError(ex)
      }
    }

    val cancelable = Task
      .fromFuture(connectedSignal.future)
      .flatMap { _ =>
        loop(subscriber).executeWithOptions(_.enableAutoCancelableRunLoops)
      }
      .runAsync(taskCallback)

    val singleFunctionCallCancelable = SingleFunctionCallCancelable(() => {
      cancelable.cancel()
      closeChannel()
    })
    SingleAssignmentCancelable.plusOne(singleFunctionCallCancelable)
  }

  private def createReadTask(buff: ByteBuffer) =
    Task.async[Int] { (scheduler, callback) =>
      try {
        socketClient.foreach(_.readChannel(buff, callback))
      } catch {
        case NonFatal(ex) => callback.onError(ex)
      }
      Cancelable(() => closeChannel()(scheduler))
    }

  private def loop(subscriber: Subscriber[Array[Byte]])(implicit rep: UncaughtExceptionReporter): Task[Array[Byte]] = {
    buffer.clear()
    createReadTask(buffer).flatMap { result =>
      val bytes = Bytes(buffer, result)
      bytes match {
        case EmptyBytes =>
          subscriber.onComplete()
          Task.now(Array.empty)

        case NonEmptyBytes(arr) =>
          Task.fromFuture(subscriber.onNext(arr)).flatMap {
            case Continue => loop(subscriber)
            case Stop => Task.now(Array.empty)
          }
      }
    }
  }
}
