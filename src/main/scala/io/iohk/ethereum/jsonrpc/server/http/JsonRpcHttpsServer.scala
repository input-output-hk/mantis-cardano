package io.iohk.ethereum.jsonrpc.server.http

import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import io.iohk.ethereum.jsonrpc.JsonRpcController
import io.iohk.ethereum.jsonrpc.server.SslSetup
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import io.iohk.ethereum.utils.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

class JsonRpcHttpsServer(val jsonRpcController: JsonRpcController, config: JsonRpcHttpServerConfig,
                         val secureRandom: SecureRandom)(implicit val actorSystem: ActorSystem)
  extends JsonRpcHttpServer with SslSetup with Logger {

  require(config.certificateConfig.isDefined,
    "HTTPS requires: certificate-keystore-path, certificate-keystore-type and certificate-password-file to be configured")

  override val certificateConfig = config.certificateConfig.get

  def run(): Unit = {
    implicit val materializer = ActorMaterializer()

    maybeHttpsContext match {
      case Right(httpsContext) =>
        Http().setDefaultServerHttpContext(httpsContext)
        val bindingResultF = Http().bindAndHandle(route, config.interface, config.port, connectionContext = httpsContext)

        bindingResultF onComplete {
          case Success(serverBinding) => log.info(s"JSON RPC HTTPS server listening on ${serverBinding.localAddress}")
          case Failure(ex) => log.error("Cannot start JSON HTTPS RPC server", ex)
        }
      case Left(error) => log.error(s"Cannot start JSON HTTPS RPC server due to: $error")
    }
  }

  override def corsAllowedOrigins: HttpOriginRange = config.corsAllowedOrigins

  def maxContentLength: Long = config.maxContentLength
}

object JsonRpcHttpsServer {
  type HttpsSetupResult[T] = Either[String, T]
}
