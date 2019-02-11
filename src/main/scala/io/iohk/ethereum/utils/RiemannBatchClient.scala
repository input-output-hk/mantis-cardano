package io.iohk.ethereum.utils

import java.io.IOException
import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{LinkedTransferQueue, TimeUnit}

import io.riemann.riemann.Proto
import io.riemann.riemann.Proto.Event
import io.riemann.riemann.client._

import scala.collection.JavaConverters._


class RiemannBatchClient(
  config: RiemannConfiguration,
  client: RiemannClient,
  fallback: RiemannStdoutClient
) extends IRiemannClient with Logger {

  private val batchSize = config.batchSize
  private val autoFlushMs = config.autoFlushMilliseconds
  private val alreadyCalledConnect = new AtomicBoolean(false)
  private val lastIsConnectedRef = new AtomicReference[Option[Boolean]](None)
  private val schedulerRef = new AtomicReference[Option[RiemannScheduler]](None)

  private val queue = new LinkedTransferQueue[Event]()

  private val offerSuccessMsg = Proto.Msg.newBuilder().setOk(true).build()
  private val offerSuccessPromise: IPromise[Proto.Msg] = {
    val p = new Promise[Proto.Msg]()
    p.deliver(offerSuccessMsg)
    p
  }

  private def offer(event: Event): IPromise[Proto.Msg] = {
    queue.offer(event)
    offerSuccessPromise
  }

  private def checkConnected(): Boolean = {
    val isConnected = client.isConnected
    val lastIsConnectedOpt = this.lastIsConnectedRef.get()

    (lastIsConnectedOpt, isConnected) match {
      case (Some(true), false) =>
        log.warn(s"Disconnected from ${this}")
      case (Some(false), true) =>
        log.info(s"Reconnected to ${this}")
      case _ =>
        // no interesting state transition
    }

    this.lastIsConnectedRef.set(Some(isConnected))

    isConnected
  }

  private def drain(): Unit = {
    val batch = new util.ArrayList[Event](batchSize)
    queue.drainTo(batch, batchSize)

    def sendToFallback(): Unit = {
      for(event ← batch.asScala) {
        fallback.sendEvent(event)
      }
    }

    def sendToClient(): Unit = {
      try {
        val msg = Proto.Msg.newBuilder().addAllEvents(batch).build()
        client.sendMessage(msg)
        client.flush()
      }
      catch {
        case e: IOException =>
          log.warn(s"Could not send a batch of ${batch.size()} to $this. Falling back to $fallback")
          sendToFallback()
      }
    }


    if(checkConnected())
      sendToClient()
    else
      sendToFallback()
  }

  // Actually sends the message over the wire directly, instead of using the queue.
  def sendMessage(msg: Proto.Msg): IPromise[Proto.Msg] =
    client.sendMessage(msg)

  // Actually sends the exception over the wire directly, instead of using the queue.
  def sendException(service: String, t: Throwable): IPromise[Proto.Msg] =
    client.sendException(service, t)

  def query(q: String): IPromise[util.List[Proto.Event]] =
    client.query(q)

  def sendEvent(event: Proto.Event): IPromise[Proto.Msg] =
    offer(event)

  def sendEvents(events: Proto.Event*): IPromise[Proto.Msg] = {
    for(event ← events) { offer(event) }
    offerSuccessPromise
  }

  def sendEvents(events: util.List[Proto.Event]): IPromise[Proto.Msg] =
    sendEvents(events.asScala:_*)


  def event(): EventDSL = new EventDSL(this)

  def isConnected: Boolean = client.isConnected

  def connect(): Unit = {
    try client.connect()
    finally {
      if(alreadyCalledConnect.compareAndSet(false, true)) {
        // Setup the scheduler that periodically sends events to Riemann
        val scheduler = client.scheduler()
        schedulerRef.set(Some(scheduler))

        scheduler.every(autoFlushMs, TimeUnit.MILLISECONDS, () ⇒ drain())
      }
    }
  }

  def reconnect(): Unit = {
    close()
    connect()
  }

  def flush(): Unit = client.flush()

  def close(): Unit = {
    fallback.close()
    client.close()
    schedulerRef.get().foreach(_.shutdown())
    // and now the client becomes unusable.
  }

  def transport(): Transport = client.transport()

  override def toString: String = s"Riemann(${config.host}:${config.port})"
}
