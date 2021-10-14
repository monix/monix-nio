package monix.nio.file

import java.io.File
import java.nio.file.{ Paths, WatchEvent }

import minitest.SimpleTestSuite
import monix.eval.Task
import monix.execution.Ack.{ Continue, Stop }
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
object WatchServiceTest extends SimpleTestSuite {
  test("file event captured") {
    val path = Paths.get(System.getProperty("java.io.tmpdir"))

    val watchP = Promise[Boolean]()
    val watchT = Task.evalAsync {
      watchAsync(path).timeoutOnSlowUpstream(10.seconds).subscribe(
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
        () => watchP.success(true))
    }
    val fileT = Task.evalAsync {
      val temp = File.createTempFile("monix", ".tmp", path.toFile)
      Thread.sleep(2000)
      temp.delete()
    }

    watchT.runToFuture
    fileT.runToFuture

    val result = Await.result(watchP.future, 20.seconds)
    assert(result)
  }

}
