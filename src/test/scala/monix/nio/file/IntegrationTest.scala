/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.nio.file

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util

import minitest.SimpleTestSuite
import monix.execution.Callback
import monix.nio.file

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.control.NonFatal

object IntegrationTest extends SimpleTestSuite {
  test("same file generated") {
    implicit val ctx = monix.execution.Scheduler.Implicits.global

    val from = Paths.get(this.getClass.getResource("/testFiles/file.txt").toURI)
    val to = Paths.get("src/test/resources/out.txt")
    val consumer = file.writeAsync(to)
    val p = Promise[Boolean]()
    val callback = new Callback[Throwable, Long] {
      override def onSuccess(value: Long): Unit = p.success(true)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3)
      .consumeWith(consumer)
      .runAsync(callback)

    val result = Await.result(p.future, 3.second)
    assert(result)

    val f1 = Files.readAllBytes(from)
    val f2 = Files.readAllBytes(to)
    Files.delete(to) // clean
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
    val callback = new Callback[Throwable, Long] {
      override def onSuccess(value: Long): Unit = p.success(true)
      override def onError(ex: Throwable): Unit = p.failure(ex)
    }

    readAsync(from, 3)
      .consumeWith(consumer)
      .runAsync(callback)

    val result = Await.result(p.future, 3.second)
    assert(result)

    val f1 = Files.readAllBytes(from)
    val f2 = Files.readAllBytes(to)
    Files.delete(to) // clean

    val all1: Seq[Byte] = strSeq.flatMap(_.getBytes) ++ f1.toSeq
    assertEquals(all1, f2.toSeq)
  }
}
