package io.iohk.ethereum.extvm

import java.math.BigInteger

import akka.stream.scaladsl.{SinkQueueWithCancel, SourceQueueWithComplete}
import akka.util.ByteString
import com.google.protobuf.CodedInputStream
import com.trueaccord.scalapb.{GeneratedMessage, GeneratedMessageCompanion, LiteParser, Message}
import io.iohk.ethereum.utils.events._
import org.spongycastle.util.BigIntegers

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

class MessageHandler(in: SinkQueueWithCancel[ByteString], out: SourceQueueWithComplete[ByteString]) extends EventSupport {

  private val AwaitTimeout = 5.minutes // FIXME configurable

  protected def mainService: String = "msg handler"

  def sendMessage[M <: GeneratedMessage](msg: M): Unit = {
    val bytes = msg.toByteArray
    val lengthBytes = ByteString(BigIntegers.asUnsignedByteArray(LengthPrefixSize, BigInteger.valueOf(bytes.length)))

    val offerF = out offer (lengthBytes ++ ByteString(bytes))

    sendOnFutureException(
      Event.exception("send", _).protoMsg(msg.companion)
    )(offerF)
  }

  def awaitMessage[M <: GeneratedMessage with Message[M]](implicit companion: GeneratedMessageCompanion[M]): M = {
    val resF = in.pull() map {
      case Some(bytes) => LiteParser.parseFrom(companion, CodedInputStream.newInstance(bytes.toArray[Byte]))
      case None =>
        val descriptor = companion.scalaDescriptor
        val protoName = descriptor.fullName
        val fileName = descriptor.file.fullName
        throw new RuntimeException(s"Stream completed while trying to parse $protoName [$fileName]")
    }

    sendOnFutureException(
      Event.exception("await", _).protoMsg(companion)
    )(resF)

    Await.result(resF, AwaitTimeout)
  }

  def close(): Unit = {
    Try(in.cancel())
    Try(out.complete())
  }

}
