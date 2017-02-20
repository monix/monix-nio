package monix.nio.cancelables

import monix.execution.Cancelable
import monix.execution.atomic.AtomicAny
import monix.execution.cancelables.AssignableCancelable
import monix.nio.cancelables.CallbackCancelable.State

import scala.annotation.tailrec

/**
  * Like SingleAssignmentCancelable represents a [[monix.execution.Cancelable]]
  * that can be assigned only once to another cancelable reference.
  *
  * In addition to it, supports a onCancel function that will be called once
  * when the first cancel action will occur.
  *
  * Any other cancel() call after the first call will no longer have as effect
  * the call on the onCancel function.
  *
  * If the assignment happens after the cancel() call, then then assignment cancelable will
  * be immediately canceled
  *
  * NOTE: In the case of multiple assignments an exception will be thrown which
  *       will imply that no more onCancel calls can occur afterwards
  *
  * {{{
  *   val s = CallbackCancellable(println("TTT")
 *
 *    s.cancel() ///TTT is printed
  *   s.cancel() ///nothing is printed
  * }}}
 *
  * @param onCancel: function to be called on the first cancel() invocation
  */
final class CallbackCancelable private (onCancel: () => Unit) extends AssignableCancelable {
  import State._

  /** Sets the underlying cancelable reference with `s`.
    *
    * In case this `SingleAssignmentCancelable` is already canceled,
    * then the reference `value` will also be canceled on assignment.
    *
    * Throws `IllegalStateException` in case this cancelable has already
    * been assigned.
    *
    * @return `this`
    */
  @throws(classOf[IllegalStateException])
  override def `:=`(value: Cancelable): this.type = {
    val win = state.compareAndSet(Empty, IsActive(value))

    if (!win) {
      val diffState = state.get
      diffState match {
        case IsEmptyCanceled => value.cancel()

        case IsCanceled  =>
          value.cancel()
          raiseError()

        case IsActive(v) if v!= value =>
          value.cancel()
          raiseError()

        case _ => ()//if we do the assignment twice with the same Cancelable
       }
    }

    this
  }

  @tailrec
  override def cancel(): Unit = {
    state.get match {
      case IsCanceled | IsEmptyCanceled => ()
      case ref@ IsActive(s) =>
        val oldState = state.getAndSet(IsCanceled)
        if (oldState == ref) {
          onCancel()
          s.cancel()
        }
      case Empty =>
        if (!state.compareAndSet(Empty, IsEmptyCanceled)) cancel()
        else onCancel()
    }
  }

  private def raiseError(): Nothing = {
    throw new IllegalStateException(
      "Cannot assign to CallbackCancellable, " +
        "as it was already assigned once")
  }

  private[this] val state = AtomicAny(Empty : State)
}

object CallbackCancelable {
  def  apply(onCancel: () => Unit): CallbackCancelable =
    new CallbackCancelable(onCancel)

  private sealed trait State
  private object State {
    case object Empty extends State
    case class IsActive(s: Cancelable) extends State
    case object IsCanceled extends State
    case object IsEmptyCanceled extends State
  }
}