package io.iohk.ethereum.consensus.ethash

import akka.actor.ActorRef
import io.iohk.ethereum.consensus.ConsensusBuilder
import io.iohk.ethereum.nodebuilder._

trait MinerBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with OmmersPoolBuilder
    with PendingTransactionsManagerBuilder
    with BlockGeneratorBuilder
    with SyncControllerBuilder
    with MiningConfigBuilder
    with EthServiceBuilder
    with ConsensusBuilder =>

  lazy val miner: ActorRef = actorSystem.actorOf(Miner.props(
    blockchain,
    blockGenerator,
    ommersPool,
    pendingTransactionsManager,
    syncController,
    miningConfig,
    ethService,
    consensus))
}
