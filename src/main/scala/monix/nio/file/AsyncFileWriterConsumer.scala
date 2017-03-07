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

import monix.eval.Callback
import monix.execution.Ack.{ Continue, Stop }
import monix.execution.{ Ack, Scheduler }
import monix.execution.atomic.Atomic
import monix.execution.cancelables.{ AssignableCancelable, SingleAssignmentCancelable }
import monix.nio.AsyncMonixChannel
import monix.nio.cancelables.SingleFunctionCallCancelable
import monix.nio.file.internal.{ AsyncMonixFileChannel, AsyncWriterChannel }
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.{ Future, Promise }

class AsyncFileWriterConsumer(channel: AsyncMonixChannel, startPosition: Long = 0) extends Consumer[Array[Byte], Long] {

  override def createSubscriber(
    cb: Callback[Long],
    s: Scheduler
  ): (Subscriber[Array[Byte]], AssignableCancelable) = {

    class AsyncFileSubscriber extends AsyncWriterChannel(channel) with Subscriber[Array[Byte]] {
      implicit val scheduler = s

      private[this] var position = startPosition
      final private val callbackCalled = Atomic(false)

      private def sendError(t: Throwable) =
        if (callbackCalled.compareAndSet(false, true))
          s.execute(new Runnable { def run() = cb.onError(t) })

      def onComplete(): Unit = {
        closeChannel()
        if (callbackCalled.compareAndSet(false, true))
          cb.onSuccess(position)
      }

      def onError(ex: Throwable): Unit = {
        closeChannel()
        sendError(ex)
      }

      override def onNext(elem: Array[Byte]): Future[Ack] = {
        val promise = Promise[Ack]()

        write(ByteBuffer.wrap(elem), position, new Callback[Int] {
          def onError(exc: Throwable) = {
            //We have an ERROR we STOP the consumer
            closeChannel()
            sendError(exc)
            promise.success(Stop) //stop input
          }

          def onSuccess(result: Int): Unit = {
            position += result.toInt
            promise.success(Continue)
          }
        })

        promise.future
      }

      def onCancel(): Unit = {
        callbackCalled.set(true) //the callback should not be called after cancel
        closeChannel()
      }
    }

    val out = new AsyncFileSubscriber()

    val myCancelable = SingleFunctionCallCancelable(out.onCancel _)
    val conn = SingleAssignmentCancelable.plusOne(myCancelable)
    (out, conn)
  }
}
