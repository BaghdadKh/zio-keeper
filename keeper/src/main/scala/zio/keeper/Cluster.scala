package zio.keeper

import zio._
import zio.keeper.discovery.Discovery
import zio.keeper.transport.{ ChannelOut, Transport }
import zio.nio._
import zio.stream.Stream
import zio.keeper.SerializationError._

trait Cluster {
  def nodes: UIO[List[NodeId]]

  def send(data: Chunk[Byte], receipt: NodeId): IO[Error, Unit]

  def broadcast(data: Chunk[Byte]): IO[Error, Unit]

  def receive: Stream[Error, Message]
}

object Cluster {

  private val HeaderSize = 24

  def join[A](
    port: Int
  ): ZManaged[
    Credentials with Discovery with Transport with zio.console.Console with zio.clock.Clock with zio.random.Random,
    Error,
    Cluster
  ] =
    InternalCluster.initCluster(port)

  private[keeper] def readMessage(channel: ChannelOut) =
    channel.read.flatMap(
      headerBytes =>
        (for {
          byteBuffer             <- Buffer.byte(headerBytes)
          senderMostSignificant  <- byteBuffer.getLong
          senderLeastSignificant <- byteBuffer.getLong
          messageType            <- byteBuffer.getInt
          payloadByte            <- byteBuffer.getChunk()
          sender                 = NodeId(new java.util.UUID(senderMostSignificant, senderLeastSignificant))
        } yield (messageType, Message(sender, payloadByte)))
          .mapError(e => DeserializationTypeError[Message](e))
    )

  private[keeper] def serializeMessage(member: Member, payload: Chunk[Byte], messageType: Int): IO[Error, Chunk[Byte]] = {
    for {
      byteBuffer <- Buffer.byte(HeaderSize + payload.length)
      _          <- byteBuffer.putLong(member.nodeId.value.getMostSignificantBits)
      _          <- byteBuffer.putLong(member.nodeId.value.getLeastSignificantBits)
      _          <- byteBuffer.putInt(messageType)
      _          <- byteBuffer.putChunk(payload)
      _          <- byteBuffer.flip
      bytes      <- byteBuffer.getChunk()
    } yield bytes
  }.mapError(ex => SerializationTypeError[Message](ex))

  trait Credentials {
    // TODO: ways to obtain auth data
  }
}
