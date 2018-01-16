package io.iohk.ethereum.consensus

import io.iohk.ethereum.consensus.ethash.{EthashConsensus, MiningConfig}
import io.iohk.ethereum.nodebuilder.{BlockchainConfigBuilder, ShutdownHookBuilder}
import io.iohk.ethereum.utils.{Config, Logger}

/**
 * A consensus builder is responsible to instantiate the consensus protocol.
 */
trait ConsensusBuilder {
  /**
   * Specified
   */
  self: BlockchainConfigBuilder with ConsensusConfigBuilder with Logger =>

  private def loadEthashConsensus(): EthashConsensus = {
    val miningConfig = MiningConfig(Config.config)
    val consensus = new EthashConsensus(blockchainConfig, miningConfig)
    consensus
  }

  private def loadConsensus(): Consensus = {
    val config = consensusConfig
    val protocol = config.protocol
    log.info(s"Configured consensus protocol: ${protocol.name}")

    val consensus =
      config.protocol match {
        case Ethash ⇒ loadEthashConsensus()
        case DemoPoS ⇒ ??? // FIXME implement
      }
    log.info(s"${protocol.name} protocol implemented by ${consensus.getClass.getName}")

    consensus
  }

  lazy val consensus: Consensus = loadConsensus()
}

// NOTE Subsumes MiningConfigBuilder
trait ConsensusConfigBuilder { self: ShutdownHookBuilder ⇒
  lazy val consensusConfig: ConsensusConfig = ConsensusConfig(Config.config)(this)
}
