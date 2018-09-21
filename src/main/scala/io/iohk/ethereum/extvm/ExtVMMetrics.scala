package io.iohk.ethereum.extvm

import io.iohk.ethereum.metrics.{Metrics, MetricsContainer}

class ExtVMMetrics(metrics: Metrics) extends MetricsContainer {
  final val VmInitConnectionCounter = metrics.counter("vm.init.connection.counter")
}

object ExtVMMetrics extends ExtVMMetrics(Metrics.get())
