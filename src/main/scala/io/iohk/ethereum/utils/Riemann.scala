package io.iohk.ethereum.utils

import java.io.{IOException, PrintWriter}
import java.net.InetAddress
import java.util.concurrent.TimeUnit

import io.iohk.ethereum.buildinfo.MantisBuildInfo
import io.iohk.ethereum.utils.events.{EventState, EventTag}
import io.riemann.riemann.client._
import org.apache.commons.io.output.StringBuilderWriter

trait Riemann extends Logger {
  private val hostName = Config.riemann
    .map(_.hostName)
    .getOrElse(InetAddress.getLocalHost().getHostName())

  private val riemannClient: IRiemannClient = {
    // fallback
    val stdoutClient = new RiemannStdoutClient()

    Config.riemann match {
      case Some(config) =>
        val transport = new TcpTransport(config.host, config.port)
        transport.setWriteBufferLimit(config.bufferSize)
        val client = new RiemannClient(transport)
        val batchClient = new RiemannBatchClient(config, client, stdoutClient)

        try {
          batchClient.connect()
          log.info(s"Connected to $batchClient")
          batchClient
        }
        catch {
          case e: IOException =>
            log.warn(s"Could not connect to $batchClient. Will fallback to $stdoutClient and try to reconnect later")
            batchClient
        }

      case None =>
        stdoutClient
    }
  }

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

trait ToRiemann {
  def toRiemann: EventDSL
}
