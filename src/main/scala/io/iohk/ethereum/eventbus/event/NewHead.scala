package io.iohk.ethereum.eventbus.event

import akka.util.ByteString
import io.iohk.ethereum.domain.Block

case class NewHead(blockRemoved: Seq[Block], blocksAdded: Seq[Block])
case class NewPendingTransaction(transactionHash: ByteString)
