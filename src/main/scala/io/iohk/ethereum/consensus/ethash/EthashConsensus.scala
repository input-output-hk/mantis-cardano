package io.iohk.ethereum.consensus
package ethash

import akka.util.ByteString
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.utils.BlockchainConfig
import io.iohk.ethereum.validators.{BlockHeaderError, BlockHeaderValid, BlockHeaderValidator, BlockHeaderValidatorImpl}

/**
 * Implements standard Ethereum consensus.
 */
class EthashConsensus(blockchainConfig: BlockchainConfig) extends Consensus {
  private[this] val defaultValidator = new BlockHeaderValidatorImpl(blockchainConfig)
  private[this] val powValidator = new validators.BlockHeaderValidatorImpl(blockchainConfig)

  private[this] val ethashValidator = new BlockHeaderValidator {
    def validate(
      blockHeader: BlockHeader,
      getBlockHeaderByHash: ByteString ⇒ Option[BlockHeader]
    ): Either[BlockHeaderError, BlockHeaderValid] = {

      for {
        _ ← defaultValidator.validate(blockHeader, getBlockHeaderByHash)
        _ ← powValidator.validate(blockHeader, getBlockHeaderByHash)
      } yield BlockHeaderValid
    }
  }

  override def blockHeaderValidator: BlockHeaderValidator = ethashValidator
}
