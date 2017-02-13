package monix.nio.file.internal

import java.nio.ByteBuffer

private[file] object Bytes {
  def apply(bf: ByteBuffer, n: Int) = {
    if (n < 0) EmptyBytes
    else NonEmptyBytes(bf.array().take(n))
  }
}
private[file] trait Bytes
private[file] case class NonEmptyBytes(arr: Array[Byte]) extends Bytes
private[file] case object EmptyBytes extends Bytes