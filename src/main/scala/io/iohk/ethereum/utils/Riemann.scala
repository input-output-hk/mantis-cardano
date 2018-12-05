package io.iohk.ethereum.utils

import java.io.{IOException, PrintWriter}
import java.net.InetAddress
import java.util.LinkedList
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue, ScheduledExecutorService, TimeUnit}

import com.googlecode.protobuf.format.FormatFactory
import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.utils.events.{EventState, EventTag}
import io.riemann.riemann.Proto.{Event, Msg}
import io.riemann.riemann.client._
import org.apache.commons.io.output.StringBuilderWriter

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

trait Riemann extends Logger {

  private var riemannClient: IRiemannClient = _

  def init(): Unit = {
    def stdoutClient(): IRiemannClient = {
      log.info("create new stdout riemann client")
      val client = new RiemannStdoutClient()
      client.connect()
      client
    }

    val client = Config.riemann match {
      case Some(config) => {
        log.info(s"create new riemann batch client connecting to ${config.host}:${config.port}")

        Try {
          val client = new RiemannBatchClient(config)
          client.connect()
          log.debug("riemann client connected")
          client
        } match {
          case Success(client) => client
          case Failure(ex) =>
            log.error("failed to create riemann batch client, falling back to stdout client", ex)
            stdoutClient()
        }
      }
      case None => stdoutClient()
    }

    riemannClient = client
  }

  private val hostName = Config.riemann
    .map(_.hostName)
    .getOrElse(InetAddress.getLocalHost.getHostName)

  def hostForEvents: String = hostName

  def get(): IRiemannClient = riemannClient

  def close(): Unit = riemannClient.close()

  def defaultEvent: EventDSL = {
    val event = new EventDSL(riemannClient)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
    event.time(seconds).host(hostName)
  }

  def ok(service: String): EventDSL =
    defaultEvent
      .state(EventState.OK)
      .service(s"${MantisBuildInfo.name} ${service}")

  def warning(service: String): EventDSL =
    defaultEvent
      .state(EventState.Warning)
      .service(s"${MantisBuildInfo.name} ${service}")

  def error(service: String): EventDSL =
    defaultEvent
      .state(EventState.Error)
      .service(s"${MantisBuildInfo.name} ${service}")

  def exception(service: String, t: Throwable): EventDSL = {
    // Format message and stacktrace
    val sw = new StringBuilderWriter()
    val pw = new PrintWriter(sw, true)
    t.printStackTrace(pw)

    defaultEvent
      .service(s"${MantisBuildInfo.name} ${service}")
      .state(EventState.Error)
      .tag(EventTag.Exception)
      .tag(t.getClass().getSimpleName())
      .description(sw.toString())
  }

  def critical(service: String): EventDSL =
    defaultEvent
      .state(EventState.Critical)
      .service(s"${MantisBuildInfo.name} ${service}")

}

object Riemann extends Riemann

class RiemannBatchClient(config: RiemannConfiguration) extends IRiemannClient with Logger {
  private def promise[A](v: A): Promise[A] = {
    val p: Promise[A] = new Promise()
    p.deliver(v)
    p
  }

  private def simpleMsg(event: Event) = {
    val msg = Msg.newBuilder().addEvents(event).build()
    promise(msg)
  }

  private val jsonFormatter = new FormatFactory().createFormatter(FormatFactory.Formatter.JSON)

  protected val queue: BlockingQueue[Event] = new ArrayBlockingQueue(config.bufferSize)

  private val client = RiemannClient.tcp(config.host, config.port)

  protected def sendBatch(): Unit = {
    val batch: LinkedList[Event] = new LinkedList()
    queue.drainTo(batch, config.batchSize)
    batch.add(Riemann.ok("riemann batch").metric(batch.size()).build())
    batch.add(Riemann.ok("riemann buffer").metric(queue.size()).build())
    try {
      log.trace("try to send batch")
      val p = client.sendEvents(batch)
      client.flush()
      val result = p.deref()
      log.trace(s"sent batch with result: $result")
    } catch {
      case e: IOException =>
        log.error(e.toString)
        batch.asScala
          .foreach { event =>
            {
              // scalastyle:off
              System.err.println(jsonFormatter.printToString(event))
            }
          }
    }
  }

  val sender: () => Unit = { () =>
    log.trace("run sender")
    while (queue.size() > 0) {
      log.trace("sending batch of Riemann events")
      sendBatch()
      log.trace("sent batch of Riemann events")
    }
  }

  override def sendEvent(event: Event) = {
    val res = queue.offer(event)
    if (!res) {
      log.error("Riemann buffer full")
      // scalastyle:off
      System.err.println(s"${jsonFormatter.printToString(event)}")
    }
    simpleMsg(event)
  }

  override def sendMessage(msg: Msg): IPromise[Msg] = client.sendMessage(msg)

  override def event(): EventDSL = {
    new EventDSL(this)
  }

  override def query(q: String): IPromise[java.util.List[Event]] =
    client.query(q)

  override def sendEvents(events: java.util.List[Event]): IPromise[Msg] = {
    val p = new ChainPromise[Msg]
    events.asScala.foreach { e =>
      val clientPromise = sendEvent(e)
      p.attach(clientPromise)
    }
    p
  }

  override def sendEvents(events: Event*): IPromise[Msg] = {
    val p = new ChainPromise[Msg]
    events.foreach { e =>
      val clientPromise = sendEvent(e)
      p.attach(clientPromise)
    }
    p
  }

  override def sendException(service: String, t: Throwable): IPromise[Msg] =
    client.sendException(service, t)

  private var sendExecutor: ScheduledExecutorService = null

  private def tryConnect(times: Int): Unit = {
    if (times < 1) {
      try {
        client.reconnect()
      } catch {
        case e: IOException =>
          log.error("unable to connect to Riemann, wait and try again")
          Thread.sleep(1000)
          tryConnect(times + 1)
      }
    } else {
      client.reconnect()
    }
  }

  override def connect() = {
    tryConnect(0)
    sendExecutor = Scheduler.startRunner(config.autoFlushMilliseconds, TimeUnit.MILLISECONDS, sender)
  }

  override def close(): Unit = {
    client.close()
    sendExecutor.shutdown()
  }

  override def flush(): Unit = client.flush()

  override def isConnected(): Boolean = client.isConnected

  override def reconnect(): Unit = {
    client.reconnect()
    sendExecutor.shutdown()
    sendExecutor = Scheduler.startRunner(config.autoFlushMilliseconds, TimeUnit.MILLISECONDS, sender)
  }

  override def transport(): Transport = this
}

class RiemannStdoutClient extends IRiemannClient {
  private var connected = false

  private def promise[A](v: A): Promise[A] = {
    val p: Promise[A] = new Promise()
    p.deliver(v)
    p
  }

  private def simpleMsg(event: Event) = {
    val msg = Msg.newBuilder().addEvents(event).build()
    promise(msg)
  }

  private val jsonFormatter = (new FormatFactory()).createFormatter(FormatFactory.Formatter.JSON)

  override def connect() = {
    connected = true
  }

  override def sendEvent(event: Event) = {
    // scalastyle:off
    println(jsonFormatter.printToString(event))
    simpleMsg(event)
  }

  override def sendMessage(msg: Msg): IPromise[Msg] = {
    // scalastyle:off
    println(jsonFormatter.printToString(msg))
    promise(msg)
  }

  override def event(): EventDSL = {
    new EventDSL(this)
  }

  override def query(q: String): IPromise[java.util.List[Event]] = {
    promise(new java.util.LinkedList())
  }

  override def sendEvents(events: java.util.List[Event]): IPromise[Msg] = {
    events.asScala.foreach { e =>
      // scalastyle:off
      println(jsonFormatter.printToString(e))
    }
    val msg = Msg.newBuilder().build()
    promise(msg)
  }

  override def sendEvents(events: Event*): IPromise[Msg] = {
    events.foreach { e =>
      // scalastyle:off
      println(jsonFormatter.printToString(e))
    }
    val msg = Msg.newBuilder().build()
    promise(msg)
  }

  override def sendException(service: String, t: Throwable): IPromise[Msg] = {
    // scalastyle:off
    System.err.println(s"service: ${service}\n${t}")
    val msg = Msg.newBuilder().build()
    promise(msg)
  }
  override def close(): Unit = {
    connected = false
  }

  override def flush(): Unit = {}

  override def isConnected(): Boolean = connected

  override def reconnect(): Unit = {
    connected = true
  }

  override def transport(): Transport = this

}

trait ToRiemann {
  def toRiemann: EventDSL
}
