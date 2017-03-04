package monix.nio

import java.nio.ByteBuffer

private[nio] object Bytes {
  def apply(bf: ByteBuffer, n: Int) = {
    if (n < 0) EmptyBytes
    else NonEmptyBytes(bf.array().take(n))
  }
}
private[nio] sealed trait Bytes
private[nio] case class NonEmptyBytes(arr: Array[Byte]) extends Bytes
private[nio] case object EmptyBytes extends Bytes