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

import scala.util.control.NonFatal

class AsyncTcpClient(host: String, port: Int, buffSize: Int = 256 * 1024) extends Observable[Array[Byte]] {

  private[this] val client = Client(
    new InetSocketAddress(host, port),
    onOpenError = t => Console.err.println(t.getMessage))
  private[this] val buffer = ByteBuffer.allocate(buffSize)
  private[this] val wasSubscribed = Atomic(false)

  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    }
    else try {
      val connectCallback = new Callback[Void]() {
        override def onSuccess(value: Void): Unit = {}
        override def onError(ex: Throwable): Unit = {
          closeChannel()
          subscriber.onError(ex)
        }
      }

      client.connect(connectCallback)
      startReading(subscriber)
    } catch {
      case NonFatal(e) => subscriber.onError(e); closeChannel(); Cancelable.empty
    }
  }

  private def closeChannel()(implicit reporter: UncaughtExceptionReporter) = {
    client.closeChannel()(reporter)
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

    val c = Task
      .defer(loop(subscriber))
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(taskCallback)

    val singleFunctionCallCancelable = SingleFunctionCallCancelable(() => { c.cancel(); closeChannel()})
    SingleAssignmentCancelable.plusOne(singleFunctionCallCancelable)
  }

  private def createReadTask(buff: ByteBuffer) =
    Task.create[Int] { (scheduler, callback) =>
      try {
        client.readChannel(buff, callback)
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
            case Stop => println("stop"); Task.now(Array.empty)
          }
      }
    }
  }
}
