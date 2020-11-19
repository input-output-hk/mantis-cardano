package io.iohk.ethereum.consensus

import akka.util.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.Timeouts
import io.iohk.ethereum.consensus.ethash.EthashConfig
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.utils.ByteStringUtils

/** Provides utility values used throughout tests */
object ConsensusConfigs {
  final val blockCacheSize = 30
  final val coinbaseAddressNum = 42
  final val coinbase = Address(coinbaseAddressNum)

  //noinspection ScalaStyle
  final val ethashConfig = new EthashConfig(
    ommersPoolSize = 30,
    ommerPoolQueryTimeout = Timeouts.normalTimeout,
    ethashDir = "~/.ethash",
    mineRounds = 100000
  )

  final val consensusConfig: ConsensusConfig = new ConsensusConfig(
    protocol = Protocol.Ethash,
    coinbase = coinbase,
    headerExtraData = ByteString.empty,
    blockCacheSize = blockCacheSize,
    miningEnabled = false,
    minGasPrice = 0,
    requireSignedBlocks = false,
    nodeKey = crypto.keyPairFromPrvKey(ByteStringUtils.string2hash(
      "238b5c902db6c37d337ec008bcb96cb1d34333986e83836662011b38a496921d").toArray),
    fedPubKeys = Set(
      "1f6f122edb80a7b23b9629467c0e60214dcb9006d58dbe875ee47b191d698d1bd3c85c440ce7695a1793eb6acc65ff2b7133aec024beafef4d0e52b01defbb66",
      "b5236e1604434341782798845b0f57ecc0de0b4acd744fc98daf9410862b1d75511dd96929bfc982856f81771e48b1d4cd8e17b6fafa3eff1d93dd437a45ca84",
      "a23b9b8985bd9ce4955b0643ac5c0487b9cf2e26db2ae70c0ced23a383c0eaf72131b5c9c99febfa55f94aa24fba7ecb8d982f93e5a56ab803d3018e230c4b54"
    ).map(ByteStringUtils.string2hash)
  )

  final val fullConsensusConfig = FullConsensusConfig(consensusConfig, ethashConfig)
}
