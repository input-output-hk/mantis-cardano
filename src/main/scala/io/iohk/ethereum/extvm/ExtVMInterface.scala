package io.iohk.ethereum.extvm

import java.nio.ByteOrder

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, OverflowStrategy}
import akka.stream.scaladsl.{Framing, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import atmos.dsl._
import Slf4jSupport._
import io.iohk.ethereum.async.DispatcherId
import io.iohk.ethereum.ledger.{InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage}
import io.iohk.ethereum.utils.{BlockchainConfig, VmConfig}
import io.iohk.ethereum.vm._

import scala.concurrent.ExecutionContextExecutor

class ExtVMInterface(externaVmConfig: VmConfig.ExternalConfig, blockchainConfig: BlockchainConfig, testMode: Boolean)(implicit system: ActorSystem)
  extends VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage] with VMEventSupport[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage] {

  val dispatcherIdPath: String = ExtVMInterface.ExtVMDispatcherId.configPath
  implicit val extVmExecutionContext: ExecutionContextExecutor = system.dispatchers.lookup(dispatcherIdPath)

  val materializerSettings = ActorMaterializerSettings(system).withDispatcher(dispatcherIdPath)
  implicit val materializer = ActorMaterializer(materializerSettings)

  private implicit val retryPolicy = retryFor(externaVmConfig.retry.times.attempts)
    .using(linearBackoff(externaVmConfig.retry.delay))
    .monitorWith(log onRetrying logWarning onAborted logError onInterrupted logError)
    .onError { case _ =>
      close()
      keepRetrying
    }

  private var out: Option[SourceQueueWithComplete[ByteString]] = None

  private var in: Option[SinkQueueWithCancel[ByteString]] = None

  private var vmClient: Option[VMClient] = None

  private def initConnection(): Unit = {
    close()

    val connection = Tcp().outgoingConnection(externaVmConfig.host, externaVmConfig.port)

    val (connOut, connIn) = Source.queue[ByteString](QueueBufferSize, OverflowStrategy.dropTail)
      .via(connection)
      .via(Framing.lengthField(LengthPrefixSize, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN))
      .map(_.drop(4))
      .toMat(Sink.queue[ByteString]())(Keep.both)
      .run()

    out = Some(connOut)
    in = Some(connIn)

    val client = new VMClient(externaVmConfig, new MessageHandler(connIn, connOut), testMode)
    client.sendHello(ApiVersionProvider.version, blockchainConfig)
    //TODO: await hello response, check version

    vmClient = Some(client)
  }

  /**
    * Runs a program on the VM.
    * Note: exceptions are handled by retrying the connection. If it still fails then users of this class are expected
    * to handle the exception.
    */
  override final def run(context: PC): PR = synchronized {
    retry(s"External VM call") {
      if (vmClient.isEmpty) initConnection()
      vmClient.get.run(context)
    }
  }

  def close(): Unit = {
    vmClient.foreach(_.close())
    vmClient = None
  }
}

object ExtVMInterface {
  final val ExtVMDispatcherId = DispatcherId("mantis.async.dispatchers.extvm")
}
