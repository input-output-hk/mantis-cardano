package io.iohk.ethereum.consensus
package ethash

import akka.util.ByteString
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.validators.BlockHeaderValidatorImpl
import com.typesafe.config.{Config =>TypesafeConfig}
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

trait MiningConfig {
  val ommersPoolSize: Int // NOTE was only used to instantiate OmmersPool
  val blockCacheSize: Int // NOTE only used in BlockGenerator
  val coinbase: Address
  // FIXME Remove
  // val activeTimeout: FiniteDuration // NOTE only used in EthService (??)
  val ommerPoolQueryTimeout: FiniteDuration
  val headerExtraData: ByteString // only used in BlockGenerator
  val miningEnabled: Boolean
  val ethashDir: String
  val mineRounds: Int
}

object MiningConfig {
  def apply(etcClientConfig: TypesafeConfig): MiningConfig = {
    val miningConfig = etcClientConfig.getConfig("mining")

    new MiningConfig {
      val coinbase: Address = Address(miningConfig.getString("coinbase"))
      val blockCacheSize: Int = miningConfig.getInt("block-cashe-size")
      val ommersPoolSize: Int = miningConfig.getInt("ommers-pool-size")
      // FIXME Remove
      val activeTimeout: FiniteDuration = miningConfig.getDuration("active-timeout").toMillis.millis
      val ommerPoolQueryTimeout: FiniteDuration = miningConfig.getDuration("ommer-pool-query-timeout").toMillis.millis
      override val headerExtraData: ByteString =
        ByteString(miningConfig
          .getString("header-extra-data").getBytes)
          .take(BlockHeaderValidatorImpl.MaxExtraDataSize)
      override val miningEnabled = miningConfig.getBoolean("mining-enabled")
      override val ethashDir = miningConfig.getString("ethash-dir")
      override val mineRounds = miningConfig.getInt("mine-rounds")
    }
  }
}
