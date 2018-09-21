package io.iohk.ethereum.ledger

import io.iohk.ethereum.metrics.{Metrics, MetricsContainer}

class BlockPreparatorMetrics(metrics: Metrics) extends MetricsContainer {
  /**
   * Measures the time it takes to execute a transaction that results in success.
   *
   * The measurement is about everything Mantis needs to do
   * (validation, accessing accounts, ..., calling the VM).
   */
  final val TxExecutionSuccessFullTimer = metrics.timer("block.tx.execution.success.full.timer")


  /**
   * Measures the time it takes to execute a transaction that results in an error.
   *
   * The measurement is about everything Mantis needs to do
   * (validation, accessing accounts, ..., calling the VM).
   */
  final val TxExecutionErrorFullTimer = metrics.timer("block.tx.execution.error.full.timer")
}
