package io.iohk.ethereum.jsonrpc.server.websocket

import java.security.SecureRandom

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.jsonrpc.server.websocket.JsonRpcWebsocketServer.JsonRpcWebsocketServerConfig
import io.iohk.ethereum.jsonrpc.JsonRpcController
import io.iohk.ethereum.jsonrpc.server.SslSetup.CertificateConfig
import io.iohk.ethereum.utils.Logger

import scala.concurrent.ExecutionContext
import scala.util.Try

abstract class BasicJsonRpcWebsocketServer(
    jsonRpcController: JsonRpcController,
    blockchain: Blockchain,
    config: JsonRpcWebsocketServerConfig)(
    implicit ec: ExecutionContext,
    system: ActorSystem,
    materializer: Materializer) extends Logger {

  import JsonRpcWebsocketServer._

  private val pubSubActor = system.actorOf(PubSubActor.props(blockchain))

  private[websocket] def webSocketService(): Flow[Message, Message, NotUsed] = {
    val websocketHandlerActor = system.actorOf(WebsocketHandlerActor.props(jsonRpcController, pubSubActor))

    val sink = Sink.actorRef[Message](websocketHandlerActor, WebsocketHandlerActor.ConnectionClosed)
    val source = Source.queue[Message](MessageQueueBufferSize, OverflowStrategy.dropTail)
    Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
      .mapMaterializedValue { out =>
        websocketHandlerActor ! WebsocketHandlerActor.Init(out)
        NotUsed
      }
  }

  private[websocket] val websocketRoute: Route =
    pathEndOrSingleSlash {
      handleWebSocketMessages(webSocketService())
    }

  def run(): Unit
  def close(): Unit
}

object JsonRpcWebsocketServer {

  def apply(jsonRpcController: JsonRpcController,
            blockchain: Blockchain,
            config: JsonRpcWebsocketServerConfig,
            secureRandom: SecureRandom)
           (implicit ec: ExecutionContext, system: ActorSystem, materializer: Materializer): Either[String, BasicJsonRpcWebsocketServer] =
    config.mode match {
      case "http" => Right(new HttpJsonRpcWebsocketServer(jsonRpcController, blockchain, config))
      case "https" => Right(new HttpsJsonRpcWebsocketServer(jsonRpcController, blockchain, config, secureRandom))
      case _ => Left(s"Cannot start JSON RPC websocket server: Invalid mode ${config.mode} selected")
    }

  val MessageQueueBufferSize = 256

  trait JsonRpcWebsocketServerConfig {
    val mode: String
    val enabled: Boolean
    val interface: String
    val port: Int
    val certificateConfig: Option[CertificateConfig]
  }

  object JsonRpcWebsocketServerConfig {
    import com.typesafe.config.{Config ⇒ TypesafeConfig}

    def apply(mantisConfig: TypesafeConfig): JsonRpcWebsocketServerConfig = {
      val rpcHttpConfig = mantisConfig.getConfig("network.rpc.websocket")

      val maybeCertificateConfig = for {
        certificateKeyStorePath <- Try(rpcHttpConfig.getString("certificate-keystore-path")).toOption
        certificateKeyStoreType <- Try(rpcHttpConfig.getString("certificate-keystore-type")).toOption
        certificatePasswordFile <- Try(rpcHttpConfig.getString("certificate-password-file")).toOption
      } yield CertificateConfig(certificateKeyStorePath, certificateKeyStoreType, certificatePasswordFile)


      new JsonRpcWebsocketServerConfig {
        override val enabled: Boolean = rpcHttpConfig.getBoolean("enabled")
        override val interface: String = rpcHttpConfig.getString("interface")
        override val port: Int = rpcHttpConfig.getInt("port")
        override val mode: String = rpcHttpConfig.getString("mode")
        override val certificateConfig: Option[CertificateConfig] = maybeCertificateConfig
      }
    }
  }
}
