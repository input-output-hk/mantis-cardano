package io.iohk.ethereum.consensus.atomixraft.validators

import io.iohk.ethereum.consensus.ConsensusConfig
import io.iohk.ethereum.consensus.validators.std.{StdBlockValidator, StdSignedTransactionValidator, StdValidators}
import io.iohk.ethereum.utils.{BlockchainConfig, VmConfig}


object AtomixRaftValidators {
  def apply(blockchainConfig: BlockchainConfig, vmConfig: VmConfig, consensusConfig: ConsensusConfig): AtomixRaftValidators = {
    new StdValidators(
      new StdBlockValidator(blockchainConfig.ethCompatibilityMode),
      new AtomixRaftBlockHeaderValidator(blockchainConfig, consensusConfig),
      new StdSignedTransactionValidator(blockchainConfig, vmConfig)
    )
  }
}
