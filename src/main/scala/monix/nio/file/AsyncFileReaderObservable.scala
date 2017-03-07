package monix.nio.file

import monix.nio.{AsyncChannelObservable, AsyncMonixChannel}

final class AsyncFileReaderObservable(
  fileChannel: AsyncMonixChannel,
  size: Int) extends AsyncChannelObservable[AsyncMonixChannel] {

  override def bufferSize: Int = size

  override def channel: Option[AsyncMonixChannel] =
    Some(fileChannel)
}
