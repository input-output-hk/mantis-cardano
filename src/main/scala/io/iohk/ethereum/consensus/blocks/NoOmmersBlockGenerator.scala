package io.iohk.ethereum.consensus.blocks

import io.iohk.ethereum.consensus.ConsensusConfig
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger.{BlockPreparationError, BlockPreparator}
import io.iohk.ethereum.network.p2p.messages.PV62.BlockBody
import io.iohk.ethereum.utils.BlockchainConfig


abstract class NoOmmersBlockGenerator(
  blockchain: Blockchain,
  blockchainConfig: BlockchainConfig,
  consensusConfig: ConsensusConfig,
  blockPreparator: BlockPreparator,
  blockTimestampProvider: BlockTimestampProvider = DefaultBlockTimestampProvider
) extends BlockGeneratorSkeleton(
  blockchain,
  blockchainConfig,
  consensusConfig,
  blockPreparator,
  blockTimestampProvider
) {

  type X = Nil.type

  protected def newBlockBody(transactions: Seq[SignedTransaction], x: Nil.type): BlockBody =
    BlockBody(transactions, x)

  protected def prepareHeader(
    blockNumber: BigInt,
    parent: Block,
    beneficiary: Address,
    blockTimestamp: Long,
    x: Nil.type
  ): BlockHeader =
    defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)


  /** An empty `X` */
  def emptyX: Nil.type = Nil

  def generateBlock(
    parent: Block,
    transactions: Seq[SignedTransaction],
    beneficiary: Address,
    x: Nil.type
  ): Either[BlockPreparationError, PendingBlock] = {

    val pHeader = parent.header
    val blockNumber = pHeader.number + 1

    val prepared = addSignatureIfRequired(
      prepareBlock(parent, transactions, beneficiary, blockNumber, blockPreparator, x)
    )
    cache.updateAndGet((t: List[PendingBlockAndState]) => (prepared :: t).take(blockCacheSize))

    Right(prepared.pendingBlock)
  }

  private def addSignatureIfRequired(pendingBlockAndState: PendingBlockAndState): PendingBlockAndState = {
    if (!consensusConfig.requireSignedBlocks)
      pendingBlockAndState
    else {
      val header = pendingBlockAndState.pendingBlock.block.header
      val signature = BlockHeader.sign(header, consensusConfig.nodeKey)
      val signedHeader = header.copy(signature = Some(signature))
      val signedBlock = pendingBlockAndState.pendingBlock.block.copy(header = signedHeader)
      val signedPendingBlock = pendingBlockAndState.pendingBlock.copy(block = signedBlock)
      pendingBlockAndState.copy(pendingBlock = signedPendingBlock)
    }
  }
}
