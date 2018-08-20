package io.iohk.ethereum.nodebuilder

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.ActorSystem
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.iohk.ethereum.blockchain.sync.SyncController
import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.consensus.StdConsensusBuilder
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.network.discovery.DiscoveryListener
import io.iohk.ethereum.network.{PeerManagerActor, ServerActor}
import io.iohk.ethereum.testmode.{TestLedgerBuilder, TestmodeConsensusBuilder}
import io.iohk.ethereum.utils._
import io.iohk.ethereum.utils.events.EventSupport

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
abstract class BaseNode extends Node with EventSupport {
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
    if (jsonRpcConfig.websocketServerConfig.enabled) jsonRpcWebsocketServer.run()
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

  private[this] def startHealthcheckSender(): Unit = {
    val tf = (new ThreadFactoryBuilder)
      .setDaemon(true)
      .setNameFormat("healthcheck-sender-%d")
      .setPriority(Thread.NORM_PRIORITY)
      .build()

    val executor = Executors.newScheduledThreadPool(1, tf)
    executor.scheduleAtFixedRate(
      () => {
        // This will trigger the healthcheck events
        jsonRpcController.healthcheck()
      },
      Config.healthIntervalMilliseconds,
      Config.healthIntervalMilliseconds,
      TimeUnit.MILLISECONDS
    )
  }

  def start(): Unit = {
    logBuildInfo()

    startMetrics()

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

    startHealthcheckSender()
  }

  override def shutdown(): Unit = {
    var errorCount = 0

    def tryAndLogFailure[A <: AnyRef](what: A)(f: A => Any): Unit = Try(f(what)) match {
      case Failure(e) =>
        errorCount += 1
        val msg = s"Error [$errorCount] shutting down $what"
        log.warn(msg, e)
        Event.warning("shutdown").description(msg).send()
      case Success(_) =>
    }

    log.info(s"Shutdown sequence initiated")

    tryAndLogFailure(consensus)(_.stopProtocol())
    tryAndLogFailure(system)((system: ActorSystem) => Await.ready(system.terminate, shutdownTimeoutDuration))
    tryAndLogFailure(storagesInstance.dataSources)(_.closeAll())
    tryAndLogFailure(jsonRpcController)(_.shutdown())
    if (jsonRpcConfig.ipcServerConfig.enabled) {
      tryAndLogFailure(jsonRpcIpcServer)(_.close())
    }
    if (jsonRpcConfig.websocketServerConfig.enabled) {
      tryAndLogFailure(() => jsonRpcWebsocketServer.close())
    }

    tryAndLogFailure(metrics)(_.close())

    val msg = s"Shutdown sequence complete, $errorCount error(s)"

    if(errorCount > 0) {
      log.warn(msg)
      Event.warning("shutdown")
        .description(msg)
        .attribute("errorCount", errorCount.toString)
        .send()
    }
    else {
      log.info(msg)
      Event.ok("shutdown")
        .description(msg)
        .send()
    }

    Try(Riemann.close())

  }
}

class StdNode extends BaseNode with StdLedgerBuilder with StdConsensusBuilder
class TestNode extends BaseNode with TestLedgerBuilder with TestmodeConsensusBuilder with TestServiceBuilder
