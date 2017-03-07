package monix.nio.file

import monix.nio.{AsyncChannelConsumer, AsyncMonixChannel}


class AsyncFileWriterConsumer(
  asyncMonixChannel: AsyncMonixChannel,
  startPosition: Long = 0) extends AsyncChannelConsumer[AsyncMonixChannel] {

  override def withInitialPosition = startPosition
  override protected def channel: Option[AsyncMonixChannel] = Some(asyncMonixChannel)
}
