package io.iohk.ethereum.jsonrpc.server.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.iohk.ethereum.jsonrpc._
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import io.iohk.ethereum.utils.Logger

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

class BasicJsonRpcHttpServer(_jsonRpcController: JsonRpcController, config: JsonRpcHttpServerConfig)
                            (implicit val actorSystem: ActorSystem)
  extends JsonRpcHttpServer with Logger {

  val dispatcherIdPath: String = JsonRpcHttpServer.JsonRpcHttpDispatcherId.configPath

  implicit val routeExecutionContext: ExecutionContextExecutor = actorSystem.dispatchers.lookup(dispatcherIdPath)

  val jsonRpcController: JsonRpcController = _jsonRpcController.withExecutionContext(routeExecutionContext)

  def run(): Unit = {
    val materializerSettings = ActorMaterializerSettings(actorSystem).withDispatcher(dispatcherIdPath)
    implicit val materializer = ActorMaterializer(materializerSettings)

    val bindingResultF = Http(actorSystem).bindAndHandle(route, config.interface, config.port)

    bindingResultF onComplete {
      case Success(serverBinding) => log.info(s"JSON RPC HTTP server listening on ${serverBinding.localAddress}")
      case Failure(ex) => log.error("Cannot start JSON HTTP RPC server", ex)
    }
  }

  override def corsAllowedOrigins: HttpOriginRange = config.corsAllowedOrigins

  def maxContentLength: Long = config.maxContentLength
}
