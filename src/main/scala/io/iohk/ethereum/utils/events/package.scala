package io.iohk.ethereum.utils

import akka.util.ByteString
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.network.PeerId
import io.iohk.ethereum.vm.ProgramError
import org.spongycastle.util.encoders.Hex

// Gathers together event-related stuff, so that you can easily import them all at once
package object events {
  final type EventDSL = io.riemann.riemann.client.EventDSL

  implicit class RichEventDSL(val event: EventDSL) extends AnyVal {
    def attribute(name: String, value: BigInt): EventDSL = event.attribute(name, value.toString())

    def attribute(name: String, value: Int): EventDSL = event.attribute(name, value.toString)

    def attribute(name: String, value: Long): EventDSL = event.attribute(name, value.toString)

    def timeTakenMs(ms: Long): EventDSL = attribute(EventAttr.TimeTakenMs, ms)

    def count(count: Int): EventDSL = attribute(EventAttr.Count, count)

    def peerId(peerId: PeerId): EventDSL = event.attribute(EventAttr.PeerId, peerId.value)

    def programError(error: ProgramError): EventDSL = event.attribute(EventAttr.ProgramError, error.toString)

    def hexByteString(name: String, value: ByteString): EventDSL = {
      val valueHex = if(value.isEmpty) "" else "0x" + Hex.toHexString(value.toArray)
      event.attribute(name, valueHex)
    }

    def updateWith(f: EventDSL ⇒ EventDSL): EventDSL = f(event)

    def header(header: BlockHeader): EventDSL =
      event
        .attribute("block", header.number)
        .attribute("blockHex", "0x" + header.number.toString(16))
        .attribute("blockHash", "0x" + header.hashAsHexString)
        .attribute("blockGasLimit", header.gasLimit)
        .attribute("blockGasUsed", header.gasUsed)
        .attribute("blockDifficulty", header.difficulty)
        .attribute("blockBeneficiary", "0x" + Hex.toHexString(header.beneficiary.toArray[Byte]))


    def block(block: Block): EventDSL =
      event
        .attribute("block", block.header.number)
        .attribute("blockHex", "0x" + block.header.number.toString(16))
        .attribute("blockHash", "0x" + block.header.hashAsHexString)
        .attribute("blockTxCount", block.body.transactionList.size)
        .attribute("blockGasLimit", block.header.gasLimit)
        .attribute("blockGasUsed", block.header.gasUsed)
        .attribute("blockDifficulty", block.header.difficulty)
        .attribute("blockBeneficiary", "0x" + Hex.toHexString(block.header.beneficiary.toArray[Byte]))

    def block(prefix: String, header: BlockHeader): EventDSL =
      event
        .attribute(s"${prefix}Block", header.number)
        .attribute(s"${prefix}BlockHex", "0x" + header.number.toString(16))
        .attribute(s"${prefix}BlockHash", "0x" + header.hashAsHexString)
        .attribute(s"${prefix}BlockGasLimit", header.gasLimit)
        .attribute(s"${prefix}BlockGasUsed", header.gasUsed)
        .attribute(s"${prefix}BlockDifficulty", header.difficulty)
        .attribute(s"${prefix}BlockBeneficiary", "0x" + Hex.toHexString(header.beneficiary.toArray[Byte]))


    def block(prefix: String, block: Block): EventDSL = this.block(prefix, block.header)
  }

  object EventState {
    final val Critical = "critical"
    final val Error = "err"
    final val OK = "ok"
    final val Warning = "warning"
  }

  object EventTag {
    final val Batch = "batch"
    final val BlockForge = "blockForge"
    final val BlockImport = "blockImport"
    final val Exception = "exception"
    final val Metric = "metric"
    final val Finish = "finish"
    final val Start = "start"
    final val Unhandled = "unhandled"
  }

  object EventAttr {
    final val ActorRef = "actorRef"
    final val AppComponent = "appComponent"
    final val BatchIndex = "batchIndex"
    final val BatchSize = "batchSize"
    final val BlacklistPeerCount = "blacklistPeerCount"
    final val Count = "count"
    final val Dispatcher = "dispatcher"
    final val Error = "error"
    final val File = "file"
    final val Id = "id"
    final val IsBatch = "isBatch"
    final val MiningEnabled = "miningEnabled"
    final val NewRole = "newRole"
    final val OldRole = "oldRole"
    final val PeerCount = "peerCount"
    final val PeerId = "peerId"
    final val ProgramError = "programError"
    final val RequestMethod = "requestMethod"
    final val RequestJson = "requestJson"
    final val RequestObj = "requestObj"
    final val Resource = "resource"
    final val ResponseJson = "responseJson"
    final val ResponseObj = "responseObj"
    final val IP = "ip"
    final val Status = "status"
    final val ThreadId = "threadId"
    final val ThreadName = "threadName"
    final val ThreadPriority = "threadPriority"
    final val TimeTakenMs = "timeTakenMs"
    final val TimeUnit = "timeUnit"
    final val Type = "type"
    final val Unit = "unit"
    final val Uuid = "uuid"
  }
}
