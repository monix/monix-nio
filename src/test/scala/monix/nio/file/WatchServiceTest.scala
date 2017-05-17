package monix.nio.file

import java.nio.file.{ Paths, WatchEvent }

object MyApp extends App {
  implicit val ctx = monix.execution.Scheduler.Implicits.global

  val path = Paths.get("/tmp")

  def printEvent(event: WatchEvent[_]): Unit = {
    val name = event.context().toString
    val fullPath = path.resolve(name)
    println(s"${event.kind().name()} - $fullPath")
  }

  watchAsync(path)
    .foreach(p => p.foreach(printEvent))
}