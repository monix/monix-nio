package monix.nio

import java.nio.ByteBuffer

import monix.eval.Callback

trait AsyncMonixChannel extends AutoCloseable{
  def size(): Long
  def read(dst: ByteBuffer, position: Long, callback: Callback[Int])
  def write(b: ByteBuffer, position: Long, callback: Callback[Int])
  def close()
}
