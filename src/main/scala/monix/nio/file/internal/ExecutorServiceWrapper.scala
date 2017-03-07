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

package monix.nio.file.internal

import java.util
import java.util.concurrent.{AbstractExecutorService, ExecutorService, TimeUnit}
import monix.execution.schedulers.{ReferenceScheduler, SchedulerService}
import monix.execution.{Cancelable, ExecutionModel, Scheduler}
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutorService}

/** Wraps a Monix `Scheduler` into a Java `ExecutorService`.
  *
  * Can work with Monix's `SchedulerService` in order to provide
  * `shutdown` operations, however this is optional.
  */
private[file] final class ExecutorServiceWrapper(scheduler: Scheduler)
  extends AbstractExecutorService with ExecutionContextExecutorService {

  override def execute(runnable: Runnable): Unit =
    scheduler.execute(runnable)

  override def reportFailure(cause: Throwable): Unit =
    scheduler.reportFailure(cause)

  override def shutdown(): Unit =
    scheduler match {
      case ref: SchedulerService => ref.shutdown()
      case _ => () // do nothing
    }

  override def isTerminated: Boolean =
    scheduler match {
      case ref: SchedulerService => ref.isTerminated
      case _ => false
    }

  override def isShutdown: Boolean =
    scheduler match {
      case ref: SchedulerService => ref.isShutdown
      case _ => false
    }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
    scheduler match {
      case ref: SchedulerService =>
        import ExecutorServiceWrapper.currentThread
        Await.result(ref.awaitTermination(timeout, unit, currentThread), Duration.Inf)
      case _ =>
        false
    }

  override def shutdownNow(): util.List[Runnable] = {
    shutdown()
    Nil.asJava
  }
}

private[file] object ExecutorServiceWrapper {
  /** Builds an [[ExecutorServiceWrapper]] instance. */
  def apply(s: Scheduler): ExecutorService =
    new ExecutorServiceWrapper(s)

  /** `Scheduler` instance that executes `Runnables` immediately,
    * used when blocking in [[ExecutorServiceWrapper.awaitTermination]],
    * in order to avoid initializing an actual `Scheduler`.
    */
  private val currentThread: Scheduler =
    new ReferenceScheduler {
      import monix.execution.Scheduler.global
      def execute(r: Runnable): Unit = r.run()
      def reportFailure(t: Throwable): Unit = throw t
      def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable =
        global.scheduleOnce(initialDelay, unit, r)
      def executionModel: ExecutionModel =
        ExecutionModel.Default
    }
}
