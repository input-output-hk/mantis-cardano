package io.iohk.ethereum.transactions

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.util.{ByteString, Timeout}
import io.iohk.ethereum.domain.SignedTransaction
import io.iohk.ethereum.eventbus.event.NewPendingTransaction
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import io.iohk.ethereum.network.PeerEventBusActor.{PeerEvent, PeerSelector, Subscribe, SubscriptionClassifier}
import io.iohk.ethereum.network.PeerManagerActor.Peers
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer, PeerId, PeerManagerActor}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.SignedTransactions
import io.iohk.ethereum.utils.TxPoolConfig

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object TransactionPool {
  def props(txPoolConfig: TxPoolConfig, peerManager: ActorRef, etcPeerManager: ActorRef, peerMessageBus: ActorRef): Props =
    Props(new TransactionPool(txPoolConfig, peerManager, etcPeerManager, peerMessageBus))

  case class AddTransactions(signedTransactions: List[SignedTransaction])

  object AddTransactions{
    def apply(txs: SignedTransaction*): AddTransactions = AddTransactions(txs.toList)
  }

  case class AddOrOverrideTransaction(signedTransaction: SignedTransaction)

  private case class NotifyPeer(signedTransactions: Seq[SignedTransaction], peer: Peer)

  case object GetPendingTransactions
  case class PendingTransactionsResponse(pendingTransactions: Seq[PendingTransaction])

  case class RemoveTransactions(signedTransactions: Seq[SignedTransaction])

  case class PendingTransaction(stx: SignedTransaction, addTimestamp: Long)

  case object ClearPendingTransactions
}

class TransactionPool(txPoolConfig: TxPoolConfig, peerManager: ActorRef,
                                 etcPeerManager: ActorRef, peerEventBus: ActorRef) extends Actor {

  import TransactionPool._
  import akka.pattern.ask

  private val metrics = new TxPoolMetrics(Metrics.get(), getTxsRepeated _, getMaxProvisions _)

  /**
    * stores all pending transactions
   */
  var pendingTransactions: List[PendingTransaction] = Nil

  /**
    * stores information which tx hashes are "known" by which peers
    */
  var knownTransactions: Map[ByteString, Set[PeerId]] = Map.empty

  /**
    * stores transactions timeouts by tx hash
    */
  var timeouts: Map[ByteString, Cancellable] = Map.empty

  /**
    * How many times a TX has been provided for execution.
    * If greater than 1 it may indicate a problematic TX
    */
  val txProvisions: mutable.Map[ByteString, Int] = mutable.Map.empty.withDefaultValue(0)

  implicit val timeout = Timeout(3.seconds)

  peerEventBus ! Subscribe(SubscriptionClassifier.PeerHandshaked)
  peerEventBus ! Subscribe(MessageClassifier(Set(SignedTransactions.code), PeerSelector.AllPeers))

  // scalastyle:off method.length
  override def receive: Receive = {
    case PeerEvent.PeerHandshakeSuccessful(peer, _) =>
      self ! NotifyPeer(pendingTransactions.map(_.stx), peer)

    case AddTransactions(signedTransactions) =>
      val transactionsToAdd = signedTransactions.filterNot(t => pendingTransactions.map(_.stx).contains(t))
      if (transactionsToAdd.nonEmpty) {
        transactionsToAdd.foreach(setTimeout)
        val timestamp = System.currentTimeMillis()
        pendingTransactions = (transactionsToAdd.map(PendingTransaction(_, timestamp)) ++ pendingTransactions).take(txPoolConfig.txPoolSize)
        (peerManager ? PeerManagerActor.GetPeers).mapTo[Peers].foreach { peers =>
          peers.handshaked.foreach { peer => self ! NotifyPeer(transactionsToAdd, peer) }
        }
        transactionsToAdd.foreach(tx => context.system.eventStream.publish(NewPendingTransaction(tx.hash)))
      }

    case AddOrOverrideTransaction(newStx) =>
      val (obsoleteTxs, txsWithoutObsoletes) = pendingTransactions.partition(ptx =>
        ptx.stx.senderAddress == newStx.senderAddress &&
        ptx.stx.tx.nonce == newStx.tx.nonce)
      obsoleteTxs.map(_.stx).foreach(clearTimeout)

      val timestamp = System.currentTimeMillis()
      pendingTransactions = (PendingTransaction(newStx, timestamp) +: txsWithoutObsoletes).take(txPoolConfig.txPoolSize)
      setTimeout(newStx)

      (peerManager ? PeerManagerActor.GetPeers).mapTo[Peers].foreach { peers =>
        peers.handshaked.foreach { peer => self ! NotifyPeer(List(newStx), peer) }
      }
      context.system.eventStream.publish(NewPendingTransaction(newStx.hash))

    case NotifyPeer(signedTransactions, peer) =>
      val txsToNotify = signedTransactions
        .filter(stx => pendingTransactions.exists(_.stx.hash == stx.hash)) // signed transactions that are still pending
        .filterNot(isTxKnown(_, peer.id)) // and not known by peer

        if (txsToNotify.nonEmpty) {
          etcPeerManager ! EtcPeerManagerActor.SendMessage(SignedTransactions(txsToNotify), peer.id)
          txsToNotify.foreach(setTxKnown(_, peer.id))
        }

    case GetPendingTransactions =>
      pendingTransactions.foreach { ptx =>
        txProvisions(ptx.stx.hash) += 1
      }
      sender() ! PendingTransactionsResponse(pendingTransactions)

    case RemoveTransactions(signedTransactions) =>
      pendingTransactions = pendingTransactions.filterNot(pt => signedTransactions.contains(pt.stx))
      knownTransactions = knownTransactions.filterNot(signedTransactions.map(_.hash).contains)
      signedTransactions.foreach(clearTimeout)
      signedTransactions.foreach(txProvisions -= _.hash)

    case MessageFromPeer(SignedTransactions(signedTransactions), peerId) =>
      self ! AddTransactions(signedTransactions.toList)
      signedTransactions.foreach(setTxKnown(_, peerId))

    case ClearPendingTransactions =>
      pendingTransactions.foreach(ptx => clearTimeout(ptx.stx))
      pendingTransactions.foreach(txProvisions -= _.stx.hash)
      pendingTransactions = Nil
  }

  private def setTimeout(stx: SignedTransaction): Unit = {
    timeouts.get(stx.hash).map(_.cancel())
    val cancellable = context.system.scheduler.scheduleOnce(txPoolConfig.transactionTimeout, self, RemoveTransactions(Seq(stx)))
    timeouts += (stx.hash -> cancellable)
  }

  private def clearTimeout(stx: SignedTransaction): Unit = {
    timeouts.get(stx.hash).map(_.cancel())
    timeouts -= stx.hash
  }

  private def isTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Boolean =
    knownTransactions.getOrElse(signedTransaction.hash, Set.empty).contains(peerId)

  private def setTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Unit = {
    val currentPeers = knownTransactions.getOrElse(signedTransaction.hash, Set.empty)
    val newPeers = currentPeers + peerId
    knownTransactions += (signedTransaction.hash -> newPeers)
  }

  private def getMaxProvisions: Double =
    (0 +: txProvisions.values.toList).max

  private def getTxsRepeated: Double =
    txProvisions.count(_._2 > 1)

}
