package monix.nio

import java.nio.file.{Files, Paths}
import java.util

import monix.eval.Callback
import monix.nio.text.UTF8Codec.{utf8Decode, utf8Encode}
import monix.reactive.Observable
import org.scalatest.FunSuite
import file._

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class CodecTest extends FunSuite {

  test("decode file utf8") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/specialChars.txt").toURI)

    val p = Promise[Seq[Byte]]()
    val callback = new Callback[List[Array[Byte]]] {
      override def onSuccess(value: List[Array[Byte]]): Unit = p.success(value.flatten)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3)
      .pipeThrough(utf8Decode)
      .pipeThrough(utf8Encode)
      .toListL
      .runAsync(callback)
    val result = Await.result(p.future, 3.second)
    val f1 = Files.readAllBytes(from)
    val f2 = result
    assert(util.Arrays.equals(f1, f2.toArray))
  }

  test("decode special chars") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global
    val strSeq = Seq("A", "\u0024", "\u00A2", "\u20AC", new String(Array(0xF0, 0x90, 0x8D, 0x88).map(_.toByte)), "B")

    for (grouping <- 1 to 12) {
      val obsSeq =
        Observable
          .fromIterator(strSeq.flatMap(_.getBytes).grouped(grouping).map(_.toArray))
          .pipeThrough(utf8Decode)

      val p = Promise[Boolean]()
      val callback = new Callback[List[String]] {
        override def onSuccess(value: List[String]): Unit = {
          p.success(if (value.mkString == strSeq.mkString) true else false)
        }

        override def onError(ex: Throwable): Unit = p.failure(ex)
      }
      obsSeq.toListL.runAsync(callback)
      val result = Await.result(p.future, 3.second)
      if (!result) info(s"grouping=$grouping")
      assert(result)
    }
  }

  test("copy file utf8") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/specialChars.txt").toURI)
    val to = Paths.get("src/test/resources/res.txt")
    val consumer = file.writeAsync(to)
    val p = Promise[Long]()
    val callback = new Callback[Long] {
      override def onSuccess(value: Long): Unit = p.success(value)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3)
      .pipeThrough(utf8Decode)
      .map{ str =>
        //Console.println(str)
        str
      }
      .pipeThrough(utf8Encode)
      .consumeWith(consumer)
      .runAsync(callback)
    val result = Await.result(p.future, 3.second)
    val f1 = Files.readAllBytes(from)
    val f2 = result
    Files.delete(to)
    assert(f1.size === f2)
  }
}
