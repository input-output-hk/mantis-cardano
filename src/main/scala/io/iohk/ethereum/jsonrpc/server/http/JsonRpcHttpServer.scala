package io.iohk.ethereum.jsonrpc.server.http

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.megard.akka.http.cors.javadsl.CorsRejection
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import io.iohk.ethereum.async.DispatcherId
import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.jsonrpc._
import io.iohk.ethereum.jsonrpc.server.SslSetup.CertificateConfig
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.utils.events._
import io.iohk.ethereum.utils.{ConfigUtils, JsonUtils, Logger}
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.{DefaultFormats, native}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

trait JsonRpcHttpServer extends Json4sSupport with Logger with EventSupport {
  val jsonRpcController: JsonRpcController

  implicit val serialization = native.Serialization

  implicit val formats = DefaultFormats

  def corsAllowedOrigins: HttpOriginRange
  def maxContentLength: Long

  implicit val routeExecutionContext: ExecutionContextExecutor

  val corsSettings = CorsSettings.defaultSettings.copy(
    allowGenericHttpRequests = true,
    allowedOrigins = corsAllowedOrigins
  )

  protected lazy val metrics = new JsonRpcHttpServerMetrics(Metrics.get())

  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler.newBuilder()
      .handle {
        case _: MalformedRequestContentRejection =>
          complete((StatusCodes.BadRequest, JsonRpcResponse("2.0", None, Some(JsonRpcErrors.ParseError), JInt(0))))
        case _: CorsRejection =>
          complete(StatusCodes.Forbidden)
      }
      .result()

  val myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case EntityStreamSizeException(limit, actualSize) =>
        val msg = s"Entity size ${actualSize.getOrElse("(?)")} exceeds limit of ${limit}"
        val error = JsonRpcErrors.InvalidRequest.copy(data = Some(JString(msg)))
        complete((StatusCodes.BadRequest, JsonRpcResponse("2.0", None, Some(error), JInt(0))))
    }

  val mainRoute: Route = {
    (path("healthcheck") & pathEndOrSingleSlash & get) {
      handleHealthcheck()
    } ~
      (path("buildinfo") & pathEndOrSingleSlash & get) {
        handleBuildInfo()
      } ~
      (pathEndOrSingleSlash & post) {
        entity(as[JsonRpcRequest]) { request =>
          handleRequest(request)
        } ~ entity(as[Seq[JsonRpcRequest]]) { request =>
          handleBatchRequest(request)
        }
      }
  }

  val strictEntityTimeout = FiniteDuration(2 * 150, TimeUnit.MILLISECONDS)

  val limitsRoute: Route =
    withSizeLimit(maxContentLength) {
      toStrictEntity(strictEntityTimeout) {
        extractRequestEntity {
          case HttpEntity.Strict(_, data) ⇒
            metrics.RequestSizeDistribution.record(data.size)

            mainRoute

          case _ ⇒
            assert(false)
            mainRoute
        }
      }
    }

  val route: Route =
    cors(corsSettings) {
      handleRejections(myRejectionHandler) {
        handleExceptions(myExceptionHandler) {
          if(maxContentLength > 0)
            limitsRoute
          else
            mainRoute
        }
      }
    }

  /**
    * Try to start JSON RPC server
    */
  def run(): Unit

  private[this] final val buildInfoResponse: HttpResponse = {
    val json = JsonUtils.pretty(MantisBuildInfo.toMap)

    HttpResponse(
      status = StatusCodes.OK,
      entity = HttpEntity(ContentTypes.`application/json`, json)
    )
  }

  private[this] final val buildInfoRoute: StandardRoute = complete(buildInfoResponse)

  private[this] def handleBuildInfo(): StandardRoute = buildInfoRoute

  private[this] def handleHealthcheck(): StandardRoute = {
    val responseF = jsonRpcController.healthcheck()

    val httpResponseF =
      responseF.map {
        case response if response.isOK ⇒
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )

        case response ⇒
          HttpResponse(
            status = StatusCodes.InternalServerError,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )
      }

    complete(httpResponseF)
  }

  private def updateEventWithMeta(
    event: EventDSL,
    uuid: UUID,
    isBatch: Boolean,
    batchIndex: Int, // which item from the batch. First is 1. Sorry.
    batchSize: Int,  // how many requests in the batch
    ip: RemoteAddress,
    requestId: String
  ): EventDSL = {
    val requestUUid = uuid.toString

    event
      .attribute(EventAttr.Uuid, requestUUid)
      .attribute(EventAttr.IsBatch, isBatch.toString)
      .tag(requestUUid)
      .attribute(EventAttr.IP, ip.toString)
      .attribute(EventAttr.Id, requestId)

    if(isBatch) {
      event
        .tag(EventTag.Batch)
        .attribute(EventAttr.BatchIndex, batchIndex)
        .attribute(EventAttr.BatchSize, batchSize)
    }

    event
  }

  private def updateEventWithRequest(event: EventDSL, request: JsonRpcRequest): EventDSL = {
    val requestJson = serialization.write(request)
    event
      .attribute(EventAttr.RequestMethod, request.method)
      .attribute(EventAttr.RequestObj, request.toString)
      .attribute(EventAttr.RequestJson, requestJson)
  }

  private def updateEventWithResponse(event: EventDSL, response: JsonRpcResponse): EventDSL = {
    val responseJson = serialization.write(response)
    event
      .attribute(EventAttr.ResponseObj, response.toString)
      .attribute(EventAttr.ResponseJson, responseJson)
  }

  private def handleRequestInternal(
    uuid: UUID,
    isBatch: Boolean,
    batchIndex: Int, // which item from the batch. First is 1. If you use 0, it means the batch as a whole.
    batchSize: Int,  // how many requests in the batch
    request: JsonRpcRequest,
    ip: RemoteAddress
  ): Future[JsonRpcResponse] = {

    val requestIdOpt = request.id.flatMap(_.extractOpt[String])
    val requestId = requestIdOpt.getOrElse("")
    val requestUUid = uuid.toString
    val requestJson = serialization.write(request)

    val metaUpdater = updateEventWithMeta(_: EventDSL, uuid, isBatch, batchIndex, batchSize, ip, requestId)
    val requestUpdater = updateEventWithRequest(_: EventDSL, request)

    Event.okStart() // FIXME we need an extra string here to designate we are in "handleRequest"
      .updateWith(metaUpdater)
      .updateWith(requestUpdater)
      .send()

    val responseF: Future[JsonRpcResponse] = jsonRpcController.handleRequest(request)

    responseF.andThen {
      case Success(response) =>
        val responseJson = serialization.write(response)
        Event.okFinish()
          .updateWith(metaUpdater)
          .updateWith(updateEventWithResponse(_, response))
          .send()

      case Failure(ex) =>
        Event.exceptionFinish(ex)
          .updateWith(metaUpdater)
          .updateWith(requestUpdater)
          .send()

    }
  }

  private def handleRequest(request: JsonRpcRequest) = extractClientIP { ip =>
    complete {
      val uuid = UUID.randomUUID()
      handleRequestInternal(uuid, false, 1, 1, request, ip)
    }
  }

  private def handleBatchRequest(requests: Seq[JsonRpcRequest]): Route = extractClientIP { ip =>
    complete {
      val requestId = requests.map(_.id.flatMap(_.extractOpt[String]).getOrElse("n/a")).mkString(",")

      val uuid = UUID.randomUUID() // one UUID for the whole batch, since it is one request from the user's POV.
      val batchSize = requests.size

      val metaUpdater = updateEventWithMeta(_: EventDSL, uuid, true, 0, batchSize, ip, requestId)

      Event.okStart()
        .updateWith(metaUpdater)
        .send()

      val requestItems = for {
        (r, i) <- requests.zipWithIndex
      } yield (r, i + 1) // 1-based index

      val responseF: Future[Seq[JsonRpcResponse]] = Future.sequence(requestItems.map { case (request, batchIndex) =>
        handleRequestInternal(uuid, true, batchIndex, batchSize, request, ip)
      })

      responseF.andThen {
        case Success(_) =>
          Event.okFinish()
            .updateWith(metaUpdater)
            .send()

        case Failure(ex) =>
          Event.exceptionFinish(ex)
            .updateWith(metaUpdater)
            .send()

      }
    }
  }
}

object JsonRpcHttpServer extends Logger {
  final val JsonRpcHttpDispatcherId = DispatcherId("mantis.async.dispatchers.json-rpc-http")

  def apply(jsonRpcController: JsonRpcController, config: JsonRpcHttpServerConfig, secureRandom: SecureRandom)
           (implicit actorSystem: ActorSystem): Either[String, JsonRpcHttpServer] = config.mode match {
    case "http" => Right(new BasicJsonRpcHttpServer(jsonRpcController, config)(actorSystem))
    case "https" => Right(new JsonRpcHttpsServer(jsonRpcController, config, secureRandom)(actorSystem))
    case _ => Left(s"Cannot start JSON RPC server: Invalid mode ${config.mode} selected")
  }

  final case class JsonRpcHttpServerConfig(
    mode: String,
    enabled: Boolean,
    interface: String,
    port: Int,
    certificateConfig: Option[CertificateConfig],
    corsAllowedOrigins: HttpOriginRange,
    maxContentLength: Long
  )

  object JsonRpcHttpServerConfig {
    import com.typesafe.config.{Config ⇒ TypesafeConfig}

    def apply(mantisConfig: TypesafeConfig): JsonRpcHttpServerConfig = {
      val rpcHttpConfig = mantisConfig.getConfig("network.rpc.http")

      val maybeCertificateConfig = for {
        certificateKeyStorePath <- Try(rpcHttpConfig.getString("certificate-keystore-path")).toOption
        certificateKeyStoreType <- Try(rpcHttpConfig.getString("certificate-keystore-type")).toOption
        certificatePasswordFile <- Try(rpcHttpConfig.getString("certificate-password-file")).toOption
      } yield CertificateConfig(certificateKeyStorePath, certificateKeyStoreType, certificatePasswordFile)

      JsonRpcHttpServerConfig(
        mode = rpcHttpConfig.getString("mode"),
        enabled = rpcHttpConfig.getBoolean("enabled"),
        interface = rpcHttpConfig.getString("interface"),
        port = rpcHttpConfig.getInt("port"),

        corsAllowedOrigins = ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins"),

        maxContentLength = rpcHttpConfig.getBytes("max-content-length"),

        certificateConfig = maybeCertificateConfig
      )
    }
  }
}
