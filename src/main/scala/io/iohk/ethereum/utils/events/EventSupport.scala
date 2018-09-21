package io.iohk.ethereum.utils.events

import io.iohk.ethereum.utils.Riemann

trait EventSupport {
  // The main service is always a prefix of the event service.
  // Use something short.
  protected def mainService: String

  // An implementor decides what else is added in the event.
  protected def postProcessEvent(event: EventDSL): EventDSL = event

  private[this] def mkService(moreService: String): String =
    if(moreService.isEmpty) mainService else s"${mainService} ${moreService}"

  private[this] def postProcessAndTagMainService(event: EventDSL): EventDSL =
    postProcessEvent(event).tag(mainService)

  // DSL convenience for the eye.
  @inline final protected def Event: this.type = this

  // DSL convenience for the eye.
  // Use like this:
  //    Event(event).send()
  // when `event` comes from elsewhere (e.g. a `ToRiemann.toRiemann` call)
  @inline final protected def apply(event: EventDSL): event.type = event

  protected def ok(moreService: String): EventDSL = {
    val service = mkService(moreService)
    val event = Riemann.ok(service)
    postProcessAndTagMainService(event)
  }

  protected def ok(): EventDSL = ok("")

  private[this] def okTagged(moreService: String, tag: String): EventDSL = {
    val service = if(moreService.isEmpty) tag else s"${moreService} ${tag}"
    ok(service).tag(tag)
  }

  protected def okStart(moreService: String): EventDSL = okTagged(moreService, EventTag.Start)

  protected def okStart(): EventDSL = okStart("")

  protected def okFinish(moreService: String): EventDSL = okTagged(moreService, EventTag.Finish)

  protected def okFinish(): EventDSL = okFinish("")

  protected def warning(moreService: String): EventDSL = {
    val service = mkService(moreService)
    val event = Riemann.warning(service)
    postProcessAndTagMainService(event)
  }

  private[this] def warningTagged(moreService: String, tag: String): EventDSL = {
    val service = if(moreService.isEmpty) tag else s"${moreService} ${tag}"
    warning(service).tag(tag)
  }

  protected def warningFinish(moreService: String): EventDSL = warningTagged(moreService, EventTag.Finish)

  protected def warningStart(moreService: String): EventDSL = warningTagged(moreService, EventTag.Start)

  protected def warningStart(): EventDSL = warningStart("")

  protected def error(moreService: String): EventDSL = {
    val service = mkService(moreService)
    val event = Riemann.warning(service)
    postProcessAndTagMainService(event)
  }

  protected def exception(moreService: String, t: Throwable): EventDSL = {
    val service = mkService(moreService)
    val event = Riemann.exception(service, t)
    postProcessAndTagMainService(event)
  }

  protected def exception(t: Throwable): EventDSL = exception("", t)
  protected def exceptionFinish(t: Throwable): EventDSL = exception(EventTag.Finish, t).tag(EventTag.Finish)
}
