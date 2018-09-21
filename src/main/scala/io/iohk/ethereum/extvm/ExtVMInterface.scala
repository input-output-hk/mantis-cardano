package io.iohk.ethereum.extvm

import java.nio.ByteOrder

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Framing, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete, Tcp}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.ByteString
import io.iohk.ethereum.ledger.{InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage}
import io.iohk.ethereum.utils.events._
import io.iohk.ethereum.utils.{BlockchainConfig, VmConfig}
import io.iohk.ethereum.vm._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class ExtVMInterface(externaVmConfig: VmConfig.ExternalConfig, blockchainConfig: BlockchainConfig, testMode: Boolean)(implicit system: ActorSystem)
  extends VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage] with EventSupport {

  protected def mainService: String = externaVmConfig.vmType

  private implicit val materializer = ActorMaterializer()

  private var out: Option[SourceQueueWithComplete[ByteString]] = None

  private var in: Option[SinkQueueWithCancel[ByteString]] = None

  private var vmClient: Option[VMClient] = None

  final val ConnectionInit = "connection init"

  initConnection()

  private def initConnection(): Unit = {
    ExtVMMetrics.VmInitConnectionCounter.increment()
    val counter: Double = ExtVMMetrics.VmInitConnectionCounter.count()
    Event.okStart(ConnectionInit)
      .count(counter).metric(counter)
      .send()

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

    Event.okFinish(ConnectionInit)
      .count(counter).metric(counter)
      .send()

    vmClient = Some(client)
  }

  override final def run(context: PC): PR =
    synchronized(innerRun(context))

  private[this] final val InnerRunSvc = "innerRun"

  @tailrec
  private def innerRun(context: PC): PR = {
    if (vmClient.isEmpty) initConnection()

    // Start event
    Event.okStart(InnerRunSvc)
      .update(ProgramContext.updateEventWithContext(_, context))
      .send()

    Try(vmClient.get.run(context)) match {
      case Success(res) =>
        val event =
          res.error match {
            case Some(errorValue) ⇒
              // This is not an (catastrophic or logic) error from the node's point of view,
              // it is just a VM execution error.
              Event.warningFinish(InnerRunSvc)
                .tag(errorValue.getClass.getSimpleName)
                .attribute(EventAttr.Error, errorValue.toString)

            case None ⇒
              Event.okFinish(InnerRunSvc)
          }

        // Finish event
        event
          .update(ProgramContext.updateEventWithContext(_, context))
          .update(ProgramResult.updateEventWithResult(_, res))
          .send()

        res

      case Failure(ex) =>
        Event.exceptionFinish(InnerRunSvc, ex)
          .update(ProgramContext.updateEventWithContext(_, context))
          .send()

        initConnection()
        innerRun(context)
    }
  }

  def close(): Unit = {
    vmClient.foreach(_.close())
    vmClient = None
  }

}
