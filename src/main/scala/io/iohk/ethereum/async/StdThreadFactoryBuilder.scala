package io.iohk.ethereum.async

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.iohk.ethereum.utils.Riemann
import io.iohk.ethereum.utils.events.EventAttr

/**
 * Creates [[com.google.common.util.concurrent.ThreadFactoryBuilder ThreadFactoryBuilder]]s,
 * which in turn can be used to configure thread pools (executor services, execution contexts, ...).
 */
object StdThreadFactoryBuilder {
  /**
   * Creates a standard [[com.google.common.util.concurrent.ThreadFactoryBuilder ThreadFactoryBuilder]],
   * with the following characteristics:
   *  - Threads will be created as daemon threads
   *  - Uncaught exceptions are logged to Riemann, using `riemannMainService` as the service name
   *  - Thread names are prefixed with `threadNamePrefix`
   *
   * @param threadNamePrefix   The name prefix of each new thread.
   * @param riemannMainService The Riemann service that will be used.
   */
  def apply(threadNamePrefix: String, riemannMainService: String): ThreadFactoryBuilder = {
    require(threadNamePrefix ne null, "threadNamePrefix is not null")
    require(!threadNamePrefix.trim.isEmpty, "threadNamePrefix is not empty")

    (new ThreadFactoryBuilder)
      .setDaemon(true)
      .setNameFormat(threadNamePrefix + "-%d")
      .setUncaughtExceptionHandler((t: Thread, e: Throwable) ⇒ {
        Riemann.exception(riemannMainService, e)
          .attribute(EventAttr.ThreadId, t.getId.toString)
          .attribute(EventAttr.ThreadName, t.getName)
          .attribute(EventAttr.ThreadPriority, t.getPriority.toString)
          .send()
      })
  }

  /**
   * Creates a standard [[com.google.common.util.concurrent.ThreadFactoryBuilder ThreadFactoryBuilder]],
   * with the following characteristics:
   *  - Threads will be created as daemon threads
   *  - Uncaught exceptions are logged to Riemann, using `threadNamePrefix` as the service name
   *  - Thread names are prefixed with `threadNamePrefix`
   *
   * @param threadNamePrefix   The name prefix of each new thread.
   */
  def apply(threadNamePrefix: String): ThreadFactoryBuilder = apply(threadNamePrefix, threadNamePrefix)
}
