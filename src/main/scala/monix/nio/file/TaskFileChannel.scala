package monix.nio.file

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption

import monix.eval.Task
import monix.execution.{ Callback, Scheduler }

/**
  * A `Task` based asynchronous channel for reading, writing, and manipulating a file.
  *
  * On the JVM this is a wrapper around
  * [[https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html java.nio.channels.AsynchronousFileChannel]]
  * (class available since Java 7 for doing async I/O on files).
  *
  * @example {{{
  *   val out = TaskFileChannel(File.createTempFile, StandardOpenOption.CREATE)
  *
  *   val bytes = ByteBuffer.wrap("Hello world!".getBytes("UTF-8"))
  *
  *   out.write(bytes, 0).runAsync(new Callback[Int] {
  *     override def onError(ex: Throwable) = println(s"ERROR: " + ex.getMessage)
  *     override def onSuccess(value: Int) = println("Bytes written: %d".format(value))
  *   })
  * }}}
  * @define asyncFileChannelDesc The underlying [[monix.nio.file.AsyncFileChannel AsyncFileChannel]]
  * @define openDesc Returns `true` if this channel is open, or `false` otherwise.
  * @define readDesc Reads a sequence of bytes from this channel into
  *         the given buffer, starting at the given file position.
  * @define readDestDesc is the buffer holding the bytes read on completion
  * @define readPositionDesc is the position in the opened channel from where to read
  * @define callbackDesc is the callback to be called with the result, once
  *         this asynchronous operation is complete
  * @define readReturnDesc the number of bytes read or -1 if the given
  *         position is greater than or equal to the file's size at the
  *         time the read is attempted.
  * @define writeDesc Writes a sequence of bytes to this channel from
  *         the given buffer, starting at the given file position.
  *
  *         If the given position is greater than the file's size, at
  *         the time that the write is attempted, then the file will be
  *         grown to accommodate the new bytes; the values of any bytes
  *         between the previous end-of-file and the newly-written bytes
  *         are unspecified.
  * @define writeSrcDesc is the buffer holding the sequence of bytes to write
  * @define writePositionDesc is the position in file where to write,
  *         starts from 0, must be positive
  * @define writeReturnDesc the number of bytes that were written
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
  * @define flushWriteMetaDesc if `true` then this method is required
  *         to force changes to both the file's content and metadata
  *         to be written to storage; otherwise, it need only force
  *         content changes to be written
  * @define sizeDesc Returns the current size of this channel's file,
  *         measured in bytes.
  * @define closeDesc Closes this channel
  */
abstract class TaskFileChannel {

  /** $asyncFileChannelDesc */
  protected val asyncFileChannel: AsyncFileChannel

  /** $openDesc */
  def isOpen: Task[Boolean] =
    Task.now(asyncFileChannel.isOpen)

  /** $sizeDesc */
  def size: Task[Long] =
    Task.create { (scheduler, cb) =>
      asyncFileChannel.size(Callback.forked(cb)(scheduler))
    }

  /**
    * $readDesc
    *
    * @param dst $readDestDesc
    * @param position $readPositionDesc
    *
    * @return $readReturnDesc
    */
  def read(dst: ByteBuffer, position: Long): Task[Int] =
    Task.create { (scheduler, cb) =>
      asyncFileChannel.read(dst, position, Callback.forked(cb)(scheduler))
    }

  /**
    * $writeDesc
    *
    * @param src $writeSrcDesc
    * @param position $writePositionDesc
    *
    * @return $writeReturnDesc
    */
  def write(src: ByteBuffer, position: Long): Task[Int] =
    Task.create { (scheduler, cb) =>
      asyncFileChannel.write(src, position, Callback.forked(cb)(scheduler))
    }

  /**
    * $flushDesc
    *
    * @param writeMetaData $flushWriteMetaDesc
    */
  def flush(writeMetaData: Boolean): Task[Unit] =
    Task.create { (scheduler, cb) =>
      asyncFileChannel.flush(writeMetaData, Callback.forked(cb)(scheduler))
    }

  /** $closeDesc */
  def close(): Task[Unit] =
    Task.now(asyncFileChannel.close())
}

object TaskFileChannel {
  /**
    * Opens a channel for the given file reference, returning an
    * [[monix.nio.file.TaskFileChannel TaskFileChannel]] instance for handling reads and writes.
    *
    * @param file is the file reference to open
    * @param options specifies options for opening the file
    *        (e.g. create, append, etc.)
    *
    * @param s is the `Scheduler` used for asynchronous computations
    */
  def apply(file: File, options: StandardOpenOption*)(implicit s: Scheduler): TaskFileChannel = {
    new TaskFileChannel {
      override val asyncFileChannel = AsyncFileChannel.apply(file, options: _*)
    }
  }
}
