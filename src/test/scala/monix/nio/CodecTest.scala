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

package monix.nio

import java.nio.file.{ Files, Paths }
import java.util
import monix.eval.Callback
import monix.nio.text.UTF8Codec.{ utf8Decode, utf8Encode }
import monix.execution.Scheduler.Implicits.{ global => ctx }
import monix.reactive.Observable
import file._
import minitest.SimpleTestSuite
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._

object CodecTest extends SimpleTestSuite {
  test("decode file utf8") {
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
      assert(result)
    }
  }

  test("copy file utf8") {
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
      .map { str =>
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
    assertEquals(f1.size, f2)
  }
}
