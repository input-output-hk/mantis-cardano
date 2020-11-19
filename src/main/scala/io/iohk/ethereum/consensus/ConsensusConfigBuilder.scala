package io.iohk.ethereum.consensus

import io.iohk.ethereum.nodebuilder.NodeKeyBuilder
import io.iohk.ethereum.utils.Config

trait ConsensusConfigBuilder { self: NodeKeyBuilder ⇒
  protected def buildConsensusConfig(): ConsensusConfig = ConsensusConfig(Config.config, self.nodeKey)

  lazy val consensusConfig: ConsensusConfig = buildConsensusConfig()
}
