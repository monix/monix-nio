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

package monix.nio.file.internal

import java.nio.ByteBuffer
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.{ Path, StandardOpenOption }
import java.util.concurrent.ExecutorService

import monix.eval.Callback
import monix.execution.exceptions.APIContractViolationException
import monix.nio.AsyncMonixChannel

import collection.JavaConverters._
import scala.util.control.NonFatal

private[file] object AsyncFileChannel {
  def openUnsafe(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit
  ) = try {

    AsyncFileChannelWrapper(AsynchronousFileChannel.open(path, options.asJava, service.orNull))
  } catch {
    case NonFatal(exc) =>
      onOpenError(exc)
      NotInitializedFileChannel
  }

  def openRead(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit = _ => ()
  ) = openUnsafe(path, options + StandardOpenOption.READ, service, onOpenError)

  def openWrite(
    path: Path,
    options: Set[StandardOpenOption],
    service: Option[ExecutorService],
    onOpenError: Throwable => Unit = _ => ()
  ) = openUnsafe(path, options + StandardOpenOption.WRITE + StandardOpenOption.CREATE, service, onOpenError)

}

private[internal] case object NotInitializedFileChannel extends AsyncMonixChannel {
  def size(): Long = 0
  def close() = ()
  override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
    callback.onError(APIContractViolationException(s"can't read on ${this.getClass.getName}"))
  override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit =
    callback.onError(APIContractViolationException(s"can't read on ${this.getClass.getName}"))
}

private[internal] case class AsyncFileChannelWrapper(
    asyncFileChannel: AsynchronousFileChannel
) extends AsyncMonixChannel {

  override def size() = asyncFileChannel.size()
  override def close() = asyncFileChannel.close()

  def read(dst: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.read(dst, position, attachment, handler)
  override def read(dst: ByteBuffer, position: Long, callback: Callback[Int]): Unit = {
    val handler = new CompletionHandler[Integer, Null] {
      override def completed(result: Integer, attachment: Null) = callback.onSuccess(result)
      override def failed(exc: Throwable, attachment: Null) = callback.onError(exc)
    }
    asyncFileChannel.read(dst, position, null, handler)
  }

  def write(b: ByteBuffer, position: Long, attachment: Null, handler: CompletionHandler[Integer, Null]) =
    asyncFileChannel.write(b, position, attachment, handler)
  override def write(b: ByteBuffer, position: Long, callback: Callback[Int]): Unit = {
    val handler = new CompletionHandler[Integer, Null] {
      override def completed(result: Integer, attachment: Null) = callback.onSuccess(result)
      override def failed(exc: Throwable, attachment: Null) = callback.onError(exc)
    }
    asyncFileChannel.write(b, position, null, handler)
  }
}
