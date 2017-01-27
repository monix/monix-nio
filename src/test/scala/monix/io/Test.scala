package monix.io

import java.nio.file.{Files, Paths}
import java.util
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.FunSuite
import file._
import monix.eval.Callback

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._


class Test extends FunSuite with LazyLogging{

  test("same file generated") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI())
    val to = Paths.get("src/test/resources/out.txt")
    val consumer = new AsyncFileWriterConsumer(to)
    val p = Promise[Boolean]()
    val callback = new Callback[Long] {
      override def onSuccess(value: Long): Unit = p.success(true)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3).consumeWith(consumer).runAsync(callback)
    val result = Await.result(p.future, 3.second)
    assert(result, true)

    val f1 = Files.readAllBytes(from)
    val f2 = Files.readAllBytes(to)
    assert(util.Arrays.equals(f1, f2))

    //clean
    Files.delete(to)
  }




}
