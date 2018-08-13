package io.iohk.ethereum.jsonrpc.server.http

import java.io.{PrintWriter, StringWriter}
import java.security.SecureRandom
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
import io.iohk.ethereum.utils.events._
import io.iohk.ethereum.utils.{ConfigUtils, JsonUtils, Logger}
import org.json4s.JsonAST.JInt
import org.json4s.{DefaultFormats, native}
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.Success
import scala.util.Failure

import scala.language.implicitConversions

trait JsonRpcHttpServer extends Json4sSupport with EventSupport {
  val jsonRpcController: JsonRpcController

  implicit val serialization = native.Serialization

  implicit val formats = DefaultFormats

  protected def mainService: String = "jsonrpc http"

  def corsAllowedOrigins: HttpOriginRange

  val corsSettings = CorsSettings.defaultSettings.copy(
    allowGenericHttpRequests = true,
    allowedOrigins = corsAllowedOrigins
  )

  implicit def myRejectionHandler(request: HttpRequest)(implicit actorSystem: ActorSystem): RejectionHandler =
    RejectionHandler.newBuilder()
      .handle {
        case r: MalformedRequestContentRejection =>
          val rJson = JsonUtils.pretty(r)
          val event0 = Event
            .warning("rejection handler [MalformedRequestContentRejection]")
            .attribute("rejection", r.toString)
            .attribute("rejectionJson", rJson)
            .attribute("exception", exception2string(r.cause))
            .description("aaa")

          val event = addRequestAttributes(event0, request)
          event.send()

          val error = JsonRpcErrors.ParseError.copy(message = JsonRpcErrors.ParseError.message + "\n" + JsonUtils.pretty(event))

          complete((StatusCodes.BadRequest, JsonRpcResponse("2.0", None, Some(error), JInt(0))))

        case r: CorsRejection =>
          val rJson = JsonUtils.pretty(r)

          val event = Event
            .warning("rejection handler [CorsRejection]")
            .attribute("rejection", r.toString)
            .attribute("rejectionJson", rJson)
            .attribute("origin", r.getOrigin.map[String](JsonUtils.pretty(_)).orElse(""))
            .attribute("method", r.getMethod.map[String](JsonUtils.pretty(_)).orElse(""))
            .attribute("headers", r.getHeaders.map[String](JsonUtils.pretty(_)).orElse("[]"))
            .description("aaa")

          addRequestAttributes(event, request).send()

          complete(StatusCodes.Forbidden)
      }
      .result()

  protected def exception2string(t: Throwable): String = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw, true)
    t.printStackTrace(pw)
    sw.toString
  }

  protected def addRequestAttributes(event: EventDSL, request: HttpRequest)(implicit actorSystem: ActorSystem): EventDSL = {
    val entity: RequestEntity = request.entity

    implicit val materializer = ActorMaterializer()
    // val entityF: Future[String] = Unmarshaller.stringUnmarshaller(entity)

    val entityStrictF: Future[HttpEntity.Strict] = entity.toStrict(FiniteDuration(10L * 100L, TimeUnit.MILLISECONDS))

    entityStrictF.andThen {
      case Success(entityStrict) ⇒
        val data = entityStrict.getData()
        val utf8 = HttpCharsets.`UTF-8`
        val charset = entityStrict.contentType.charsetOption.getOrElse(utf8)

        event
          .attribute("request", String.valueOf(request))
          .attribute("request-entity", String.valueOf(entity))
          .attribute("request-entity-strict", entityStrict.toString())
          .attribute("request-entity-string", data.decodeString(charset.nioCharset()))

      case Failure(exception) ⇒
        event.attribute("entity-strict-error", exception2string(exception))
        Event.exception("request entity to strict", exception).send()
    }

    event
  }

  protected def logRequest(request: HttpRequest, rejected: Boolean)(implicit actorSystem: ActorSystem): Unit = {
    val service = if(rejected) "rpc route rejected" else "rpc route"

    addRequestAttributes(Event.warning(service), request).send()
  }

  protected def _handleRejections(request: HttpRequest, handler: RejectionHandler)(implicit actorSystem: ActorSystem): Directive0 = {
    val handled = handleRejections(handler)
    handled.andThen { _ ⇒ logRequest(request, true) }
    handled
  }

  protected def routeRequest(request: HttpRequest)(implicit actorSystem: ActorSystem): Route = cors(corsSettings) {
    _handleRejections(request, myRejectionHandler(request))(actorSystem) {
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
  }

  def route(implicit actorSystem: ActorSystem): Route =
    toStrictEntity(FiniteDuration(1*1, TimeUnit.SECONDS)).tapply(_ ⇒ {
      extractRequest.tapply {
        case Tuple1(request) ⇒
          routeRequest(request)
      }
    })

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

  private def handleRequest(request: JsonRpcRequest) = {
    complete(jsonRpcController.handleRequest(request))
  }

  private def handleBatchRequest(requests: Seq[JsonRpcRequest]) = {
    complete(Future.sequence(requests.map(request => jsonRpcController.handleRequest(request))))
  }
}

object JsonRpcHttpServer extends Logger {

  def apply(jsonRpcController: JsonRpcController, config: JsonRpcHttpServerConfig, secureRandom: SecureRandom)
           (implicit actorSystem: ActorSystem): Either[String, JsonRpcHttpServer] = config.mode match {
    case "http" => Right(new BasicJsonRpcHttpServer(jsonRpcController, config)(actorSystem))
    case "https" => Right(new JsonRpcHttpsServer(jsonRpcController, config, secureRandom)(actorSystem))
    case _ => Left(s"Cannot start JSON RPC server: Invalid mode ${config.mode} selected")
  }

  trait JsonRpcHttpServerConfig {
    val mode: String
    val enabled: Boolean
    val interface: String
    val port: Int
    val certificateKeyStorePath: Option[String]
    val certificateKeyStoreType: Option[String]
    val certificatePasswordFile: Option[String]
    val corsAllowedOrigins: HttpOriginRange
  }

  object JsonRpcHttpServerConfig {
    import com.typesafe.config.{Config ⇒ TypesafeConfig}

    def apply(mantisConfig: TypesafeConfig): JsonRpcHttpServerConfig = {
      val rpcHttpConfig = mantisConfig.getConfig("network.rpc.http")

      new JsonRpcHttpServerConfig {
        override val mode: String = rpcHttpConfig.getString("mode")
        override val enabled: Boolean = rpcHttpConfig.getBoolean("enabled")
        override val interface: String = rpcHttpConfig.getString("interface")
        override val port: Int = rpcHttpConfig.getInt("port")

        override val corsAllowedOrigins = ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins")

        override val certificateKeyStorePath: Option[String] = Try(rpcHttpConfig.getString("certificate-keystore-path")).toOption
        override val certificateKeyStoreType: Option[String] = Try(rpcHttpConfig.getString("certificate-keystore-type")).toOption
        override val certificatePasswordFile: Option[String] = Try(rpcHttpConfig.getString("certificate-password-file")).toOption
      }
    }
  }
}
