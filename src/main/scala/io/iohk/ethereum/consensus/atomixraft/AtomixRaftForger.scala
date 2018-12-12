package io.iohk.ethereum.consensus
package atomixraft

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.util.concurrent.AtomicDouble
import io.iohk.ethereum.blockchain.sync.RegularSync
import io.iohk.ethereum.consensus.atomixraft.AtomixRaftForger._
import io.iohk.ethereum.consensus.atomixraft.blocks.AtomixRaftBlockGenerator
import io.iohk.ethereum.consensus.blocks.PendingBlock
import io.iohk.ethereum.domain.{Address, Block, Blockchain}
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.nodebuilder.Node
import io.iohk.ethereum.transactions.TransactionPool
import io.iohk.ethereum.transactions.TransactionPool.PendingTransactionsResponse
import io.iohk.ethereum.utils.events._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class AtomixRaftForger(
  blockchain: Blockchain,
  txPool: ActorRef,
  syncController: ActorRef,
  consensus: AtomixRaftConsensus,
  getTransactionFromPoolTimeout: FiniteDuration
) extends Actor with ActorLogging with EventSupport {

  private[this] val lastForgedBlockNumber = new AtomicDouble(-1.0)

  private[this] val metrics = new AtomixRaftForgerMetrics(Metrics.get(), () ⇒ lastForgedBlockNumber.get())

  override protected def postProcessEvent(event: EventDSL): EventDSL = event.tag("forger")

  def receive: Receive = stopped

  private def consensusConfig: ConsensusConfig = consensus.config.generic
  private def atomixRaftConfig: AtomixRaftConfig = consensus.config.specific
  private def coinbase: Address = consensusConfig.coinbase
  private def isLeader: Boolean = consensus.isLeader.getOrElse(false)
  private def blockGenerator: AtomixRaftBlockGenerator = consensus.blockGenerator

  private def scheduleOnce(delay: FiniteDuration, msg: Msg): Unit =
    context.system.scheduler.scheduleOnce(delay, self, msg)

  private def stopped: Receive = {
    case Init ⇒
      log.info("***** Forger initialized")

    case IAmTheLeader ⇒
      log.info("***** I am the leader, will start forging blocks")

      context become forging
      self ! StartForging
  }

  private def forging: Receive = {
    case StopForging ⇒ context become stopped
    case StartForging ⇒ startForging()
  }

  private def lostLeadership(): Unit = {
    log.info("***** Ouch, lost leadership")
    self ! StopForging
  }

  private def startForging(): Unit = {
    if(isLeader) {
      val parentBlock = blockchain.getBestBlock()

      val time0 = System.nanoTime()
      getBlock(parentBlock) onComplete {
        case Success(PendingBlock(block, _)) ⇒
          val dtNanos = System.nanoTime() - time0
          val dtMillis = TimeUnit.NANOSECONDS.toMillis(dtNanos)
          syncTheBlock(block, dtMillis)

        case Failure(ex) ⇒
          log.error(ex, "Unable to get block")
          scheduleOnce(atomixRaftConfig.blockForgingDelay, StartForging)
      }
    }
    else {
      lostLeadership()
    }
  }

  private def syncTheBlock(block: Block, dtMillis: Long): Unit = {
    if(isLeader) {
      log.info(s"***** Forged block ${block.idTag}")

      Event.ok("block forged")
        .metric(block.header.number.longValue)
        .attribute(EventAttr.TimeTakenMs, dtMillis)
        .block(block)
        .tag(EventTag.BlockForge)
        .send()

      syncController ! RegularSync.MinedBlock(block)

      lastForgedBlockNumber.set(block.header.number.doubleValue())
      metrics.LeaderForgedBlocksCounter.increment()

      scheduleOnce(atomixRaftConfig.blockForgingDelay, StartForging)
    }
    else {
      lostLeadership()
    }
  }

  private def getBlock(parentBlock: Block): Future[PendingBlock] = {
    val ffPendingBlock: Future[Future[PendingBlock]] =
      for {
        pendingTxResponse ← getTransactionsFromPool
      } yield {
        val pendingTransactions = pendingTxResponse.pendingTransactions.map(_.stx)

        val errorOrPendingBlock = blockGenerator.generateBlock(
          parent = parentBlock,
          transactions = pendingTransactions,
          beneficiary = coinbase,
          x = Nil
        )
        errorOrPendingBlock match {
          case Left(error) ⇒
            Future.failed(new RuntimeException(s"Error while generating block: $error"))

          case Right(pendingBlock) ⇒
            Future.successful(pendingBlock)
        }
      }

    ffPendingBlock.flatten
  }

  private def getTransactionsFromPool = {
    implicit val timeout: Timeout = getTransactionFromPoolTimeout

    (txPool ? TransactionPool.GetPendingTransactions).mapTo[PendingTransactionsResponse]
      .recover { case ex =>
        log.error(ex, "Failed to get transactions, forging block with empty transactions list")
        PendingTransactionsResponse(Nil)
      }
  }
}

object AtomixRaftForger {
  sealed trait Msg
  case object Init extends Msg
  case object IAmTheLeader extends Msg
  case object StartForging extends Msg
  case object StopForging extends Msg

  private def props(
    blockchain: Blockchain,
    txPool: ActorRef,
    syncController: ActorRef,
    consensus: AtomixRaftConsensus,
    getTransactionFromPoolTimeout: FiniteDuration
  ): Props =
    Props(
      new AtomixRaftForger(
        blockchain, txPool, syncController, consensus,
        getTransactionFromPoolTimeout
      )
    )

  private[atomixraft] def apply(node: Node): ActorRef = {
    node.consensus match {
      case consensus: AtomixRaftConsensus ⇒
        val minerProps = props(
          blockchain = node.blockchain,
          txPool = node.txPool,
          syncController = node.syncController,
          consensus = consensus,
          getTransactionFromPoolTimeout = node.txPoolConfig.getTransactionFromPoolTimeout
        )

        node.system.actorOf(minerProps)

      case consensus ⇒
        wrongConsensusArgument[AtomixRaftConsensus](consensus)
    }
  }
}
