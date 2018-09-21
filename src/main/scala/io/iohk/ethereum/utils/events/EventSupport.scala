package io.iohk.ethereum.utils.events

import io.iohk.ethereum.utils.Riemann

trait EventSupport {
  private[this] def mkService(moreService: String): String =
    appendService(mainService, moreService)

  private[this] def postProcessAndTagMainService(event: EventDSL): EventDSL =
    postProcessEvent(event).tag(mainService)

  private[this] def status(moreService: String, create: String ⇒ EventDSL): EventDSL = {
    val service = mkService(moreService)
    val event = create(service)
    postProcessAndTagMainService(event)
  }

  private[this] def statusTag(moreService: String, create: String ⇒ EventDSL, tag: String): EventDSL = {
    val service = appendService(moreService, tag)
    status(service, create).tag(tag)
  }

  private[this] def statusStart(moreService: String, create: String ⇒ EventDSL): EventDSL =
    statusTag(moreService, create, EventTag.Start)

  private[this] def statusFinish(moreService: String, create: String ⇒ EventDSL): EventDSL =
    statusTag(moreService, create, EventTag.Finish)

  // The main service is always a prefix of the event service.
  // Use something short.
  protected def mainService: String

  // An implementor decides what else is added in the event.
  protected def postProcessEvent(event: EventDSL): EventDSL = event

  // DSL convenience for the eye.
  @inline final protected def Event: this.type = this

  // DSL convenience for the eye.
  // Use like this:
  // {{{
  //    Event(event).send()
  // }}}
  // when `event` comes from elsewhere (e.g. a `ToRiemann.toRiemann` call)
  @inline final protected def apply(event: EventDSL): event.type = event

  /////////////////////////////////////////////////////////
  // ok
  /////////////////////////////////////////////////////////
  protected def ok(moreService: String = ""): EventDSL = status(moreService, Riemann.ok)

  // Start something, signaling "ok"
  protected def okStart(moreService: String = ""): EventDSL = statusStart(moreService, Riemann.ok)
  // Finish something, signaling "ok", meaning all went well.
  protected def okFinish(moreService: String = ""): EventDSL = statusFinish(moreService, Riemann.ok)

  /////////////////////////////////////////////////////////
  // warning
  /////////////////////////////////////////////////////////
  protected def warning(moreService: String = ""): EventDSL = status(moreService, Riemann.warning)

  // Start something, signaling an upfront "warning"
  protected def warningStart(moreService: String = ""): EventDSL = statusStart(moreService, Riemann.warning)
  // Finish something, signaling a "warning". Note that the corresponding Start event can be an "ok"
  protected def warningFinish(moreService: String = ""): EventDSL = statusFinish(moreService, Riemann.warning)

  /////////////////////////////////////////////////////////
  // error
  /////////////////////////////////////////////////////////
  protected def error(moreService: String = ""): EventDSL = status(moreService, Riemann.error)

  // Start something, signaling "error"
  protected def errorStart(moreService: String = ""): EventDSL = statusStart(moreService, Riemann.error)
  // Finish something, signaling "error", meaning all went well.
  protected def errorFinish(moreService: String = ""): EventDSL = statusFinish(moreService, Riemann.error)

  /////////////////////////////////////////////////////////
  // exception
  /////////////////////////////////////////////////////////
  protected def exception(moreService: String, t: Throwable): EventDSL = {
    val service = mkService(moreService)
    val event = Riemann.exception(service, t)
    postProcessAndTagMainService(event)
  }

  protected def exception(t: Throwable): EventDSL = exception("", t)

  protected def exceptionFinish(moreService: String, t: Throwable): EventDSL =
    exception(appendService(moreService, EventTag.Finish), t).tag(EventTag.Finish)

  protected def exceptionFinish(t: Throwable): EventDSL = exceptionFinish("", t)
  protected def exceptionClose(t: Throwable): EventDSL = exception(EventTag.Close, t).tag(EventTag.Close)
}
