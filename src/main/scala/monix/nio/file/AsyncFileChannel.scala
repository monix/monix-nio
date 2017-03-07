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

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.StandardOpenOption

import monix.eval.{ Callback, Task }
import monix.execution.{ Cancelable, Scheduler }
import monix.nio.file.internal.ExecutorServiceWrapper

import scala.concurrent.{ Future, Promise }
import scala.concurrent.blocking
import scala.util.control.NonFatal

/**
 * An asynchronous channel for reading, writing, and manipulating a file.
 *
 * On the JVM this is a wrapper around
 * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html java.nio.channels.AsynchronousFileChannel]]
 * (class available since Java 7 for doing async I/O on files).
 *
 * @example {{{
 *   val out = AsyncFileChannel(File.createTempFile, StandardOpenOption.CREATE)
 *
 *   val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
 *   val future = out.write(bytes, 0)
 *
 *   future.onComplete {
 *     case Success(nr) =>
 *       println("Bytes written: %d".format(nr))
 *
 *     case Failure(exc) =>
 *       println(s"ERROR: " + exc.getMessage)
 *   }
 * }}}
 *
 * @define readDesc Reads a sequence of bytes from this channel into
 *         the given buffer, starting at the given file position.
 *
 * @define readDestDesc is the buffer holding the bytes read on completion
 * @define readPositionDesc is the position in the opened channel from where to read
 *
 * @define callbackDesc is the callback to be called with the result, once
 *         this asynchronous operation is complete
 *
 * @define readReturnDesc the number of bytes read or -1 if the given
 *         position is greater than or equal to the file's size at the
 *         time the read is attempted.
 *
 * @define writeDesc Writes a sequence of bytes to this channel from
 *         the given buffer, starting at the given file position.
 *
 *         If the given position is greater than the file's size, at
 *         the time that the write is attempted, then the file will be
 *         grown to accommodate the new bytes; the values of any bytes
 *         between the previous end-of-file and the newly-written bytes
 *         are unspecified.
 *
 * @define writeSrcDesc is the buffer holding the sequence of bytes to write
 * @define writePositionDesc is the position in file where to write,
 *         starts from 0, must be positive
 *
 * @define writeReturnDesc the number of bytes that were written
 *
 * @define flushDesc Forces any updates to this channel's file to be
 *         written to the storage device that contains it.
 *
 *         Invoking this method might trigger an
 *         [[http://man7.org/linux/man-pages/man2/fdatasync.2.html fsync or fdatasync]]
 *         operation, which transfers all modified in-core data of
 *         the file to the disk device, so that all changed
 *         information can be retrieved even after the system crashed
 *         or was rebooted. If the `writeMetaData` is set to `true`,
 *         then this would be the equivalent of an `fsync` command,
 *         or `fdatasync` if set to false.
 *
 *         This method is only guaranteed to force changes that were
 *         made to this channel's file via the methods defined in
 *         this class.
 *
 * @define flushWriteMetaDesc if `true` then this method is required
 *         to force changes to both the file's content and metadata
 *         to be written to storage; otherwise, it need only force
 *         content changes to be written
 *
 * @define sizeDesc Returns the current size of this channel's file,
 *         measured in bytes.
 */
abstract class AsyncFileChannel extends AutoCloseable {
  /** Returns `true` if this channel is open, or `false` otherwise. */
  def isOpen: Boolean

  /** $sizeDesc */
  def size(cb: Callback[Long]): Unit

  /** $sizeDesc */
  def size: Future[Long] = {
    val p = Promise[Long]()
    size(Callback.fromPromise(p))
    p.future
  }

  /** $sizeDesc */
  def sizeL: Task[Long] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      size(Callback.async(cb))
    }

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param position $readPositionDesc
   * @param cb $callbackDesc. For this method it signals $readReturnDesc
   */
  def read(dst: ByteBuffer, position: Long, cb: Callback[Int]): Unit

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param position $readPositionDesc
   *
   * @return $readReturnDesc
   */
  def read(dst: ByteBuffer, position: Long): Future[Int] = {
    val p = Promise[Int]()
    read(dst, position, Callback.fromPromise(p))
    p.future
  }

  /**
   * $readDesc
   *
   * @param dst $readDestDesc
   * @param position $readPositionDesc
   *
   * @return $readReturnDesc
   */
  def readL(dst: ByteBuffer, position: Long): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      read(dst, position, Callback.async(cb))
    }

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param position $writePositionDesc
   * @param cb $callbackDesc. For this method it signals $writeReturnDesc
   */
  def write(src: ByteBuffer, position: Long, cb: Callback[Int]): Unit

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param position $writePositionDesc
   *
   * @return $writeReturnDesc
   */
  def write(src: ByteBuffer, position: Long): Future[Int] = {
    val p = Promise[Int]()
    write(src, position, Callback.fromPromise(p))
    p.future
  }

  /**
   * $writeDesc
   *
   * @param src $writeSrcDesc
   * @param position $writePositionDesc
   *
   * @return $writeReturnDesc
   */
  def writeL(src: ByteBuffer, position: Long): Task[Int] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      write(src, position, Callback.async(cb))
    }

  /**
   * $flushDesc
   *
   * @param writeMetaData $flushWriteMetaDesc
   * @param cb is a callback to be called when the asynchronous
   * operation succeeds, or for signaling errors
   */
  def flush(writeMetaData: Boolean, cb: Callback[Unit]): Unit

  /**
   * $flushDesc
   *
   * @param writeMetaData $flushWriteMetaDesc
   */
  def flush(writeMetaData: Boolean): Future[Unit] = {
    val p = Promise[Unit]()
    flush(writeMetaData, Callback.fromPromise(p))
    p.future
  }

  /**
   * $flushDesc
   *
   * @param writeMetaData $flushWriteMetaDesc
   */
  def flushL(writeMetaData: Boolean): Task[Unit] =
    Task.unsafeCreate { (context, cb) =>
      implicit val s = context.scheduler
      flush(writeMetaData, Callback.async(cb))
    }
}

object AsyncFileChannel {
  /**
   * Opens a channel for the given file reference, returning an
   * [[AsyncFileChannel]] instance for handling reads and writes.
   *
   * @param file is the file reference to open
   * @param options specifies options for opening the file
   *        (e.g. create, append, etc.)
   *
   * @param s is the `Scheduler` used for asynchronous computations
   */
  def apply(file: File, options: StandardOpenOption*)(implicit s: Scheduler): AsyncFileChannel = {
    import scala.collection.JavaConverters._
    new NewIOImplementation(
      AsynchronousFileChannel.open(
        file.toPath,
        options.toSet.asJava,
        ExecutorServiceWrapper(s)
      )
    )
  }

  /** Implementation for [[AsyncFileChannel]] that uses Java's NIO. */
  private final class NewIOImplementation(underlying: AsynchronousFileChannel)(implicit scheduler: Scheduler) extends AsyncFileChannel {

    override def isOpen: Boolean =
      underlying.isOpen

    override def size(cb: Callback[Long]): Unit =
      scheduler.executeAsync { () =>
        var streamErrors = true
        try {
          val size = underlying.size()
          streamErrors = false
          cb.onSuccess(size)
        } catch {
          case NonFatal(ex) =>
            if (streamErrors) cb.onError(ex)
            else scheduler.reportFailure(ex)
        }
      }

    private[this] val cancelable: Cancelable =
      Cancelable { () =>
        try underlying.close()
        catch { case NonFatal(ex) => scheduler.reportFailure(ex) }
      }

    def read(dst: ByteBuffer, position: Long, cb: Callback[Int]): Unit = {
      require(position >= 0, "position >= 0")
      require(!dst.isReadOnly, "!dst.isReadOnly")
      try {
        // Can throw NonReadableChannelException
        underlying.read(dst, position, cb, completionHandler)
      } catch {
        case NonFatal(ex) =>
          cb.onError(ex)
      }
    }

    def write(src: ByteBuffer, position: Long, cb: Callback[Int]): Unit = {
      require(position >= 0, "position >= 0")
      try underlying.write(src, position, cb, completionHandler)
      catch { case NonFatal(ex) => cb.onError(ex) }
    }

    override def flush(metaData: Boolean, cb: Callback[Unit]): Unit =
      scheduler.executeAsync { () =>
        try blocking {
          underlying.force(true)
          cb.onSuccess(())
        } catch {
          case NonFatal(ex) =>
            cb.onError(ex)
        }
      }

    override def close(): Unit =
      cancelable.cancel()

    private[this] val completionHandler =
      new CompletionHandler[Integer, Callback[Int]] {
        def completed(result: Integer, cb: Callback[Int]): Unit =
          cb.onSuccess(result)
        def failed(exc: Throwable, cb: Callback[Int]): Unit =
          cb.onError(exc)
      }
  }
}
