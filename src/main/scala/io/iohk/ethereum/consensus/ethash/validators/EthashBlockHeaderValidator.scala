package io.iohk.ethereum.consensus.ethash
package validators

import io.iohk.ethereum.consensus.difficulty.DifficultyCalculator
import io.iohk.ethereum.consensus.ethash.difficulty.EthashDifficultyCalculator
import io.iohk.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import io.iohk.ethereum.consensus.validators.{BlockHeaderError, BlockHeaderValid, BlockHeaderValidatorSkeleton}
import io.iohk.ethereum.crypto
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.utils.BlockchainConfig

/**
 * A block header validator for Ethash.
 */
class EthashBlockHeaderValidator(blockchainConfig: BlockchainConfig) extends BlockHeaderValidatorSkeleton(blockchainConfig) {
  import EthashBlockHeaderValidator._

  // NOTE the below comment is from before PoW decoupling
  // we need concurrent map since validators can be used from multiple places
  protected val powCaches: java.util.concurrent.ConcurrentMap[Long, PowCacheData] = new java.util.concurrent.ConcurrentHashMap[Long, PowCacheData]()

  protected def difficulty: DifficultyCalculator = new EthashDifficultyCalculator(blockchainConfig)

  def validateEvenMore(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
    validatePoW(blockHeader)

  /**
   * Validates [[io.iohk.ethereum.domain.BlockHeader.nonce]] and [[io.iohk.ethereum.domain.BlockHeader.mixHash]] are correct
   * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
   *
   * @param blockHeader BlockHeader to validate.
   * @return BlockHeader if valid, an [[io.iohk.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError]] otherwise
   */
  protected def validatePoW(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] = {
    import EthashUtils._

    import scala.collection.JavaConverters._

    def getPowCacheData(epoch: Long): PowCacheData = {
      if (epoch == 0) EthashBlockHeaderValidator.epoch0PowCache else
        Option(powCaches.get(epoch)) match {
          case Some(pcd) => pcd
          case None =>
            val data = new PowCacheData(
              cache = EthashUtils.makeCache(epoch),
              dagSize = EthashUtils.dagSize(epoch))

            val keys = powCaches.keySet().asScala
            val keysToRemove = keys.toSeq.sorted.take(keys.size - MaxPowCaches + 1)
            keysToRemove.foreach(powCaches.remove)

            powCaches.put(epoch, data)

            data
        }
    }

    val powCacheData = getPowCacheData(epoch(blockHeader.number.toLong))

    val proofOfWork = hashimotoLight(crypto.kec256(BlockHeader.getEncodedWithoutNonce(blockHeader)),
      blockHeader.nonce.toArray[Byte], powCacheData.dagSize, powCacheData.cache)

    if (proofOfWork.mixHash == blockHeader.mixHash && checkDifficulty(blockHeader.difficulty.toLong, proofOfWork)) Right(BlockHeaderValid)
    else Left(HeaderPoWError)
  }
}

object EthashBlockHeaderValidator {
  final val MaxPowCaches: Int = 2 // maximum number of epochs for which PoW cache is stored in memory

  class PowCacheData(val cache: Array[Int], val dagSize: Long)

  // NOTE The below is from before PoW decoupling.
  // FIXME [EC-331]: this is used to speed up ETS Blockchain tests. All blocks in those tests have low numbers (1, 2, 3 ...)
  // so keeping the cache for epoch 0 avoids recalculating it for each individual test. The difference in test runtime
  // can be dramatic - full suite: 26 hours vs 21 minutes on same machine
  // It might be desirable to find a better solution for this - one that doesn't keep this cache unnecessarily
  final lazy val epoch0PowCache = new PowCacheData(
    cache = EthashUtils.makeCache(0),
    dagSize = EthashUtils.dagSize(0))
}
