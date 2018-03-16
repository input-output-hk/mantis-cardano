package io.iohk.ethereum.consensus.blocks

import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, Block, SignedTransaction}
import io.iohk.ethereum.ledger.BlockPreparationError

/**
 * We use a `BlockGenerator` to create the next block.
 * In a PoW setting, this is what a miner typically does.
 * In general, a [[BlockGenerator]] depends on and is provided by the
 * [[io.iohk.ethereum.consensus.Consensus Consensus]].
 *
 * @note This is generally a stateful object.
 *
 * @see [[io.iohk.ethereum.consensus.Consensus#blockGenerator Consensus#blockGenerator()]],
 *      [[io.iohk.ethereum.ledger.BlockPreparator BlockPreparator]]
 *
 */
trait BlockGenerator {
  /**
   * The type of consensus-specific data used in the block generation process.
   * For example, under [[io.iohk.ethereum.consensus.ethash.EthashConsensus EthashConsensus]],
   * this represents the [[io.iohk.ethereum.network.p2p.messages.PV62.BlockBody#uncleNodesList ommers]].
   */
  type X

  /** An empty `X` */
  def emptyX: X

  // FIXME This is currently used only for Ethash
  // @see [[io.iohk.ethereum.jsonrpc.EthService.submitWork]]
  def getPrepared(powHeaderHash: ByteString): Option[PendingBlock]

  /**
   * This function returns the block currently being mined block with highest timestamp
   */
  def getPendingBlock: Option[PendingBlock]

  def getPendingBlockAndState: Option[PendingBlockAndState]

  def blockTimestampProvider: BlockTimestampProvider

  // FIXME Rename to generateBlock, since "Mining" does not always apply
  def generateBlockForMining(
    parent: Block,
    transactions: Seq[SignedTransaction],
    beneficiary: Address,
    ommers: X // we call it `ommers` in order to remember what is needed in Ethash but in general it can be anything
  ): Either[BlockPreparationError, PendingBlock]
}

/**
 * Internal API, used for testing.
 *
 * This is a [[BlockGenerator]] API for the needs of the test suites.
 *
 * @see [[io.iohk.ethereum.consensus.TestConsensus TestConsensus]].
 */
trait TestBlockGenerator extends BlockGenerator {
  def withBlockTimestampProvider(blockTimestampProvider: BlockTimestampProvider): TestBlockGenerator
}
