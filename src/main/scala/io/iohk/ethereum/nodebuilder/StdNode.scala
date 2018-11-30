package io.iohk.ethereum.nodebuilder

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import io.iohk.ethereum.blockchain.sync.SyncController
import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.consensus.StdConsensusBuilder
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.network.discovery.DiscoveryListener
import io.iohk.ethereum.network.{PeerManagerActor, ServerActor}
import io.iohk.ethereum.testmode.{TestLedgerBuilder, TestmodeConsensusBuilder}
import io.iohk.ethereum.utils.{Config, JsonUtils, Scheduler}

import scala.concurrent.Await
import scala.util.{Failure, Success, Try}

/**
 * A standard node is everything Ethereum prescribes except the consensus algorithm,
 * which is plugged in dynamically.
 *
 * The design is historically related to the initial cake-pattern-based
 * [[io.iohk.ethereum.nodebuilder.Node Node]].
 *
 * @see [[io.iohk.ethereum.nodebuilder.Node Node]]
 */
abstract class BaseNode extends Node {
  private[this] lazy val metrics = Metrics.get() // TODO inject
  private[this] lazy val nodeMetrics = new BaseNodeMetrics(metrics)

  private[this] def loadGenesisData(): Unit = {
    if (!Config.testmode) genesisDataLoader.loadGenesisData()
  }

  private[this] def startPeerManager(): Unit = peerManager ! PeerManagerActor.StartConnecting

  private[this] def startServer(): Unit = server ! ServerActor.StartServer(networkConfig.Server.listenAddress)

  private[this] def startDiscoveryListener(): Unit =
    if (discoveryConfig.discoveryEnabled) {
      discoveryListener ! DiscoveryListener.Start
    }

  private[this] def startSyncController(): Unit = syncController ! SyncController.Start

  private[this] def startConsensus(): Unit = consensus.startProtocol(this)

  private[this] def startDiscoveryManager(): Unit = peerDiscoveryManager // unlazy

  private[this] def startJsonRpcHttpServer(): Unit = {
    maybeJsonRpcHttpServer match {
      case Right(jsonRpcServer) if jsonRpcConfig.httpServerConfig.enabled => jsonRpcServer.run()
      case Left(error) if jsonRpcConfig.httpServerConfig.enabled => log.error(error)
      case _ => //Nothing
    }
  }

  private[this] def startJsonRpcWebsocketServer(): Unit = {
    maybeJsonRpcWebsocketServer match {
      case Right(jsonRpcWebsocketServer) if jsonRpcConfig.websocketServerConfig.enabled => jsonRpcWebsocketServer.run()
      case Left(error) if jsonRpcConfig.websocketServerConfig.enabled => log.error(error)
      case _ => //Nothing
    }
  }

  private[this] def startJsonRpcIpcServer(): Unit = {
    if (jsonRpcConfig.ipcServerConfig.enabled) jsonRpcIpcServer.run()
  }

  private[this] def startMetrics(): Unit = {
    Metrics.configure(Config.config)

    // Produces a point in the graphs to signify Mantis has been (re)started.
    nodeMetrics.Start.trigger()
  }

  private[this] def logBuildInfo(): Unit = {
    val json = JsonUtils.pretty(MantisBuildInfo.toMap)

    log.info(s"buildInfo = \n$json")
  }

  // FIXME This `var` needs another look.
  private var sendExecutor: ScheduledExecutorService = null

  // FIXME This is not the build info sender anymore...
  private[this] def startBuildInfoSender(): ScheduledExecutorService = {
    Scheduler.startRunner(
      Config.healthIntervalMilliseconds,
      TimeUnit.MILLISECONDS,
      () => {
        jsonRpcController.healthcheck()
      }
    )
  }

  def start(): Unit = {
    logBuildInfo()

    startMetrics()

    sendExecutor = startBuildInfoSender()

    loadGenesisData()

    startPeerManager()

    startServer()

    startDiscoveryListener()

    startSyncController()

    startConsensus()

    startDiscoveryManager()

    startJsonRpcHttpServer()
    startJsonRpcIpcServer()
    startJsonRpcWebsocketServer()
  }

  override def shutdown(): Unit = {
    def tryAndLogFailure(f: () => Any): Unit = Try(f()) match {
      case Failure(e) => log.warn("Error while shutting down...", e)
      case Success(_) =>
    }

    tryAndLogFailure(() => consensus.stopProtocol())
    tryAndLogFailure(() => Await.ready(system.terminate, shutdownTimeoutDuration))
    tryAndLogFailure(() => storagesInstance.dataSources.closeAll())
    tryAndLogFailure(() => sendExecutor.shutdown())
    tryAndLogFailure(() => jsonRpcController.shutdown())
    if (jsonRpcConfig.ipcServerConfig.enabled) {
      tryAndLogFailure(() => jsonRpcIpcServer.close())
    }
    if (jsonRpcConfig.websocketServerConfig.enabled) {
      tryAndLogFailure(() => maybeJsonRpcWebsocketServer.map(_.close()))
    }

    tryAndLogFailure(() => metrics.close())
  }
}

class StdNode extends BaseNode with StdLedgerBuilder with StdConsensusBuilder
class TestNode extends BaseNode with TestLedgerBuilder with TestmodeConsensusBuilder with TestServiceBuilder
