package io.iohk.ethereum.consensus

import com.typesafe.config.{Config ⇒ TypesafeConfig}

import scala.concurrent.duration.{FiniteDuration, _}

trait ConsensusConfig {
  // @see [[io.iohk.ethereum.consensus.ethash.MiningConfig.activeTimeout]]
  def activeTimeout: FiniteDuration
}

object ConsensusConfig {
  def apply(etcClientConfig: TypesafeConfig): ConsensusConfig = {
    // FIXME Decide on the key and further structure
    val consenusConfig = etcClientConfig.getConfig("consensus")

    new ConsensusConfig {
      def activeTimeout: FiniteDuration = consenusConfig.getDuration("active-timeout").toMillis.millis
    }
  }
}
