package io.iohk.ethereum.jsonrpc

import java.util.concurrent.TimeUnit

import io.iohk.ethereum.jsonrpc.EthService._
import io.iohk.ethereum.jsonrpc.JsonRpcController.JsonRpcConfig
import io.iohk.ethereum.jsonrpc.NetService._
import io.iohk.ethereum.jsonrpc.PersonalService._
import io.iohk.ethereum.jsonrpc.Web3Service._
import io.iohk.ethereum.utils.Logger
import org.json4s.JsonAST.{JArray, JValue}
import org.json4s.JsonDSL._
import com.typesafe.config.{Config ⇒ TypesafeConfig}
import io.iohk.ethereum.healthcheck.HealthcheckResponse
import io.iohk.ethereum.healthcheck.HealthcheckStatus
import io.iohk.ethereum.jsonrpc.TestService._
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import io.iohk.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer.JsonRpcIpcServerConfig
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.utils.events._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

object JsonRpcController {

  trait JsonDecoder[T] {
    def decodeJson(params: Option[JArray]): Either[JsonRpcError, T]
  }

  trait JsonEncoder[T] {
    def encodeJson(t: T): JValue
  }

  trait JsonRpcConfig {
    def apis: Seq[String]
    def accountTransactionsMaxBlocks: Int
    def minerActiveTimeout: FiniteDuration
    def httpServerConfig: JsonRpcHttpServerConfig
    def ipcServerConfig: JsonRpcIpcServerConfig
    def disabledMethods: Set[String]
  }

  object JsonRpcConfig {
    def apply(mantisConfig: TypesafeConfig): JsonRpcConfig = {
      import scala.concurrent.duration._
      val rpcConfig = mantisConfig.getConfig("network.rpc")

      new JsonRpcConfig {
        override val apis: Seq[String] = {
          val providedApis = rpcConfig.getString("apis").split(",").map(_.trim.toLowerCase)
          val invalidApis = providedApis.diff(List("web3", "eth", "net", "personal", "daedalus", "test", "iele"))
          require(invalidApis.isEmpty, s"Invalid RPC APIs specified: ${invalidApis.mkString(",")}")
          providedApis
        }

        override def accountTransactionsMaxBlocks: Int = rpcConfig.getInt("account-transactions-max-blocks")
        override def minerActiveTimeout: FiniteDuration = rpcConfig.getDuration("miner-active-timeout").toMillis.millis

        override val httpServerConfig: JsonRpcHttpServerConfig = JsonRpcHttpServerConfig(mantisConfig)
        override val ipcServerConfig: JsonRpcIpcServerConfig = JsonRpcIpcServerConfig(mantisConfig)

        override val disabledMethods = rpcConfig.getStringList("disabled-methods").asScala.toSet
      }
    }
  }

  object Apis {
    val Eth = "eth"
    val Web3 = "web3"
    val Net = "net"
    val Db = "db"
    val Personal = "personal"
    val Daedalus = "daedalus"
    val Admin = "admin"
    val Debug = "debug"
    val Rpc = "rpc"
    val Test = "test"
    val Iele = "iele"
  }

}

class JsonRpcController(
  web3Service: Web3Service,
  netService: NetService,
  ethService: EthService,
  personalService: PersonalService,
  testServiceOpt: Option[TestService],
  config: JsonRpcConfig) extends Logger with EventSupport {

  import JsonRpcController._
  import EthJsonMethodsImplicits._
  import TestJsonMethodsImplicits._
  import IeleJsonMethodsImplicits._
  import JsonMethodsImplicits._
  import JsonRpcErrors._

  private[this] val metrics = new JsonRpcControllerMetrics(Metrics.get())

  lazy val apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]]] = Map(
    Apis.Eth -> handleEthRequest,
    Apis.Web3 -> handleWeb3Request,
    Apis.Net -> handleNetRequest,
    Apis.Db -> PartialFunction.empty,
    Apis.Personal -> handlePersonalRequest,
    Apis.Daedalus -> handleDaedalusRequest,
    Apis.Rpc -> handleRpcRequest,
    Apis.Admin -> PartialFunction.empty,
    Apis.Debug -> PartialFunction.empty,
    Apis.Test -> handleTestRequest,
    Apis.Iele -> handleIeleRequest
  )

  private def enabledApis = config.apis :+ Apis.Rpc // RPC enabled by default

  private def handleWeb3Request: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "web3_sha3", _, _) =>
      handle[Sha3Request, Sha3Response](web3Service.sha3, req)
    case req @ JsonRpcRequest(_, "web3_clientVersion", _, _) =>
      handle[ClientVersionRequest, ClientVersionResponse](web3Service.clientVersion, req)
  }

  private def handleNetRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "net_version", _, _) =>
      handle[VersionRequest, VersionResponse](netService.version, req)
    case req @ JsonRpcRequest(_, "net_listening", _, _) =>
      handle[ListeningRequest, ListeningResponse](netService.listening, req)
    case req @ JsonRpcRequest(_, "net_peerCount", _, _) =>
      handle[PeerCountRequest, PeerCountResponse](netService.peerCount, req)
  }

  // scalastyle:off cyclomatic.complexity
  // scalastyle:off method.length
  private def handleEthRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "eth_protocolVersion", _, _) =>
      handle[ProtocolVersionRequest, ProtocolVersionResponse](ethService.protocolVersion, req)
    case req @ JsonRpcRequest(_, "eth_syncing", _, _) =>
      handle[SyncingRequest, SyncingResponse](ethService.syncing, req)
    case req @ JsonRpcRequest(_, "eth_submitHashrate", _, _) =>
      handle[SubmitHashRateRequest, SubmitHashRateResponse](ethService.submitHashRate, req)
    case req @ JsonRpcRequest(_, "eth_hashrate", _, _) =>
      handle[GetHashRateRequest, GetHashRateResponse](ethService.getHashRate, req)
    case req @ JsonRpcRequest(_, "eth_gasPrice", _, _) =>
      handle[GetGasPriceRequest, GetGasPriceResponse](ethService.getGetGasPrice, req)
    case req@JsonRpcRequest(_, "eth_getTransactionByBlockNumberAndIndex", _, _) =>
      handle[GetTransactionByBlockNumberAndIndexRequest,
        GetTransactionByBlockNumberAndIndexResponse](ethService.getTransactionByBlockNumberAndIndexRequest, req)
    case req @ JsonRpcRequest(_, "eth_mining", _, _) =>
      handle[GetMiningRequest, GetMiningResponse](ethService.getMining, req)
    case req @ JsonRpcRequest(_, "eth_getWork", _, _) =>
      handle[GetWorkRequest, GetWorkResponse](ethService.getWork, req)
    case req @ JsonRpcRequest(_, "eth_submitWork", _, _) =>
      handle[SubmitWorkRequest, SubmitWorkResponse](ethService.submitWork, req)
    case req @ JsonRpcRequest(_, "eth_blockNumber", _, _) =>
      handle[BestBlockNumberRequest, BestBlockNumberResponse](ethService.bestBlockNumber, req)
    case req @ JsonRpcRequest(_, "eth_coinbase", _, _) =>
      handle[GetCoinbaseRequest, GetCoinbaseResponse](ethService.getCoinbase, req)
    case req @ JsonRpcRequest(_, "eth_getBlockTransactionCountByHash", _, _) =>
      handle[TxCountByBlockHashRequest, TxCountByBlockHashResponse](ethService.getBlockTransactionCountByHash, req)
    case req @ JsonRpcRequest(_, "eth_getBlockByHash", _, _) =>
      handle[BlockByBlockHashRequest, BlockByBlockHashResponse](ethService.getByBlockHash, req)
    case req @ JsonRpcRequest(_, "eth_getBlockByNumber", _, _) =>
      handle[BlockByNumberRequest, BlockByNumberResponse](ethService.getBlockByNumber, req)
    case req @ JsonRpcRequest(_, "eth_getTransactionByBlockHashAndIndex", _, _) =>
      handle[GetTransactionByBlockHashAndIndexRequest, GetTransactionByBlockHashAndIndexResponse](ethService.getTransactionByBlockHashAndIndexRequest, req)
    case req @ JsonRpcRequest(_, "eth_getUncleByBlockHashAndIndex", _, _) =>
      handle[UncleByBlockHashAndIndexRequest, UncleByBlockHashAndIndexResponse](ethService.getUncleByBlockHashAndIndex, req)
    case req @ JsonRpcRequest(_, "eth_getUncleByBlockNumberAndIndex", _, _) =>
      handle[UncleByBlockNumberAndIndexRequest, UncleByBlockNumberAndIndexResponse](ethService.getUncleByBlockNumberAndIndex, req)
    case req @ JsonRpcRequest(_, "eth_accounts", _, _) =>
      handle[ListAccountsRequest, ListAccountsResponse](personalService.listAccounts, req)
    case req @ JsonRpcRequest(_, "eth_sendRawTransaction", _, _) =>
      handle[SendRawTransactionRequest, SendRawTransactionResponse](ethService.sendRawTransaction, req)
    case req @ JsonRpcRequest(_, "eth_sendTransaction", _, _) =>
      handle[SendTransactionRequest, SendTransactionResponse](personalService.sendTransaction, req)
    case req @ JsonRpcRequest(_, "eth_call", _, _) =>
      handle[CallRequest, CallResponse](ethService.call, req)(eth_call, eth_call)
    case req @ JsonRpcRequest(_, "eth_estimateGas", _, _) =>
      handle[CallRequest, EstimateGasResponse](ethService.estimateGas, req)(eth_estimateGas, eth_estimateGas)
    case req @ JsonRpcRequest(_, "eth_getCode", _, _) =>
      handle[GetCodeRequest, GetCodeResponse](ethService.getCode, req)
    case req @ JsonRpcRequest(_, "eth_getUncleCountByBlockNumber", _, _) =>
      handle[GetUncleCountByBlockNumberRequest, GetUncleCountByBlockNumberResponse](ethService.getUncleCountByBlockNumber, req)
    case req @ JsonRpcRequest(_, "eth_getUncleCountByBlockHash", _, _) =>
      handle[GetUncleCountByBlockHashRequest, GetUncleCountByBlockHashResponse](ethService.getUncleCountByBlockHash, req)
    case req @ JsonRpcRequest(_, "eth_getBlockTransactionCountByNumber", _, _) =>
      handle[GetBlockTransactionCountByNumberRequest, GetBlockTransactionCountByNumberResponse](ethService.getBlockTransactionCountByNumber, req)
    case req @ JsonRpcRequest(_, "eth_getBalance", _, _) =>
      handle[GetBalanceRequest, GetBalanceResponse](ethService.getBalance, req)
    case req @ JsonRpcRequest(_, "eth_getStorageAt", _, _) =>
      handle[GetStorageAtRequest, GetStorageAtResponse](ethService.getStorageAt, req)
    case req @ JsonRpcRequest(_, "eth_getTransactionCount", _, _) =>
      handle[GetTransactionCountRequest, GetTransactionCountResponse](ethService.getTransactionCount, req)
    case req @ JsonRpcRequest(_, "eth_newFilter", _, _) =>
      handle[NewFilterRequest, NewFilterResponse](ethService.newFilter, req)
    case req @ JsonRpcRequest(_, "eth_newBlockFilter", _, _) =>
      handle[NewBlockFilterRequest, NewFilterResponse](ethService.newBlockFilter, req)
    case req @ JsonRpcRequest(_, "eth_newPendingTransactionFilter", _, _) =>
      handle[NewPendingTransactionFilterRequest, NewFilterResponse](ethService.newPendingTransactionFilter, req)
    case req @ JsonRpcRequest(_, "eth_uninstallFilter", _, _) =>
      handle[UninstallFilterRequest, UninstallFilterResponse](ethService.uninstallFilter, req)
    case req @ JsonRpcRequest(_, "eth_getFilterChanges", _, _) =>
      handle[GetFilterChangesRequest, GetFilterChangesResponse](ethService.getFilterChanges, req)
    case req @ JsonRpcRequest(_, "eth_getFilterLogs", _, _) =>
      handle[GetFilterLogsRequest, GetFilterLogsResponse](ethService.getFilterLogs, req)
   case req @ JsonRpcRequest(_, "eth_getLogs", _, _) =>
      handle[GetLogsRequest, GetLogsResponse](ethService.getLogs, req)
    case req @ JsonRpcRequest(_, "eth_getTransactionByHash", _, _) =>
      handle[GetTransactionByHashRequest, GetTransactionByHashResponse](ethService.getTransactionByHash, req)
    case req @ JsonRpcRequest(_, "eth_getTransactionReceipt", _, _) =>
      handle[GetTransactionReceiptRequest, GetTransactionReceiptResponse](ethService.getTransactionReceipt, req)
    case req @ JsonRpcRequest(_, "eth_sign", _, _) =>
      // Even if it's under eth_xxx this method actually does the same as personal_sign but needs the account
      // to be unlocked before calling
      handle[SignRequest, SignResponse](personalService.sign, req)(eth_sign, personal_sign)
    case req @ JsonRpcRequest(_, "eth_getStorageRoot", _, _) =>
      handle[GetStorageRootRequest, GetStorageRootResponse](ethService.getStorageRoot, req)
  }

  private def handleTestRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
      testServiceOpt match {
        case Some(testService) => handleTestRequest(testService)
        case None => PartialFunction.empty
      }
  }

  private def handleTestRequest(testService: TestService): PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req@JsonRpcRequest(_, "test_setChainParams", _, _) =>
      handle[SetChainParamsRequest, SetChainParamsResponse](testService.setChainParams, req)
    case req@JsonRpcRequest(_, "test_mineBlocks", _, _) =>
      handle[MineBlocksRequest, MineBlocksResponse](testService.mineBlocks, req)
    case req@JsonRpcRequest(_, "test_modifyTimestamp", _, _) =>
      handle[ModifyTimestampRequest, ModifyTimestampResponse](testService.modifyTimestamp, req)
    case req@JsonRpcRequest(_, "test_rewindToBlock", _, _) =>
      handle[RewindToBlockRequest, RewindToBlockResponse](testService.rewindToBlock, req)
    case req@JsonRpcRequest(_, "miner_setEtherbase", _, _) =>
      handle[SetEtherbaseRequest, SetEtherbaseResponse](testService.setEtherbase, req)
  }

  private def handleIeleRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "iele_sendTransaction", _, _) =>
      handle[SendIeleTransactionRequest, SendTransactionResponse](personalService.sendIeleTransaction, req)
    case req @ JsonRpcRequest(_, "iele_call", _, _) =>
      handle[IeleCallRequest, IeleCallResponse](ethService.ieleCall, req)
  }

  private def handlePersonalRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "personal_importRawKey", _, _) =>
      handle[ImportRawKeyRequest, ImportRawKeyResponse](personalService.importRawKey, req)

    case req @ JsonRpcRequest(_, "personal_newAccount", _, _) =>
      handle[NewAccountRequest, NewAccountResponse](personalService.newAccount, req)

    case req @ JsonRpcRequest(_, "personal_listAccounts", _, _) =>
      handle[ListAccountsRequest, ListAccountsResponse](personalService.listAccounts, req)

    case req @ JsonRpcRequest(_, "personal_sendTransaction" | "personal_signAndSendTransaction", _, _) =>
      handle[SendTransactionWithPassphraseRequest, SendTransactionWithPassphraseResponse](personalService.sendTransaction, req)

    case req @ JsonRpcRequest(_, "personal_unlockAccount", _, _) =>
      handle[UnlockAccountRequest, UnlockAccountResponse](personalService.unlockAccount, req)

    case req @ JsonRpcRequest(_, "personal_lockAccount", _, _) =>
      handle[LockAccountRequest, LockAccountResponse](personalService.lockAccount, req)

    case req @ JsonRpcRequest(_, "personal_sign", _, _) =>
      handle[SignRequest, SignResponse](personalService.sign, req)(personal_sign, personal_sign)

    case req @ JsonRpcRequest(_, "personal_ecRecover", _, _) =>
      handle[EcRecoverRequest, EcRecoverResponse](personalService.ecRecover, req)
  }

  private def handleDaedalusRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "daedalus_getAccountTransactions", _, _) =>
      handle[GetAccountTransactionsRequest, GetAccountTransactionsResponse](ethService.getAccountTransactions, req)

    case req @ JsonRpcRequest(_, "daedalus_deleteWallet", _, _) =>
      handle[DeleteWalletRequest, DeleteWalletResponse](personalService.deleteWallet, req)

    case req @ JsonRpcRequest(_, "daedalus_changePassphrase", _, _) =>
      handle[ChangePassphraseRequest, ChangePassphraseResponse](personalService.changePassphrase, req)
  }

  private def handleRpcRequest: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
    case req @ JsonRpcRequest(_, "rpc_modules", _, _) =>
      val result = enabledApis.map { _ -> "1.0" }.toMap
      Future.successful(JsonRpcResponse("2.0", Some(result), None, req.id))
  }

  final val listeningHC = JsonRpcHealthcheck("listening", () ⇒ netService.listening(NetService.ListeningRequest()))
  final val peerCountHC = JsonRpcHealthcheck("peerCount", () ⇒ netService.peerCount(PeerCountRequest()))
  final val earliestBlockHC = JsonRpcHealthcheck("earliestBlock", () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Earliest, true)))
  final val latestBlockHC = JsonRpcHealthcheck("latestBlock", () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Latest, true)))
  final val pendingBlockHC = JsonRpcHealthcheck("pendingBlock", () ⇒ ethService.getBlockByNumber(BlockByNumberRequest(EthService.BlockParam.Pending, true)))

  def healthcheck(): Future[HealthcheckResponse] = {
    val listeningF = listeningHC()
    val peerCountF = peerCountHC()
    val earliestBlockF = earliestBlockHC()
    val latestBlockF = latestBlockHC()
    val pendingBlockF = pendingBlockHC()

    val allChecksF = List(listeningF, peerCountF, earliestBlockF, latestBlockF, pendingBlockF)
    val responseF = Future.sequence(allChecksF).map(HealthcheckResponse)

    responseF.andThen {
      case Success(response) ⇒ {
        if (response.isOK) {
          Event.ok("health").send()
        }
        else {
          metrics.HealhcheckErrorCounter.increment()

          Event.error("health").send()
          response.checks.foreach { result =>
            (result.status, result.error) match {
              case (HealthcheckStatus.OK, None) =>
                Event.ok(s"health ${result.description}").send()

              case (HealthcheckStatus.ERROR, Some(error)) =>
                Event
                  .error(s"health ${result.description}")
                  .attribute(EventAttr.Error, error)
                  .send()
              case _ =>
                // See assertion in [[io.iohk.ethereum.healthcheck.HealthcheckResult]]
                Event.error("health result internal error")
                  .attribute(EventAttr.Status, result.status)
                  .attribute(EventAttr.Error, result.error.toString)
                  .send()
            }
          }
        }
      }

      case Failure(t) ⇒ {
        metrics.HealhcheckErrorCounter.increment()

        Event.exception("health", t).send()
      }
    }
  }

  def shutdown(): Unit = {
  }

  def handleRequest(request: JsonRpcRequest): Future[JsonRpcResponse] = {
    val startTimeNanos = System.nanoTime()

    val notFoundFn: PartialFunction[JsonRpcRequest, Future[JsonRpcResponse]] = {
      case _ => Future.successful(errorResponse(request, MethodNotFound))
    }

    val method = request.method

    if (config.disabledMethods.contains(method)) {
      metrics.DisabledMethodsCounter.increment()

      Future.successful(errorResponse(request, MethodNotFound))
    } else {

      val handleFn = enabledApis.foldLeft(notFoundFn)((fn, api) => apisHandleFns.getOrElse(api, PartialFunction.empty) orElse fn)
      val result: Future[JsonRpcResponse] = handleFn(request)

      result.andThen {
        case Success(JsonRpcResponse(_, _, Some(JsonRpcError(code, _, _)), _)) =>
          metrics.MethodsErrorCounter.increment()

        case Success(JsonRpcResponse(_, _, None, _)) =>
          metrics.MethodsSuccessCounter.increment()

          val endTimeNanos = System.nanoTime()
          val dtNanos = endTimeNanos - startTimeNanos
          metrics.MethodsTimer.record(dtNanos, TimeUnit.NANOSECONDS)

        case Failure(t) =>
          metrics.MethodsExceptionCounter.increment()
          log.error("Error serving request", t)
      }
    }
  }

  private def handle[Req, Res](fn: Req => Future[Either[JsonRpcError, Res]], rpcReq: JsonRpcRequest)
                              (implicit dec: JsonDecoder[Req], enc: JsonEncoder[Res]): Future[JsonRpcResponse] = {
    dec.decodeJson(rpcReq.params) match {
      case Right(req) =>
        fn(req)
          .map {
            case Right(success) => successResponse(rpcReq, success)
            case Left(error) => errorResponse(rpcReq, error)
          }
          .recover { case ex =>
            log.error("Failed to handle RPC request", ex)
            errorResponse(rpcReq, InternalError)
          }
      case Left(error) =>
        Future.successful(errorResponse(rpcReq, error))
    }
  }

  private def successResponse[T](req: JsonRpcRequest, result: T)(implicit enc: JsonEncoder[T]): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, Some(enc.encodeJson(result)), None, req.id.getOrElse(0))

  private def errorResponse[T](req: JsonRpcRequest, error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, None, Some(error), req.id.getOrElse(0))

}
