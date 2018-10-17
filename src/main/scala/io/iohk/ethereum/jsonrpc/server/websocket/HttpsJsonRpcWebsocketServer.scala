package io.iohk.ethereum.jsonrpc.server.websocket

import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.jsonrpc.JsonRpcController
import io.iohk.ethereum.jsonrpc.server.SslSetup
import io.iohk.ethereum.jsonrpc.server.websocket.JsonRpcWebsocketServer.JsonRpcWebsocketServerConfig

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class HttpsJsonRpcWebsocketServer(
    jsonRpcController: JsonRpcController,
    blockchain: Blockchain,
    appStateStorage: AppStateStorage,
    config: JsonRpcWebsocketServerConfig,
    val secureRandom: SecureRandom)(
    implicit ec: ExecutionContext, system: ActorSystem, materializer: Materializer)
  extends BasicJsonRpcWebsocketServer(jsonRpcController, blockchain, appStateStorage, config) with SslSetup {

  require(config.certificateConfig.isDefined,
    "HTTPS requires: certificate-keystore-path, certificate-keystore-type and certificate-password-file to be configured")

  override val certificateConfig = config.certificateConfig.get

  private var serverBinding: Option[ServerBinding] = None

  override def run(): Unit = {

    maybeHttpsContext match {
      case Right(httpsContext) =>
        Http().setDefaultServerHttpContext(httpsContext)
        val bindingResultF = Http(system).bindAndHandle(websocketRoute, config.interface, config.port, connectionContext = httpsContext)

        bindingResultF onComplete {
          case Success(sb) =>
            serverBinding = Some(sb)
            log.info(s"JSON RPC websocket server listening on ${sb.localAddress}")
          case Failure(ex) => log.error("Cannot start JSON websocket RPC server", ex)
        }
      case Left(error) => log.error(s"Cannot start JSON HTTPS RPC server due to: $error")
    }
  }

  override def close(): Unit = {
    serverBinding.foreach(_.unbind())
  }
}
