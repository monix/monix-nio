/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.nio

import java.nio.ByteBuffer

import monix.eval.Task
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Callback, Cancelable, Scheduler}
import monix.execution.atomic.Atomic
import monix.execution.cancelables.SingleAssignCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.nio.internal.{Bytes, EmptyBytes, NonEmptyBytes}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import scala.util.control.NonFatal

private[nio] abstract class AsyncChannelObservable extends Observable[Array[Byte]] {
  def bufferSize: Int
  def channel: Option[AsyncChannel]
  def init(subscriber: Subscriber[Array[Byte]]): Future[Unit] =
    Future.successful(())

  private[this] val wasSubscribed = Atomic(false)
  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    } else try startReading(subscriber) catch {
      case NonFatal(e) =>
        subscriber.onError(e)
        closeChannel()
        Cancelable.empty
    }
  }

  private def startReading(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler

    val taskCallback = new Callback[Throwable, Array[Byte]]() {
      override def onSuccess(value: Array[Byte]): Unit = {
        channel.collect { case sc if sc.closeOnComplete => closeChannel() }
      }
      override def onError(ex: Throwable): Unit = {
        closeChannel()
        subscriber.onError(ex)
      }
    }
    val cancelable = Task
      .fromFuture(init(subscriber))
      .flatMap { _ =>
        loop(subscriber, 0)
      }
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(taskCallback)

    val extraCancelable = Cancelable(() => {
      cancelable.cancel()
      closeChannel()
    })
    SingleAssignCancelable.plusOne(extraCancelable)
  }

  private[this] val buffer = ByteBuffer.allocate(bufferSize)
  private def loop(subscriber: Subscriber[Array[Byte]], position: Long)(implicit scheduler: Scheduler): Task[Array[Byte]] = {
    buffer.clear()
    channel.map { ch =>
      ch
        .read(buffer, position)
        .doOnCancel(Task.defer(ch.close()))
        .flatMap { result =>
          val bytes = Bytes(buffer, result)
          bytes match {
            case EmptyBytes =>
              subscriber.onComplete()
              Task.now(Bytes.emptyBytes)

            case NonEmptyBytes(arr) =>
              Task.fromFuture(subscriber.onNext(arr)).flatMap {
                case Continue =>
                  loop(subscriber, position + result)

                case Stop =>
                  Task.now(Bytes.emptyBytes)
              }
          }
        }
    }.getOrElse(Task.now(Bytes.emptyBytes))
  }

  private[nio] final def closeChannel()(implicit scheduler: Scheduler) =
    channel.foreach(_.close().runToFuture)
}
