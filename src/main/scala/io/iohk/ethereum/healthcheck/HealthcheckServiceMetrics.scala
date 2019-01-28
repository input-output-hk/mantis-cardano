package io.iohk.ethereum.healthcheck

import io.iohk.ethereum.metrics.{Metrics, MetricsContainer}

class HealthcheckServiceMetrics(metrics: Metrics) extends MetricsContainer {
  final val HealhcheckOKCounter        = metrics.counter("healthcheck.ok.counter")
  final val HealhcheckErrorCounter     = metrics.counter("healthcheck.error.counter")
  final val HealhcheckExceptionCounter = metrics.counter("healthcheck.exception.counter")
}
