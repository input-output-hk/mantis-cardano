package io.iohk.ethereum.healthcheck

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

import akka.util.ByteString
import com.google.common.eventbus.Subscribe
import io.iohk.ethereum.eventbus.EventBus
import io.iohk.ethereum.jsonrpc.EthService._
import io.iohk.ethereum.jsonrpc.NetService.{PeerCountRequest, PeerCountResponse}
import io.iohk.ethereum.jsonrpc.{EthService, JsonRpcHealthcheck, NetService}
import io.iohk.ethereum.ledger.LedgerBusEvent
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.utils.events._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * The actual healthchecks that we currently implement.
 */
class HealthcheckService(
  config: HealthcheckServiceConfig,
  ethService: EthService,
  netService: NetService
)(implicit ec: ExecutionContext) extends EventSupport {
  //+ EventBus
  private val lastLedgerBusEventRef = new AtomicReference[Option[LedgerBusEvent]](None)

  @Subscribe
  protected def onLedgerBusEvent(event: LedgerBusEvent): Unit = {
    lastLedgerBusEventRef.set(Some(event))
  }
  EventBus.get().register(this)
  //- EventBus

  //+ Metrics
  private val metrics = new HealthcheckServiceMetrics(Metrics.get())
  //- Metrics

  private def millis2minutesD(millis: Long): Double = millis.toDouble / 1000.0 / 60.0
  private def uptimeMinutes(): Double = millis2minutesD(ManagementFactory.getRuntimeMXBean.getUptime)

  private def mapListeningHCResult(result: NetService.ListeningResponse): HealthcheckResult.MapperResult = {
    if(result.value) // server is listening
      None → Map.empty
    else
      Some("Server not listening") → Map.empty
  }
  private val listeningHC = JsonRpcHealthcheck(
    "listening",
    () ⇒ netService.listening(NetService.ListeningRequest()),
    mapListeningHCResult
  )

  private def mapPeerCountHCResult(result: PeerCountResponse): HealthcheckResult.MapperResult = {
    val peerCount = result.value
    val peersMeta = Map("peerCount" → peerCount.toString)
    if(peerCount > 0)
      None → peersMeta
    else
      Some("No peers") → peersMeta
  }
  private val peerCountHC = JsonRpcHealthcheck(
    "peerCount",
    () ⇒ netService.peerCount(PeerCountRequest()),
    mapPeerCountHCResult
  )

  private def mapBlockByNumberHCResult(result: BlockByNumberResponse): HealthcheckResult.MapperResult = {
    result.blockResponse match {
      case None ⇒
        Some("No block") → Map.empty

      case Some(response) ⇒
        None → Map("blockNumber" → response.number.toString())
    }
  }

  private def mapEarliestBlockHCResult(result: BlockByNumberResponse): HealthcheckResult.MapperResult =
    mapBlockByNumberHCResult(result)

  private val earliestBlockHC = JsonRpcHealthcheck(
    "earliestBlock",
    () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Earliest, true)),
    mapEarliestBlockHCResult
  )

  private def mapLatestBlockHCResult(result: BlockByNumberResponse): HealthcheckResult.MapperResult =
    mapBlockByNumberHCResult(result)

  private val latestBlockHC = JsonRpcHealthcheck(
    "latestBlock",
    () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Latest, true)),
    mapLatestBlockHCResult
  )

  private def mapPendingBlockHCResult(result: BlockByNumberResponse): HealthcheckResult.MapperResult =
    mapBlockByNumberHCResult(result)

  private val pendingBlockHC = JsonRpcHealthcheck(
    "pendingBlock",
    () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Pending, true)),
    mapPendingBlockHCResult
  )

  private def mapVmCallHCResult(result: CallResponse): HealthcheckResult.MapperResult = {
    None -> Map.empty
  }

  private val evmByteCode = ByteString("0xf3")

  //scalastyle:off
  private val ieleByteCode =
    ByteString("0000003963026900067465737428296800010000660000340065000200618001640001660001f6000101660002620102f7026700000000660000f60000a165627a7a72305820e4fd512842080cbbea62d8abf7ffa5d385b4520fd9bd85bf2d496803299340ff0029")
  //scalastyle:on

  private val virtualMachineHC = {
    val block = BlockParam.Latest
    val data = if(config.isIeleVM) ieleByteCode else evmByteCode
    val tx = CallTx(from = None, to = None, gas = None, gasPrice = 0, value = 0, data = data)
    JsonRpcHealthcheck(
      "virtualMachine",
      () ⇒ ethService.call(CallRequest(tx, block)),
      mapVmCallHCResult
    )
  }

  def blockImportHC(): HealthcheckResult = {
    val description = "blockImportLatency"

    // minutes are more human-friendly than millis
    val UptimeGracePeriod = millis2minutesD(config.uptimeGracePeriod.toMillis)
    val ImportGracePeriod = millis2minutesD(config.lastImportGracePeriod.toMillis)

    def uptimeMeta: Map[String, String] =
      Map("uptime" → s"${uptimeMinutes()} min", "gracePeriod" → s"${UptimeGracePeriod} min", "check" → "uptime")

    if(uptimeMinutes() <= UptimeGracePeriod) {
      // Give the node a change to boot properly and just respond as healthy.
      HealthcheckResult(description, None, uptimeMeta)
    }
    else {
      val lastEventOpt = lastLedgerBusEventRef.get()
      lastEventOpt match {
        case None ⇒
          HealthcheckResult(description, Some("No blocks imported for " + uptimeMinutes() + " min since boot"), uptimeMeta)

        case Some(LedgerBusEvent.ImportedBlocks(whenImportedMillis)) ⇒
          val dtMillis = System.currentTimeMillis() - whenImportedMillis
          val dtMinutes = millis2minutesD(dtMillis)

          val meta = Map(
            "lastImport" → s"${dtMinutes} min",
            "gracePeriod" → s"${ImportGracePeriod} min",
            "check" → "lastImport"
          )

          val error =
            if(dtMinutes <= ImportGracePeriod) { None }
            else { Some("No blocks imported for " + dtMinutes + " min since last import") }

          HealthcheckResult(description, error, meta)
      }
    }
  }

  def apply(): Future[HealthcheckResults] = {
    val listeningF = listeningHC()
    val peerCountF = peerCountHC()
    val earliestBlockF = earliestBlockHC()
    val latestBlockF = latestBlockHC()
    val pendingBlockF = pendingBlockHC()
    val virtualMachineF = virtualMachineHC()
    val blockImportF = Future.successful(blockImportHC())

    val allChecksF = List(listeningF, peerCountF, earliestBlockF, latestBlockF, pendingBlockF, virtualMachineF, blockImportF)
    val responseF = Future.sequence(allChecksF).map(HealthcheckResults)

    responseF.andThen {
      case Success(response) ⇒ {
        if (response.isOK) {
          metrics.HealhcheckOKCounter.increment()
        }
        else {
          metrics.HealhcheckErrorCounter.increment()

          // Only send the errors
          for(result ← response.checks if !result.isOK) {
            Event
              .error(result.description)
              .healthcheck(result)
              .send()
          }
        }
      }

      case Failure(t) ⇒ {
        metrics.HealhcheckExceptionCounter.increment()

        Event.exception(t).send()
      }
    }
  }
}
