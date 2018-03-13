package io.iohk.ethereum.network.handshaker

import akka.agent.Agent
import akka.util.ByteString
import io.iohk.ethereum.Fixtures
import io.iohk.ethereum.blockchain.sync.EphemBlockchainTestSetup
import io.iohk.ethereum.crypto.generateKeyPair
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain._
import io.iohk.ethereum.network.ForkResolver
import io.iohk.ethereum.network.PeerManagerActor.PeerConfiguration
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.handshaker.Handshaker.HandshakeComplete.{HandshakeFailure, HandshakeSuccess}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status.StatusEnc
import io.iohk.ethereum.network.p2p.messages.PV62.GetBlockHeaders.GetBlockHeadersEnc
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockHeaders, GetBlockHeaders}
import io.iohk.ethereum.network.p2p.messages.Versions
import io.iohk.ethereum.network.p2p.messages.WireProtocol.Hello.HelloEnc
import io.iohk.ethereum.network.p2p.messages.WireProtocol.{Capability, Disconnect, Hello}
import io.iohk.ethereum.nodebuilder.SecureRandomBuilder
import io.iohk.ethereum.utils._
import org.scalatest.{FlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex

import scala.concurrent.ExecutionContext.Implicits.global

class EtcHandshakerSpec extends FlatSpec with Matchers  {

  it should "correctly connect during an apropiate handshake if no fork resolver is used" in new TestSetup
    with LocalPeerSetup with RemotePeerSetup {

    initHandshakerWithoutResolver.nextMessage.map(_.messageToSend) shouldBe Right(localHello: HelloEnc)
    val handshakerAfterHelloOpt = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)
    handshakerAfterHelloOpt.get.nextMessage.map(_.messageToSend) shouldBe Right(localStatus: StatusEnc)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus)
    assert(handshakerAfterStatusOpt.isDefined)

    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeSuccess(PeerInfo(initialStatus, totalDifficulty, forkAccepted, currentMaxBlockNumber))) =>
        initialStatus shouldBe remoteStatus
        totalDifficulty shouldBe remoteStatus.totalDifficulty
        currentMaxBlockNumber shouldBe 0
        forkAccepted shouldBe true
      case _ => fail
    }
  }

  it should "send status with total difficulty" in new TestSetup
    with LocalPeerSetup with RemotePeerSetup {

    val newTotalDifficulty = genesisBlock.header.difficulty + firstBlock.header.difficulty

    blockchain.save(firstBlock, Nil, newTotalDifficulty, saveAsBestBlock = true)

    val newLocalStatus =
      localStatus.copy(totalDifficulty = newTotalDifficulty, bestHash = firstBlock.header.hash)

    initHandshakerWithoutResolver.nextMessage.map(_.messageToSend) shouldBe Right(localHello: HelloEnc)
    val handshakerAfterHelloOpt = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)
    handshakerAfterHelloOpt.get.nextMessage.map(_.messageToSend) shouldBe Right(newLocalStatus: StatusEnc)
  }

  it should "correctly connect during an apropiate handshake if a fork resolver is used and the remote peer has the DAO block" in new TestSetup
    with LocalPeerSetup with RemotePeerSetup {

    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus)
    assert(handshakerAfterStatusOpt.isDefined)
    handshakerAfterStatusOpt.get.nextMessage.map(_.messageToSend) shouldBe Right(localGetBlockHeadersRequest: GetBlockHeadersEnc)
    val handshakerAfterForkOpt = handshakerAfterStatusOpt.get.applyMessage(BlockHeaders(Seq(forkBlockHeader)))
    assert(handshakerAfterForkOpt.isDefined)

    handshakerAfterForkOpt.get.nextMessage match {
      case Left(HandshakeSuccess(PeerInfo(initialStatus, totalDifficulty, forkAccepted, currentMaxBlockNumber))) =>
        initialStatus shouldBe remoteStatus
        totalDifficulty shouldBe remoteStatus.totalDifficulty
        currentMaxBlockNumber shouldBe forkBlockHeader.number
        forkAccepted shouldBe true
      case _ => fail
    }
  }

  it should "correctly connect during an apropiate handshake if a fork resolver is used and the remote peer doesn't have the DAO block" in new TestSetup
    with LocalPeerSetup with RemotePeerSetup {

    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus)
    assert(handshakerAfterStatusOpt.isDefined)
    handshakerAfterStatusOpt.get.nextMessage.map(_.messageToSend) shouldBe Right(localGetBlockHeadersRequest: GetBlockHeadersEnc)
    val handshakerAfterFork = handshakerAfterStatusOpt.get.applyMessage(BlockHeaders(Nil))
    assert(handshakerAfterStatusOpt.isDefined)

    handshakerAfterFork.get.nextMessage match {
      case Left(HandshakeSuccess(PeerInfo(initialStatus, totalDifficulty, forkAccepted, currentMaxBlockNumber))) =>
        initialStatus shouldBe remoteStatus
        totalDifficulty shouldBe remoteStatus.totalDifficulty
        currentMaxBlockNumber shouldBe 0
        forkAccepted shouldBe false
      case _ => fail
    }
  }

  it should "fail if a timeout happened during hello exchange" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val handshakerAfterTimeout = initHandshakerWithoutResolver.processTimeout
    handshakerAfterTimeout.nextMessage.map(_.messageToSend) shouldBe Left(HandshakeFailure(Disconnect.Reasons.TimeoutOnReceivingAMessage))
  }

  it should "fail if a timeout happened during status exchange" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterTimeout = handshakerAfterHelloOpt.get.processTimeout
    handshakerAfterTimeout.nextMessage.map(_.messageToSend) shouldBe Left(HandshakeFailure(Disconnect.Reasons.TimeoutOnReceivingAMessage))
  }

  it should "fail if a timeout happened during fork block exchange" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus)
    val handshakerAfterTimeout = handshakerAfterStatusOpt.get.processTimeout
    handshakerAfterTimeout.nextMessage.map(_.messageToSend) shouldBe Left(HandshakeFailure(Disconnect.Reasons.TimeoutOnReceivingAMessage))
  }

  it should "fail if a status msg is received with invalid network id" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val wrongNetworkId = localStatus.networkId + 1

    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus.copy(networkId = wrongNetworkId))
    handshakerAfterStatusOpt.get.nextMessage.map(_.messageToSend) shouldBe Left(HandshakeFailure(Disconnect.Reasons.DisconnectRequested))
  }

  it should "fail if a status msg is received with invalid genesisHash" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val wrongGenesisHash = (localStatus.genesisHash.head + 1).toByte +: localStatus.genesisHash.tail

    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus.copy(genesisHash = wrongGenesisHash))
    handshakerAfterStatusOpt.get.nextMessage.map(_.messageToSend) shouldBe Left(HandshakeFailure(Disconnect.Reasons.DisconnectRequested))
  }

  it should "fail if the remote peer doesn't support PV63" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val pv62Capability = Capability("eth", Versions.PV62.toByte)
    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello.copy(capabilities = Seq(pv62Capability)))
    assert(handshakerAfterHelloOpt.isDefined)
    handshakerAfterHelloOpt.get.nextMessage.leftSide shouldBe Left(HandshakeFailure(Disconnect.Reasons.IncompatibleP2pProtocolVersion))
  }

  it should "fail if a fork resolver is used and the block from the remote peer isn't accepted" in new TestSetup with LocalPeerSetup with RemotePeerSetup {
    val handshakerAfterHelloOpt = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt = handshakerAfterHelloOpt.get.applyMessage(remoteStatus)
    val handshakerAfterForkBlockOpt = handshakerAfterStatusOpt.get.applyMessage(BlockHeaders(Seq(genesisBlock.header.copy(number = forkBlockHeader.number))))
    assert(handshakerAfterForkBlockOpt.isDefined)
    handshakerAfterForkBlockOpt.get.nextMessage.leftSide shouldBe Left(HandshakeFailure(Disconnect.Reasons.UselessPeer))
  }

  trait TestSetup extends SecureRandomBuilder with EphemBlockchainTestSetup {

    val genesisBlock = Block(
      Fixtures.Blocks.Genesis.header,
      Fixtures.Blocks.Genesis.body
    )

    val forkBlockHeader = Fixtures.Blocks.DaoForkBlock.header

    blockchain.save(genesisBlock, Nil, genesisBlock.header.difficulty, saveAsBestBlock = true)

    val nodeStatus = NodeStatus(key = generateKeyPair(secureRandom), serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)
    lazy val nodeStatusHolder = Agent(nodeStatus)

    class MockEtcHandshakerConfiguration extends EtcHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = None
      override val nodeStatusHolder: Agent[NodeStatus] = TestSetup.this.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = Config.Network.peer
      override val blockchain: Blockchain = TestSetup.this.blockchain
      override val appStateStorage: AppStateStorage = TestSetup.this.storagesInstance.storages.appStateStorage
    }

    override lazy val blockchainConfig = new BlockchainConfig {
      //unused
      override val maxCodeSize: Option[BigInt] = None
      override val frontierBlockNumber: BigInt = 0
      override val homesteadBlockNumber: BigInt = 0
      override val eip150BlockNumber: BigInt = 0
      override val eip160BlockNumber: BigInt = 0
      override val eip155BlockNumber: BigInt = 0
      override val eip161BlockNumber: BigInt = 0
      override val eip106BlockNumber: BigInt = 0
      override val difficultyBombPauseBlockNumber: BigInt = 0
      override val difficultyBombContinueBlockNumber: BigInt = 0
      override val customGenesisFileOpt: Option[String] = None
      override val chainId: Byte = 0.toByte
      override val monetaryPolicyConfig: MonetaryPolicyConfig = null
      override val accountStartNonce: UInt256 = UInt256.Zero
      override val daoForkConfig: Option[DaoForkConfig] = Some(new DaoForkConfig {
        override val blockExtraData: Option[ByteString] = None
        override val range: Int = 10
        override val drainList: Seq[Address] = Nil
        override val forkBlockHash: ByteString = ByteString(Hex.decode("94365e3a8c0b35089c1d1195081fe7489b528a84b22199c916180db8b28ade7f"))
        override val forkBlockNumber: BigInt = 1920000
        override val refundContract: Option[Address] = None
      })
      val gasTieBreaker: Boolean = false
    }

    val etcHandshakerConfigurationWithResolver = new MockEtcHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = Some(new ForkResolver.EtcForkResolver(blockchainConfig.daoForkConfig.get))
    }

    val initHandshakerWithoutResolver = EtcHandshaker(new MockEtcHandshakerConfiguration)
    val initHandshakerWithResolver = EtcHandshaker(etcHandshakerConfigurationWithResolver)

    val firstBlock = genesisBlock.copy(header = genesisBlock.header.copy(parentHash = genesisBlock.header.hash, number = 1))
  }

  trait LocalPeerSetup extends TestSetup {
    val localHello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = Config.clientId,
      capabilities = Seq(Capability("eth", Versions.PV63.toByte)),
      listenPort = 0, //Local node not listening
      nodeId = ByteString(nodeStatus.nodeId)
    )

    val localStatus = Status(
      protocolVersion = Versions.PV63,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = genesisBlock.header.difficulty,
      bestHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash
    )

    val localGetBlockHeadersRequest = GetBlockHeaders(Left(forkBlockHeader.number), maxHeaders = 1, skip = 0, reverse = false)
  }

  trait RemotePeerSetup extends TestSetup {
    val remoteNodeStatus = NodeStatus(key = generateKeyPair(secureRandom), serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)
    val remotePort = 8545

    val remoteHello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = "remote-peer",
      capabilities = Seq(Capability("eth", Versions.PV63.toByte)),
      listenPort = remotePort,
      nodeId = ByteString(remoteNodeStatus.nodeId)
    )

    val remoteStatus =
      Status(
        protocolVersion = Versions.PV63,
        networkId = Config.Network.peer.networkId,
        totalDifficulty = 0,
        bestHash = genesisBlock.header.hash,
        genesisHash = genesisBlock.header.hash
      )
  }
}
