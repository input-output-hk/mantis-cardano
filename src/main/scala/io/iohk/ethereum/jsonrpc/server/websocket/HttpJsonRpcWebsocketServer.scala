package io.iohk.ethereum.jsonrpc.server.websocket

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.jsonrpc.server.websocket.JsonRpcWebsocketServer.JsonRpcWebsocketServerConfig
import io.iohk.ethereum.jsonrpc.JsonRpcController

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class HttpJsonRpcWebsocketServer(
    jsonRpcController: JsonRpcController,
    blockchain: Blockchain,
    appStateStorage: AppStateStorage,
    config: JsonRpcWebsocketServerConfig)(
    implicit ec: ExecutionContext, system: ActorSystem, materializer: Materializer)
  extends BasicJsonRpcWebsocketServer(jsonRpcController, blockchain, appStateStorage, config) {

  private var serverBinding: Option[ServerBinding] = None

  override def run(): Unit = {
    val bindingResultF = Http(system).bindAndHandle(websocketRoute, config.interface, config.port)

    bindingResultF onComplete {
      case Success(sb) =>
        serverBinding = Some(sb)
        log.info(s"JSON RPC websocket server listening on ${sb.localAddress}")
      case Failure(ex) => log.error("Cannot start JSON websocket RPC server", ex)
    }
  }

  override def close(): Unit = {
    serverBinding.foreach(_.unbind())
  }
}
