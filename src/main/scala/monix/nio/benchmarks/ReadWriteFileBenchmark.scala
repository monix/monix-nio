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
package monix.nio.benchmarks

import java.nio.file.{ Files, Paths }
import java.util.concurrent.TimeUnit

import monix.nio.file._
import monix.reactive.Observable
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * Run the benchmark from within SBT:
  *
  *     jmh:run -i 10 -wi 1 -f 1 -t 1 monix.nio.benchmarks.ReadWriteFileBenchmark
  *
  * Which means "10 iterations", "1 warm-up iterations", "1 fork", "1 thread".
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ReadWriteFileBenchmark {

  implicit val ctx = monix.execution.Scheduler.io()

  val rawBytes = (0 until 52428800).map(_.toByte).toArray
  val chunks1 = rawBytes.grouped(1024).toList
  val chunks100 = rawBytes.grouped(102400).toList
  val chunks1000 = rawBytes.grouped(1048576).toList

  val from = {
    val file = new java.io.File(Paths.get(".").toFile, "monix-in.nio")
    if (file.exists()) {
      file.toPath
    } else {
      Files.write(file.toPath, rawBytes)
      file.toPath
    }
  }

  def createToFile() = {
    val file = java.io.File.createTempFile("monix-out", ".nio")
    file.deleteOnExit()
    file.toPath
  }

  @Benchmark
  def write50MiBJavaNio(): Unit = {
    val to = createToFile()
    Files.write(to, rawBytes)
  }

  @Benchmark
  def write50MiBWith1KiBChunks(): Unit = {
    val to = createToFile()
    val program = Observable
      .fromIterable(chunks1)
      .consumeWith(writeAsync(to))
    Await.result(program.runAsync, Duration.Inf)
  }

  @Benchmark
  def write50MiBWith100KiBChunks(): Unit = {
    val to = createToFile()
    val program = Observable
      .fromIterable(chunks100)
      .consumeWith(writeAsync(to))
    Await.result(program.runAsync, Duration.Inf)
  }

  @Benchmark
  def write50MiBWith1MiBChunks(): Unit = {
    val to = createToFile()
    val program = Observable
      .fromIterable(chunks1000)
      .consumeWith(writeAsync(to))
    Await.result(program.runAsync, Duration.Inf)
  }

  @Benchmark
  def write50MiB(): Unit = {
    val to = createToFile()
    val program = Observable
      .fromIterable(List(rawBytes))
      .consumeWith(writeAsync(to))
    Await.result(program.runAsync, Duration.Inf)
  }

  @Benchmark
  def read50MiBJavaNio(): Unit = {
    Files.readAllBytes(from)
  }

  @Benchmark
  def read50MiBWith1KiBChunks(): Unit = {
    val program = readAsync(from, 1024).foreach(_ => ())
    Await.result(program, Duration.Inf)
  }

  @Benchmark
  def read50MiBWith100KiBChunks(): Unit = {
    val program = readAsync(from, 102400).foreach(_ => ())
    Await.result(program, Duration.Inf)
  }

  @Benchmark
  def read50MiBWith1MiBChunks(): Unit = {
    val program = readAsync(from, 1048576).foreach(_ => ())
    Await.result(program, Duration.Inf)
  }

  @Benchmark
  def read50MiB(): Unit = {
    val program = readAsync(from, 52428800).foreach(_ => ())
    Await.result(program, Duration.Inf)
  }
}
