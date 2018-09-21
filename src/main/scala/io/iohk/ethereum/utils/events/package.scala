package io.iohk.ethereum.utils

import com.trueaccord.scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.network.PeerId

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// Gathers together event-related stuff, so that you can easily import them all at once
package object events {
  final type EventDSL = io.riemann.riemann.client.EventDSL

  def appendService(first: String, second: String): String =
    if(second.isEmpty)
      first
    else if(first.isEmpty)
      second
    else
      s"$first $second"

  def sendOnException(eventGen: Throwable ⇒ EventDSL)(action: ⇒ Unit): Unit = {
    Try(action) match {
      case Failure(exception) ⇒
        eventGen(exception).send()
      case Success(_) ⇒
    }
  }

  def sendOnFutureException[A](eventGen: Throwable ⇒ EventDSL)(fut: Future[A])(implicit executor: ExecutionContext): Unit = {
    fut.andThen {
      case Failure(exception) ⇒ eventGen(exception).send()
    }
  }

  implicit class RichEventDSL(val event: EventDSL) extends AnyVal {
    def update(f: EventDSL ⇒ EventDSL): EventDSL = f(event)

    def attribute(name: String, value: BigInt): EventDSL = event.attribute(name, value.toString)

    def attribute(name: String, value: Boolean): EventDSL = event.attribute(name, value.toString)

    def attribute(name: String, value: Int): EventDSL = event.attribute(name, value.toString)

    def attribute(name: String, value: Long): EventDSL = event.attribute(name, value.toString)

    def attribute(name: String, value: Double): EventDSL = event.attribute(name, value.toString)

    def attributeSet[A](name: String, items: Set[A], f: A ⇒ String = (x: A) ⇒ String.valueOf(x)): EventDSL =
      event.attribute(name, items.map(f).map(s ⇒ s""""$s"""").mkString("[", ", ", "]"))

    def attributeIfDefined[A](name: String, valueOpt: Option[A], f: A ⇒ String = (x: A) ⇒ String.valueOf(x)): EventDSL =
      valueOpt match {
        case Some(value) ⇒ event.attribute(name, f(value))
        case None ⇒ event
      }

    def timeTakenMs(ms: Long): EventDSL = attribute(EventAttr.TimeTakenMs, ms)

    def count(count: Int): EventDSL = attribute(EventAttr.Count, count)

    def count(count: Double): EventDSL = attribute(EventAttr.Count, count)

    def peerId(peerId: PeerId): EventDSL = event.attribute(EventAttr.PeerId, peerId.value)

    def protoMsg(companion: GeneratedMessageCompanion[_]): EventDSL = {
      val descriptor = companion.scalaDescriptor
      val protoName = descriptor.fullName
      val protoFile = descriptor.file.fullName

      event
        .attribute(EventAttr.ProtoName, protoName)
        .attribute(EventAttr.ProtoFile, protoFile)
    }

    def protoMsg[M <: GeneratedMessage with Message[M]](msg: M): EventDSL =
      protoMsg(msg.companion.asInstanceOf[GeneratedMessageCompanion[M]])

    def header(header: BlockHeader): EventDSL =
      event
        .attribute("header", header.number)
        .attribute("headerHex", "0x" + header.number.toString(16))
        .attribute("headerHash", "0x" + header.hashAsHexString)

    def block(block: Block): EventDSL =
      event
        .attribute("block", block.header.number)
        .attribute("blockHex", "0x" + block.header.number.toString(16))
        .attribute("blockHash", "0x" + block.header.hashAsHexString)

    def block(prefix: String, header: BlockHeader): EventDSL =
      event
        .attribute(s"${prefix}Block", header.number)
        .attribute(s"${prefix}BlockHex", "0x" + header.number.toString(16))
        .attribute(s"${prefix}BlockHash", "0x" + header.hashAsHexString)

    def block(prefix: String, block: Block): EventDSL = this.block(prefix, block.header)
  }

  object EventState {
    final val Critical = "critical"
    final val Error = "err"
    final val OK = "ok"
    final val Warning = "warning"
  }

  object EventTag {
    final val BlockForge = "blockForge"
    final val BlockImport = "blockImport"
    final val Close = "close"
    final val Exception = "exception"
    final val Metric = "metric"
    final val Finish = "finish"
    final val Start = "start"
    final val Unhandled = "unhandled"
  }

  object EventAttr {
    final val AlreadyValidated = "alreadyValidated"
    final val Count = "count"
    final val Error = "error"
    final val File = "file"
    final val Id = "id"
    final val PeerId = "peerId"
    final val ProtoFile = "protoFile"
    final val ProtoName = "protoName"
    final val Resource = "resource"
    final val ReceiptCount = "receiptCount"
    final val Status = "status"
    final val ThreadId = "threadId"
    final val ThreadName = "threadName"
    final val ThreadPriority = "threadPriority"
    final val TimeTakenMs = "timeTakenMs"
    final val TimeUnit = "timeUnit"
    final val TransactionCount = "transactionCount"
    final val Type = "type"
    final val Unit = "unit"
  }
}
