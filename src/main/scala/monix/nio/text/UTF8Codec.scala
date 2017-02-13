package monix.nio.text

import java.nio.ByteBuffer
import java.nio.charset.Charset

import monix.execution.Ack.{Continue, Stop}
import monix.execution.atomic.Atomic
import monix.execution.{Ack, Cancelable}
import monix.reactive.exceptions.MultipleSubscribersException
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.Subject
import monix.reactive.{Observable, Observer, Pipe}

import scala.concurrent.Future

object UTF8Codec {
  case object utf8Decode extends Pipe[Array[Byte], String]{
    override def unicast: (Observer[Array[Byte]], Observable[String]) = {
      val p = new UTF8DecodingSubject()
      (p,p)
    }
  }

  case object utf8Encode extends Pipe[String, Array[Byte]] {
    override def unicast: (Observer[String], Observable[Array[Byte]]) = {
      val p = new UTF8EncodingSubject()
      (p,p)
    }
  }
  private val utf8Charset = Charset.forName("UTF-8")

  private[text] class UTF8DecodingSubject extends Subject[Array[Byte], String] {
    private[this] val subscriber = Atomic(Option.empty[Subscriber[String]])
    private[this] val stopOnNext = Atomic(false)

    val remaining = ByteBuffer.allocate(4)

    override def size: Int =
      if (subscriber.get.nonEmpty) 1 else 0

    override def unsafeSubscribeFn(subscriber: Subscriber[String]): Cancelable = {
      if (!this.subscriber.compareAndSet(None, Some(subscriber))) {
        subscriber.onError(MultipleSubscribersException(this.getClass.getName))
        Cancelable.empty
      } else {
        remaining.put(0,0)
        Cancelable(() => stopOnNext.set(true))
      }
    }

    override def onError(ex: Throwable): Unit = subscriber.get.foreach(_.onError(ex))
    override def onComplete(): Unit = subscriber.get.foreach(_.onComplete())

    override def onNext(elem: Array[Byte]): Future[Ack] = {
      if (stopOnNext.get || subscriber.get.isEmpty) {
        //we stop if no subscriber or canceled from outside
        Stop
      } else {
        val remainingArray = remaining.array()
        val oldRemaining =
          if (remainingArray(0) == 0) Array.empty[Byte]
          else remainingArray.slice(1, 1+remainingArray(0))
        val (current, newRemaining) = getString(elem, oldRemaining)
        remaining.clear()
        remaining.put(newRemaining.length.toByte)
        remaining.put(newRemaining)
        current match {
          case Some(str) => subscriber.get.get.onNext(str)
          case _ => Continue
        }
      }
    }

    private def getString(elem: Array[Byte], oldBytes: Array[Byte]): (Option[String], Array[Byte]) = {
      val bytes = oldBytes ++ elem
      val splitAt = getSplitAt(bytes)

      splitAt match {
        case Some(split) if split == 0 =>
          val newRemaining = bytes
          (None, newRemaining)

        case Some(split) if split > 0 =>

          val str = new String(bytes.take(split), utf8Charset)
          val newRemaining = bytes.drop(split)
          (Some(str), newRemaining)

        case _ =>
          val str = new String(bytes, utf8Charset)
          (Some(str), Array.empty[Byte])
      }
    }

    private def indexIncrement(b: Byte): Int  = {
      if ((b & 0x80) == 0) 0 // ASCII byte
      else if ((b & 0xE0) == 0xC0) 2 // first of a 2 byte seq
      else if ((b & 0xF0) == 0xE0) 3 // first of a 3 byte seq
      else if ((b & 0xF8) == 0xF0) 4 // first of a 4 byte seq
      else 0 // following char
    }

    private def getSplitAt(bytes: Array[Byte]) = {
      val lastThree = bytes.drop(0 max bytes.length - 3)
      val addBytesFromLast3 = lastThree.zipWithIndex.foldLeft(Option.empty[Int]){ (acc, elem) =>
        val increment = indexIncrement(elem._1)
        val index = elem._2
        if (index + increment > lastThree.length) {
          Some(index)
        } else {
          acc
        }
      }
      addBytesFromLast3 map (bytes.length + _ - lastThree.length)
    }
  }

  private[text] class UTF8EncodingSubject extends Subject[String, Array[Byte]] {
    private[this] val subscriber = Atomic(Option.empty[Subscriber[Array[Byte]]])
    private[this] val stopOnNext = Atomic(false)

    override def size: Int = if (subscriber.get.nonEmpty) 1 else 0

    override def unsafeSubscribeFn(subscriber: Subscriber[Array[Byte]]): Cancelable = {
      if (!this.subscriber.compareAndSet(None, Some(subscriber))) {
        subscriber.onError(MultipleSubscribersException(this.getClass.getName))
        Cancelable.empty
      } else {
        Cancelable(() => stopOnNext.set(true))
      }
    }

    override def onError(ex: Throwable): Unit = subscriber.get.foreach(_.onError(ex))
    override def onComplete(): Unit = subscriber.get.foreach(_.onComplete())

    override def onNext(elem: String): Future[Ack] = {
      if (stopOnNext.get || subscriber.get.isEmpty) {
        //we stop if no subscriber or canceled from outside
        Stop
      }
      else {
        subscriber.get.get.onNext(elem.getBytes(utf8Charset))
      }
    }
  }
}
