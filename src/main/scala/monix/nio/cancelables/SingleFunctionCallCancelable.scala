package monix.nio.cancelables

import monix.execution.Cancelable
import monix.execution.atomic.Atomic

case class SingleFunctionCallCancelable(onCancel: () => Unit) extends Cancelable {
  private val isCanceled = Atomic(false)
  override def cancel(): Unit = {
    val alreadyCanceled = isCanceled.getAndSet(true)
    if (!alreadyCanceled) onCancel()
  }
}
