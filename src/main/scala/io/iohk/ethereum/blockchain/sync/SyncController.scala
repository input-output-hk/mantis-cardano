package io.iohk.ethereum.blockchain.sync

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Scheduler}
import io.iohk.ethereum.consensus.validators.Validators
import io.iohk.ethereum.db.storage.{AppStateStorage, FastSyncStateStorage}
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.ledger.Ledger
import io.iohk.ethereum.utils.Config.SyncConfig

class SyncController(
    appStateStorage: AppStateStorage,
    blockchain: Blockchain,
    fastSyncStateStorage: FastSyncStateStorage,
    ledger: Ledger,
    validators: Validators,
    peerEventBus: ActorRef,
    txPool: ActorRef,
    ommersPool: ActorRef,
    etcPeerManager: ActorRef,
    syncConfig: SyncConfig,
    shutdownAppFn: () => Unit,
    externalSchedulerOpt: Option[Scheduler] = None)
  extends Actor
    with ActorLogging {

  import SyncController._

  def scheduler: Scheduler = externalSchedulerOpt getOrElse context.system.scheduler

  override def receive: Receive = idle

  def idle: Receive = {
    case Start => start()
  }

  def runningFastSync(fastSync: ActorRef): Receive = {
    case FastSync.Done =>
      fastSync ! PoisonPill
      startRegularSync()

    case other => fastSync.forward(other)
  }

  def runningRegularSync(regularSync: ActorRef): Receive = {
    case other => regularSync.forward(other)
  }

  def start(): Unit = {
    import syncConfig.doFastSync

    appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber())
    (appStateStorage.isFastSyncDone(), doFastSync) match {
      case (false, true) =>
        startFastSync()
      case (true, true) =>
        log.warning(s"do-fast-sync is set to $doFastSync but fast sync cannot start because it has already been completed")
        startRegularSync()
      case (true, false) =>
        startRegularSync()
      case (false, false) =>
        //Check whether fast sync was started before
        if (fastSyncStateStorage.getSyncState().isDefined) {
          log.warning(s"do-fast-sync is set to $doFastSync but regular sync cannot start because fast sync hasn't completed")
          startFastSync()
        } else
          startRegularSync()
    }
  }

  def startFastSync(): Unit = {
    val fastSync = context.actorOf(FastSync.props(fastSyncStateStorage, appStateStorage, blockchain, validators,
      peerEventBus, etcPeerManager, syncConfig, scheduler), "fast-sync")
    fastSync ! FastSync.Start
    context become runningFastSync(fastSync)
  }

  def startRegularSync(): Unit = {
    val props: Props = RegularSync.props(appStateStorage, etcPeerManager,
      peerEventBus, ommersPool, txPool, new BlockBroadcast(etcPeerManager, syncConfig),
      ledger, blockchain, syncConfig, scheduler
    ) // We set the dispatcher here and not in `RegularSync.props` because we do not want to break the tests
      // (try it with akka-testkit_2.12-2.4.17)
      .withDispatcher(RegularSync.RegularSyncDispatcherId)

    val regularSync = context.actorOf(props, "regular-sync")

    regularSync ! RegularSync.Start
    context become runningRegularSync(regularSync)
  }
}

object SyncController {
  // scalastyle:off parameter.number
  def props(appStateStorage: AppStateStorage,
            blockchain: Blockchain,
            syncStateStorage: FastSyncStateStorage,
            ledger: Ledger,
            validators: Validators,
            peerEventBus: ActorRef,
            txPool: ActorRef,
            ommersPool: ActorRef,
            etcPeerManager: ActorRef,
            syncConfig: SyncConfig,
            shutdownFn: () => Unit):
  Props = Props(new SyncController(appStateStorage, blockchain, syncStateStorage, ledger, validators,
    peerEventBus, txPool, ommersPool, etcPeerManager, syncConfig, shutdownFn))

  case object Start
}
