package io.iohk.ethereum.transactions

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, SignedTransaction, Transaction}
import io.iohk.ethereum.network.PeerActor.Status.Handshaked
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerManagerActor.Peers
import io.iohk.ethereum.network.handshaker.Handshaker.HandshakeResult
import io.iohk.ethereum.network.p2p.messages.CommonMessages.SignedTransactions
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer, PeerId, PeerManagerActor}
import io.iohk.ethereum.transactions.TransactionPool._
import io.iohk.ethereum.{NormalPatience, Timeouts, crypto}
import io.iohk.ethereum.nodebuilder.SecureRandomBuilder
import io.iohk.ethereum.utils.TxPoolConfig
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}
import org.spongycastle.crypto.AsymmetricCipherKeyPair

import scala.concurrent.duration._

class TransactionPoolSpec extends FlatSpec with Matchers with ScalaFutures with NormalPatience {

  "TransactionPool" should "store pending transactions received from peers" in new TestSetup {
    val msg = SignedTransactions((1 to 10).map(e => newStx(e)))
    txPool ! MessageFromPeer(msg, PeerId("1"))

    Thread.sleep(Timeouts.normalTimeout.toMillis)

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg.txs.toSet
  }

  it should "ignore known transaction" in new TestSetup {
    val msg = SignedTransactions(Seq(newStx(1)))
    txPool ! MessageFromPeer(msg, PeerId("1"))
    txPool ! MessageFromPeer(msg, PeerId("2"))

    Thread.sleep(Timeouts.normalTimeout.toMillis)

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx).length shouldBe 1
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg.txs.toSet
  }

  it should "broadcast received pending transactions to other peers" in new TestSetup {
    val stx = newStx()
    txPool ! AddTransactions(stx)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx)), peer2.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx)), peer3.id)
    )

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx) shouldBe Seq(stx)
  }

  it should "notify other peers about received transactions and handle removal" in new TestSetup {
    val msg1 = SignedTransactions(Seq.fill(10)(newStx()))
    txPool ! MessageFromPeer(msg1, peer1.id)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))
    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(msg1.txs), peer2.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(msg1.txs), peer3.id)
    )
    etcPeerManager.expectNoMsg()

    val msg2 = SignedTransactions(Seq.fill(5)(newStx()))
    txPool ! MessageFromPeer(msg2, peer2.id)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))
    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(msg2.txs), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(msg2.txs), peer3.id)
    )
    etcPeerManager.expectNoMsg()

    txPool ! RemoveTransactions(msg1.txs.dropRight(4))
    txPool ! RemoveTransactions(msg2.txs.drop(2))

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.size shouldBe 6
    pendingTxs.pendingTransactions.map(_.stx) shouldBe msg2.txs.take(2) ++ msg1.txs.takeRight(4)
  }

  it should "not add pending transaction again when it was removed while waiting for peers" in new TestSetup {
    val msg1 = SignedTransactions(Seq(newStx(1)))
    txPool ! MessageFromPeer(msg1, peer1.id)
    Thread.sleep(Timeouts.normalTimeout.toMillis)
    txPool ! RemoveTransactions(msg1.txs)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

    etcPeerManager.expectNoMsg()

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.size shouldBe 0
  }

  it should "override transactions with the same sender and nonce" in new TestSetup {
    val firstTx = newStx(1, tx, keyPair1)
    val otherTx = newStx(1, tx, keyPair2)
    val overrideTx = newStx(1, tx.copy(value = 2 * tx.value), keyPair1)

    txPool ! AddOrOverrideTransaction(firstTx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))
    Thread.sleep(Timeouts.shortTimeout.toMillis)

    txPool ! AddOrOverrideTransaction(otherTx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))
    Thread.sleep(Timeouts.shortTimeout.toMillis)

    txPool ! AddOrOverrideTransaction(overrideTx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))
    Thread.sleep(Timeouts.shortTimeout.toMillis)

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse]
      .futureValue.pendingTransactions

    pendingTxs.map(_.stx) shouldEqual List(overrideTx, otherTx)

    // overriden TX will still be broadcast to peers
    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(firstTx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(otherTx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(overrideTx)), peer1.id)
    )
  }

  it should "broadcast pending transactions to newly connected peers" in new TestSetup {
    val stx = newStx()
    txPool ! AddTransactions(stx)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map.empty))

    txPool ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})

    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx)), peer1.id))
  }

  it should "remove transaction on timeout" in new TestSetup {
    override val txPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 300
      override val transactionTimeout: FiniteDuration = 500.millis
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout

      //unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
    }

    override val txPool = system.actorOf(
      TransactionPool.props(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref))

    val stx = newStx()
    txPool ! AddTransactions(stx)

    val pendingTxs = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)

    Thread.sleep(550)

    val pendingTxsAfter = (txPool ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxsAfter.pendingTransactions.map(_.stx).toSet shouldBe Set.empty
  }

  trait TestSetup extends SecureRandomBuilder {
    implicit val system = ActorSystem("test-system")

    val keyPair1 = crypto.generateKeyPair(secureRandom)
    val keyPair2 = crypto.generateKeyPair(secureRandom)

    val tx = Transaction(1, 1, 1, Some(Address(42)), 10, ByteString(""))

    def newStx(nonce: BigInt = 0, tx: Transaction = tx, keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)): SignedTransaction =
      SignedTransaction.sign(tx, keyPair, Some(0x3d))

    val peer1TestProbe = TestProbe()
    val peer1 = Peer(new InetSocketAddress("127.0.0.1", 9000), peer1TestProbe.ref, false)
    val peer2TestProbe = TestProbe()
    val peer2 = Peer(new InetSocketAddress("127.0.0.2", 9000), peer2TestProbe.ref, false)
    val peer3TestProbe = TestProbe()
    val peer3 = Peer(new InetSocketAddress("127.0.0.3", 9000), peer3TestProbe.ref, false)

    val txPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 300

      //unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
    }

    val peerManager = TestProbe()
    val etcPeerManager = TestProbe()
    val peerMessageBus = TestProbe()
    val txPool = system.actorOf(
      TransactionPool.props(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref))
  }

}
