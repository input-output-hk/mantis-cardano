package io.iohk.ethereum.utils

import com.googlecode.protobuf.format.FormatFactory
import io.riemann.riemann.Proto.{Event, Msg}
import io.riemann.riemann.client._

import scala.collection.JavaConverters._


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

  private val jsonFormatter = new FormatFactory().createFormatter(FormatFactory.Formatter.JSON)

  override def connect(): Unit = {
    connected = true
  }

  override def sendEvent(event: Event): Promise[Msg] = {
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

  override def toString: String = getClass.getSimpleName
}
