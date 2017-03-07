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

package monix.nio.file

import java.nio.ByteBuffer

import monix.eval.{ Callback, Task }
import monix.execution.Ack.{ Continue, Stop }
import monix.execution.{ Cancelable, UncaughtExceptionReporter }
import monix.execution.atomic.Atomic
import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.exceptions.APIContractViolationException
import monix.nio.AsyncMonixChannel
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.nio.file.internal._
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.util.control.NonFatal

class AsyncFileReaderObservable(channel: AsyncMonixChannel, size: Int)
    extends AsyncReadChannel(channel) with Observable[Array[Byte]] {
  private[this] val wasSubscribed = Atomic(false)
  private[this] val buffer = ByteBuffer.allocate(size)

  override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
    import subscriber.scheduler
    if (wasSubscribed.getAndSet(true)) {
      subscriber.onError(APIContractViolationException(this.getClass.getName))
      Cancelable.empty
    } else {
      try {
        val taskCallback = new Callback[Array[Byte]]() {
          override def onSuccess(value: Array[Byte]): Unit = {
            closeChannel()
          }

          override def onError(ex: Throwable): Unit = {
            closeChannel()
            subscriber.onError(ex)
          }
        }

        val c = Task.defer(loop(subscriber, 0))
          .executeWithOptions(_.enableAutoCancelableRunLoops)
          .runAsync(taskCallback)
        val singleFunctionCallCancelable = SingleFunctionCallCancelable(() => {
          closeChannel()
          c.cancel()
        })
        SingleAssignmentCancelable.plusOne(singleFunctionCallCancelable)
      } catch {
        case NonFatal(e) =>
          subscriber.onError(e)
          closeChannel()
          Cancelable.empty
      }
    }
  }

  def createReadTask(buff: ByteBuffer, position: Long) =
    Task.create[Int] { (scheduler, callback) =>
      try {
        read(buff, position, callback)
      } catch {
        case NonFatal(ex) => callback.onError(ex)
      }
      Cancelable(() => closeChannel()(scheduler))
    }

  def loop(
    subscriber: Subscriber[Array[Byte]],
    position: Long
  )(implicit rep: UncaughtExceptionReporter): Task[Array[Byte]] = {

    buffer.clear()
    createReadTask(buffer, position).flatMap { result =>
      val bytes = Bytes(buffer, result)
      bytes match {
        case EmptyBytes =>
          subscriber.onComplete()
          Task.now(Array.empty)

        case NonEmptyBytes(arr) =>
          Task.fromFuture(subscriber.onNext(arr)).flatMap {
            case Continue =>
              loop(subscriber, position + result)

            case Stop =>
              Task.now(Array.empty)
          }
      }

    }
  }

}
