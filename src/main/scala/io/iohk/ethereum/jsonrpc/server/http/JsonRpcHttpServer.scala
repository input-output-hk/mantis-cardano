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
import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.jsonrpc._
import io.iohk.ethereum.metrics.Metrics
import io.iohk.ethereum.utils.events._
import io.iohk.ethereum.utils.{ConfigUtils, JsonUtils, Logger}
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.{DefaultFormats, native}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

trait JsonRpcHttpServer extends Json4sSupport with Logger with EventSupport {
  val jsonRpcController: JsonRpcController

  implicit val serialization = native.Serialization

  implicit val formats = DefaultFormats

  protected def mainService: String = "jsonrpc http"

  def corsAllowedOrigins: HttpOriginRange
  def maxContentLength: Long

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

  val stricEntityDuration = FiniteDuration(2 * 150, TimeUnit.MILLISECONDS)

  val limitsRoute: Route =
    withSizeLimit(maxContentLength) {
      toStrictEntity(stricEntityDuration) {
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

  private[this] def handleBuildInfo() = buildInfoRoute

  private[this] def handleHealthcheck() = {
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

  private def handleRequest(request: JsonRpcRequest) = extractClientIP { ip =>
    complete {
      val requestIdOpt = request.id.flatMap(_.extractOpt[String])
      val requestId = requestIdOpt.getOrElse("")

      val requestJson = serialization.write(request)
      val requestUUid = UUID.randomUUID().toString
      val ipAddressOrEmpty = ip.toOption.map(_.getHostAddress).getOrElse("")

      def updateEvent(event: EventDSL): EventDSL =
        event
          .attribute(EventAttr.Uuid, requestUUid)
          .tag(requestUUid)
          .attribute("_ip_", ip.toString())
          .attribute(EventAttr.IP, ipAddressOrEmpty)
          .attribute(EventAttr.Id, requestIdOpt.getOrElse(""))
          .attribute("requestObj", request.toString)
          .attribute("requestJson", requestJson)

      Event.okStart() // FIXME we need an extra string here to designate we are in "handleRequest"
        .update(updateEvent)
        .send()

      log.info(s"Processing new JSON RPC request [$requestId] from [$ip]:\n ${serialization.write(request)}")

      val responseF = jsonRpcController.handleRequest(request)

      responseF.andThen {
        case Success(response) =>
          val responseJson = serialization.write(response)
          Event.okFinish()
            .update(updateEvent)
            .attribute("responseObj", response.toString)
            .attribute("responseJson", responseJson)
            .send()

          log.info(s"Request [$requestId] successful")

        case Failure(ex) =>
          Event.exceptionFinish(ex)
            .update(updateEvent)
            .send()

          log.error(s"Request [$requestId] failed", ex)
      }
    }
  }

  private def handleBatchRequest(requests: Seq[JsonRpcRequest]) = extractClientIP { ip =>
    complete {
      val requestId = requests.map(_.id.flatMap(_.extractOpt[String]).getOrElse("n/a")).mkString(",")

      log.info(s"Processing new JSON RPC batch request [$requestId] from [$ip]:\n ${requests.map(r => serialization.write(r)).mkString("\n")}")

      val responseF = Future.sequence(requests.map { request =>
        jsonRpcController.handleRequest(request)
      })

      responseF.andThen {
        case Success(_) => log.info(s"Batch request [$requestId] successful")
        case Failure(ex) => log.error(s"Batch request [$requestId] failed", ex)
      }
    }
  }
}

object JsonRpcHttpServer extends Logger {

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
    certificateKeyStorePath: Option[String],
    certificateKeyStoreType: Option[String],
    certificatePasswordFile: Option[String],
    corsAllowedOrigins: HttpOriginRange,
    maxContentLength: Long
  )

  object JsonRpcHttpServerConfig {
    import com.typesafe.config.{Config ⇒ TypesafeConfig}

    def apply(mantisConfig: TypesafeConfig): JsonRpcHttpServerConfig = {
      val rpcHttpConfig = mantisConfig.getConfig("network.rpc.http")

      JsonRpcHttpServerConfig(
        mode = rpcHttpConfig.getString("mode"),
        enabled = rpcHttpConfig.getBoolean("enabled"),
        interface = rpcHttpConfig.getString("interface"),
        port = rpcHttpConfig.getInt("port"),

        corsAllowedOrigins = ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins"),

        maxContentLength = rpcHttpConfig.getBytes("max-content-length"),

        certificateKeyStorePath = Try(rpcHttpConfig.getString("certificate-keystore-path")).toOption,
        certificateKeyStoreType = Try(rpcHttpConfig.getString("certificate-keystore-type")).toOption,
        certificatePasswordFile = Try(rpcHttpConfig.getString("certificate-password-file")).toOption
      )
    }
  }
}
