package io.iohk.ethereum.ommers

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import io.iohk.ethereum.Fixtures.Blocks.Block3125369
import io.iohk.ethereum.Timeouts
import io.iohk.ethereum.domain.BlockchainImpl
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, GetOmmers, RemoveOmmers}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

class OmmersPoolSpec extends FlatSpec with Matchers with MockFactory {

  "OmmersPool" should "accept ommers" in new TestSetup {
    //just return header
    (blockchain.getBlockHeaderByHash _).expects(*).returns(Some(Block3125369.header))

    ommersPool ! AddOmmers(Block3125369.header)
    ommersPool.!(GetOmmers(Block3125369.header.number + 1))(testProbe.ref)

    testProbe.expectMsg(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(Block3125369.header)))
  }

  "OmmersPool" should "removes ommers ommers" in new TestSetup {
    //just return header
    (blockchain.getBlockHeaderByHash _).expects(*).returns(Some(Block3125369.header))

    ommersPool ! AddOmmers(Block3125369.header)
    ommersPool ! AddOmmers(Block3125369.header.copy(number = 2))
    ommersPool ! RemoveOmmers(Block3125369.header)

    ommersPool.!(GetOmmers(3))(testProbe.ref)

    testProbe.expectMsg(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(Block3125369.header.copy(number = 2))))
  }

  "OmmersPool" should "returns ommers when out of pool siez" in new TestSetup {
    //just return header
    (blockchain.getBlockHeaderByHash _).expects(*).returns(Some(Block3125369.header))

    ommersPool ! AddOmmers(Block3125369.header.copy(number = 4))
    ommersPool ! AddOmmers(Block3125369.header.copy(number = 20))
    ommersPool ! AddOmmers(Block3125369.header.copy(number = 30))
    ommersPool ! AddOmmers(Block3125369.header.copy(number = 40))
    ommersPool ! AddOmmers(Block3125369.header.copy(number = 5))
    ommersPool.!(GetOmmers(6))(testProbe.ref)

    testProbe.expectMsg(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(Block3125369.header.copy(number = 5))))
  }

  trait TestSetup extends MockFactory {
    implicit val system = ActorSystem("OmmersPoolSpec_System")

    val ommersPoolSize: Int = 3
    val testProbe = TestProbe()

    val blockchain = mock[BlockchainImpl]
    val ommersPool = system.actorOf(OmmersPool.props(blockchain, ommersPoolSize))
  }
}
