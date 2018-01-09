package io.iohk.ethereum.consensus.ethash

import io.iohk.ethereum.utils.Config

trait MiningConfigBuilder {
  lazy val miningConfig = MiningConfig(Config.config)
}
