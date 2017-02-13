package monix.nio

import java.nio.file.{Files, Paths}
import java.util

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.FunSuite
import file._
import monix.eval.Callback
import monix.nio.text.UTF8Codec._
import monix.reactive.Observable

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._


class Test extends FunSuite with LazyLogging{

  test("same file generated") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)
    val to = Paths.get("src/test/resources/out.txt")
    val consumer = new AsyncFileWriterConsumer(to)
    val p = Promise[Boolean]()
    val callback = new Callback[Long] {
      override def onSuccess(value: Long): Unit = p.success(true)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3)
      .consumeWith(consumer)
      .runAsync(callback)
    val result = Await.result(p.future, 3.second)
    assert(result, true)

    val f1 = Files.readAllBytes(from)
    val f2 = Files.readAllBytes(to)
    assert(util.Arrays.equals(f1, f2))

    //clean
    Files.delete(to)
  }

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




}
