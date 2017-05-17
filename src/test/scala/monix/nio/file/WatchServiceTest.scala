package monix.nio.file

import java.io.File
import java.nio.file.{ Paths, WatchEvent }

import minitest.SimpleTestSuite
import monix.eval.Task
import monix.execution.Ack.{ Continue, Stop }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
object WatchServiceTest extends SimpleTestSuite {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  test("file event captured") {
    val path = Paths.get(System.getProperty("java.io.tmpdir"))

    val watchP = Promise[Boolean]()
    val watchT = Task {
      watchAsync(path).timeoutOnSlowUpstream(5.seconds).subscribe(
        (events: Array[WatchEvent[_]]) => {
          val captured = events.find(e => s"${e.kind().name()} - ${e.context().toString}".contains("monix"))
          if (captured.isDefined) {
            watchP.success(true)
            Stop
          } else {
            Continue
          }
        },
        err => watchP.failure(err),
        () => watchP.success(true)
      )
    }
    val fileT = Task {
      val temp = File.createTempFile("monix", ".tmp", path.toFile)
      Thread.sleep(2000)
      temp.delete()
    }

    watchT.runAsync
    fileT.runAsync

    val result = Await.result(watchP.future, 10.seconds)
    assert(result)
  }

}