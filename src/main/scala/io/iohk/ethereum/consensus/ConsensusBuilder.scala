package io.iohk.ethereum.consensus

import io.iohk.ethereum.nodebuilder.BlockchainConfigBuilder
import io.iohk.ethereum.validators.BlockHeaderValidator

import scala.concurrent.duration.FiniteDuration

// NOTE subsumes MinerBuilder, since the selection of consensus algorith will decide if it is needed.
trait ConsensusBuilder {
  self: BlockchainConfigBuilder =>

  // FIXME Create dynamically from configuration
  lazy val consensus: Consensus = new Consensus {
    def blockHeaderValidator: BlockHeaderValidator = ???
  }
}

// NOTE Subsumes MiningConfigBuilder
trait ConsensusConfigBuilder {
  // FIXME implement
  lazy val consensusConfig: ConsensusConfig = new ConsensusConfig {
    def activeTimeout: FiniteDuration = ???
  }
}
