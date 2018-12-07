package io.iohk.ethereum.jsonrpc

import akka.actor.ActorRef
import akka.util.{ByteString, Timeout}
import io.iohk.ethereum.blockchain.data.{AllocAccount, GenesisData, GenesisDataLoader}
import io.iohk.ethereum.consensus.ConsensusConfig
import io.iohk.ethereum.consensus.blocks._
import io.iohk.ethereum.domain.{Address, Block, BlockchainImpl, UInt256}
import io.iohk.ethereum.testmode.{TestLedgerWrapper, TestmodeConsensus}
import io.iohk.ethereum.transactions.TransactionPool
import io.iohk.ethereum.transactions.TransactionPool.PendingTransactionsResponse
import io.iohk.ethereum.utils.Logger
import org.spongycastle.util.encoders.Hex

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

object TestService {
  case class GenesisParams(author: ByteString, extraData: ByteString, gasLimit: BigInt, parentHash: ByteString, timestamp: ByteString)
  case class BlockchainParams(
      EIP150ForkBlock: BigInt,
      EIP158ForkBlock: BigInt,
      accountStartNonce: BigInt,
      allowFutureBlocks: Boolean,
      blockReward: BigInt,
      byzantiumForkBlock: BigInt,
      homesteadForkBlock: BigInt,
      maximumExtraDataSize: BigInt)
  case class PrecompiledAccountConfig(name: String)
  case class AccountConfig(precompiled: Option[PrecompiledAccountConfig], wei: BigInt)
  case class ChainParams(genesis: GenesisParams, blockchainParams: BlockchainParams, sealEngine: String, accounts: Map[ByteString, AccountConfig])

  case class SetChainParamsRequest(chainParams: ChainParams)
  case class SetChainParamsResponse()

  case class MineBlocksRequest(num: Int)
  case class MineBlocksResponse()

  case class ModifyTimestampRequest(timestamp: Long)
  case class ModifyTimestampResponse()

  case class RewindToBlockRequest(blockNum: Long)
  case class RewindToBlockResponse()

  case class SetEtherbaseRequest(etherbase: Address)
  case class SetEtherbaseResponse()
}

class TestService(
    blockchain: BlockchainImpl,
    txPool: ActorRef,
    consensusConfig: ConsensusConfig,
    consensus: TestmodeConsensus,
    testLedgerWrapper: TestLedgerWrapper)
  extends Logger {

  import TestService._
  import akka.pattern.ask

  private var etherbase: Address = consensusConfig.coinbase

  def setChainParams(request: SetChainParamsRequest): ServiceResponse[SetChainParamsResponse] = {
    val newBlockchainConfig = testLedgerWrapper.blockchainConfig.copy(
      homesteadBlockNumber = request.chainParams.blockchainParams.homesteadForkBlock,
      eip150BlockNumber = request.chainParams.blockchainParams.EIP150ForkBlock,
      accountStartNonce = UInt256(request.chainParams.blockchainParams.accountStartNonce)
    )

    val genesisData = GenesisData(
      nonce = ByteString(Hex.decode("00")),
      mixHash = None,
      difficulty = "0",
      extraData = request.chainParams.genesis.extraData,
      gasLimit = "0x" + request.chainParams.genesis.gasLimit.toString(16),
      coinbase = request.chainParams.genesis.author,
      timestamp = Hex.toHexString(request.chainParams.genesis.timestamp.toArray[Byte]),
      alloc = request.chainParams.accounts.map { case (addr, acc) => Hex.toHexString(addr.toArray[Byte]) -> AllocAccount(acc.wei.toString) })

    // remove current genesis (Try because it may not exist)
    Try(blockchain.removeBlock(blockchain.genesisHeader.hash, saveParentAsBestBlock = false))

    // load the new genesis
    val genesisDataLoader = new GenesisDataLoader(blockchain, newBlockchainConfig)
    genesisDataLoader.loadGenesisData(genesisData)

    // update test ledger with new config
    testLedgerWrapper.blockchainConfig = newBlockchainConfig

    Future.successful(Right(SetChainParamsResponse()))
  }

  def mineBlocks(request: MineBlocksRequest): ServiceResponse[MineBlocksResponse] = {
    def mineBlock(): Future[Unit] = {
      getBlockForMining(blockchain.getBestBlock()).map { blockForMining =>
        val res = testLedgerWrapper.ledger.importBlock(blockForMining.block)
        log.info("Block mining result: " + res)
        txPool ! TransactionPool.ClearPendingTransactions
        consensus.blockTimestamp += 1
      }
    }

    def doNTimesF(n: Int)(fn: () => Future[Unit]): Future[Unit] = fn().flatMap { res =>
      if (n <= 1) Future.successful(res)
      else doNTimesF(n - 1)(fn)
    }

    doNTimesF(request.num)(mineBlock _).map(_ => Right(MineBlocksResponse()))
  }

  def modifyTimestamp(request: ModifyTimestampRequest): ServiceResponse[ModifyTimestampResponse] = {
    consensus.blockTimestamp = request.timestamp
    Future.successful(Right(ModifyTimestampResponse()))
  }

  def rewindToBlock(request: RewindToBlockRequest): ServiceResponse[RewindToBlockResponse] = {
    txPool ! TransactionPool.ClearPendingTransactions
    (blockchain.getBestBlockNumber() until request.blockNum by -1).foreach { n =>
      blockchain.removeBlock(blockchain.getBlockHeaderByNumber(n).get.hash, saveParentAsBestBlock = false)
    }
    blockchain.saveBestBlockNumber(request.blockNum)
    Future.successful(Right(RewindToBlockResponse()))
  }

  def setEtherbase(req: SetEtherbaseRequest): ServiceResponse[SetEtherbaseResponse] = {
    etherbase = req.etherbase
    Future.successful(Right(SetEtherbaseResponse()))
  }

  private def getBlockForMining(parentBlock: Block): Future[PendingBlock] = {
    implicit val timeout = Timeout(5.seconds)
    (txPool ? TransactionPool.GetPendingTransactions)
      .mapTo[PendingTransactionsResponse]
      .recover { case _ => PendingTransactionsResponse(Nil) }
      .flatMap { pendingTxs =>
        consensus.blockGenerator.generateBlock(parentBlock, pendingTxs.pendingTransactions.map(_.stx), etherbase, Nil) match {
          case Right(pb) => Future.successful(pb)
          case Left(err) => Future.failed(new RuntimeException(s"Error while generating block for mining: $err"))
        }
      }
  }
}
