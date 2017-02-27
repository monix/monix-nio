package monix.nio

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.FunSuite
import file._
import monix.eval.Callback

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal


class IntegrationTest extends FunSuite with LazyLogging{
  test("same file generated") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)
    val to = Paths.get("src/test/resources/out.txt")
    val consumer = file.writeAsync(to)
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
    Files.delete(to)//clean
    assert(util.Arrays.equals(f1, f2))
  }

  test("add data to existing file") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)
    val to = Paths.get("src/test/resources/existing.txt")
    val strSeq = Seq("A", "\u0024", "\u00A2", "\u20AC", new String(Array(0xF0, 0x90, 0x8D, 0x88).map(_.toByte)), "B")

    try {
      Files.write(to, strSeq.flatMap(_.getBytes).toArray, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case NonFatal(e) => fail(s"got error: $e")
    }
    val consumer = file.appendAsync(to, Files.size(to))
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
    Files.delete(to)//clean
    val all1: Seq[Byte] = strSeq.flatMap(_.getBytes) ++ f1.toSeq
    assert(all1 === f2.toSeq)

  }

}
