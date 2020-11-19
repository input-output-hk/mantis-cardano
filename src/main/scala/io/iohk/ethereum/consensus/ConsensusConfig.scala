package io.iohk.ethereum.consensus

import akka.util.ByteString
import com.typesafe.config.{Config => TypesafeConfig}
import io.iohk.ethereum.consensus.validators.BlockHeaderValidator
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.utils.{ByteStringUtils, Logger}
import org.spongycastle.crypto.AsymmetricCipherKeyPair

import scala.collection.JavaConverters._


/**
 * Provides generic consensus configuration. Each consensus protocol implementation
 * will use its own specific configuration as well.
 *
 * @param protocol Designates the consensus protocol.
 * @param miningEnabled Provides support for generalized "mining". The exact semantics are up to the
 *                      specific consensus protocol implementation.
 */
final case class ConsensusConfig(
  protocol: Protocol,
  coinbase: Address,
  headerExtraData: ByteString, // only used in BlockGenerator
  blockCacheSize: Int, // only used in BlockGenerator
  miningEnabled: Boolean,
  minGasPrice: BigInt,
  requireSignedBlocks: Boolean,
  nodeKey: AsymmetricCipherKeyPair, //used to sign generated blocks
  fedPubKeys: Set[ByteString] //approved block miners
)

object ConsensusConfig extends Logger {
  object Keys {
    final val Consensus = "consensus"
    final val Protocol = "protocol"
    final val Coinbase = "coinbase"
    final val HeaderExtraData = "header-extra-data"
    final val BlockCacheSize = "block-cashe-size"
    final val MiningEnabled = "mining-enabled"
    final val RequireSignedBlocks = "require-signed-blocks"
    final val MinGasPrice = "min-gas-price"
    final val FedPubKeys = "federation-public-keys"
  }


  final val AllowedProtocols = Set(
    Protocol.Names.Ethash,
    Protocol.Names.AtomixRaft
  )

  final val AllowedProtocolsError = (s: String) ⇒ Keys.Consensus +
    " is configured as '" + s + "'" +
    " but it should be one of " +
    AllowedProtocols.map("'" + _ + "'").mkString(",")

  private def readProtocol(consensusConfig: TypesafeConfig): Protocol = {
    val protocol = consensusConfig.getString(Keys.Protocol)

    // If the consensus protocol is not a known one, then it is a fatal error
    // and the application must exit.
    if(!AllowedProtocols(protocol)) {
      val error = AllowedProtocolsError(protocol)
      throw new RuntimeException(error)
    }

    Protocol(protocol)
  }


  def apply(mantisConfig: TypesafeConfig, nodeKey: AsymmetricCipherKeyPair): ConsensusConfig = {
    val config = mantisConfig.getConfig(Keys.Consensus)

    val protocol = readProtocol(config)
    val coinbase = Address(config.getString(Keys.Coinbase))

    val headerExtraData = ByteString(config.getString(Keys.HeaderExtraData).getBytes)
      .take(BlockHeaderValidator.MaxExtraDataSize)
    val blockCacheSize = config.getInt(Keys.BlockCacheSize)
    val miningEnabled = config.getBoolean(Keys.MiningEnabled)
    val requireSignedBlocks = config.getBoolean(Keys.RequireSignedBlocks)
    val minGasPrice = BigInt(config.getString(Keys.MinGasPrice))
    val fedPubKeys = config.getStringList(Keys.FedPubKeys).asScala.map(ByteStringUtils.string2hash).toSet

    new ConsensusConfig(
      protocol = protocol,
      coinbase = coinbase,
      headerExtraData = headerExtraData,
      blockCacheSize = blockCacheSize,
      miningEnabled = miningEnabled,
      minGasPrice = minGasPrice,
      requireSignedBlocks = requireSignedBlocks,
      nodeKey = nodeKey,
      fedPubKeys = fedPubKeys
    )
  }
}
