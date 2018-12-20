package io.iohk.ethereum.eventbus

import java.util.concurrent.atomic.AtomicReference

import com.google.common.eventbus.{SubscriberExceptionContext, SubscriberExceptionHandler}
import com.google.common.{eventbus ⇒ guavabus}
import io.iohk.ethereum.utils.events.EventSupport

/**
 * A marker trait for all bus events in the code base.
 *
 * @note Define `BusEvent`s close to where there are generated (their producers).
 *
 * @see [[TypedEventBus]]
 */
trait BusEvent

/**
 * An event bus in the style of Guava's [[https://github.com/google/guava/wiki/EventBusExplained EventBus]].
 * The idea is that a component (producer) can produce events and some other component (consumer)
 * may be interested in those particular events; the consumer registers interest via the event bus,
 * without exactly knowing the producer. In effect we are decoupling the consumer and the producer.
 *
 * <p/>
 * For the actual registration and notification of consumers we just reuse Guava's
 * [[com.google.common.eventbus.Subscribe Subscribe]] annotation, see also the
 * [[https://github.com/google/guava/wiki/EventBusExplained Guava wiki]]. In this design we consider the use of the
 * [[com.google.common.eventbus.Subscribe Subscribe]] annotation as semantics and implementation re-use,
 * signifying that we adhere to the same ''registration semantics'' as Guava.
 *
 * <p/>
 * Clients of the event bus API are encouraged to obtain a type-constrained version via [[TypedEventBus]].
 *
 * @note An event is delivered synchronously, in the same thread the call to
 *       [[EventBus#post* EventBus.post]] happens.
 *       If you need to do substantial work in the consumer method, consider using a thread pool there.
 *
 *       When posting an event, the consumers are notified in sequence, that is in the order they
 *       were registered.
 *
 * @see [[TypedEventBus]]
 */
trait EventBus {
  def register[H <: AnyRef](handler: H): Unit
  def unregister[H <: AnyRef](handler: H): Unit
  def post[A <: AnyRef](event: A): Unit
}

/**
 * Provides a thin wrapper around an [[EventBus]] that constrains the event type.
 *
 * <p/>
 * The intended programming pattern here is for a producer to specify their event types as subclasses of
 * [[BusEvent]], and then obtain a [[TypedEventBus]] that guarantees only their "own" events can be posted.
 *
 * You can obtain a [[TypedEventBus]] via [[EventBus#get EventBus.get]].
 *
 * @example
 *
 * {{{
 *   import com.google.common.eventbus.Subscribe
 *   import io.iohk.ethereum.eventbus._
 *
 *   case class ClockEvent(millis: Long) extends BusEvent
 *
 *   class ClockEventProducer {
 *     // You can only post `ClockEvent`s on this bus.
 *     val bus = EventBus.get[ClockEvent]
 *
 *     [...]
 *
 *     bus.post(ClockEvent(System.currentTimeMillis))
 *   }
 *
 *   class SomeOtherClassThatNeedsClockEvents {
 *     @Subscribe def onClockEvent(ce: ClockEvent): Unit = { ... }
 *   }
 * }}}
 *
 * @tparam A The event type.
 *
 * @see [[BusEvent]], [[EventBus]]
 */
class TypedEventBus[A <: BusEvent](untyped: EventBus) {
  def register[H <: AnyRef](handler: H): Unit = untyped.register(handler)
  def unregister[H <: AnyRef](handler: H): Unit = untyped.unregister(handler)
  def post(event: A): Unit = untyped.post(event)
}

private[eventbus] class GEventBus extends EventBus with EventSupport {
  private val exceptionHandler = new SubscriberExceptionHandler {
    def handleException(exception: Throwable, context: SubscriberExceptionContext): Unit = {
      val method = context.getSubscriberMethod
      val methodDescriptor = s"${method.getName}(${method.getParameterTypes()(0).getName})"
      val subscriber = context.getSubscriber.getClass.getSimpleName
      val event = context.getEvent
      val description = s"Error dispatching ${event} to ${subscriber}.${methodDescriptor}"

      Event.exception(exception).description(description).send()
    }
  }

  private val internal = new guavabus.EventBus(exceptionHandler)

  def register[H <: AnyRef](handler: H): Unit = internal.register(handler)

  def unregister[H <: AnyRef](handler: H): Unit = internal.unregister(handler)

  def post[A <: AnyRef](event: A): Unit = internal.post(event)
}

object EventBus {
  private val StdGEventBus = new GEventBus
  private val eventBusRef = new AtomicReference[EventBus](StdGEventBus)

  def setOnce(eventBus: EventBus): Boolean = eventBusRef.compareAndSet(StdGEventBus, eventBus)

  def get[A <: BusEvent](): TypedEventBus[A] = new TypedEventBus[A](eventBusRef.get())
}
