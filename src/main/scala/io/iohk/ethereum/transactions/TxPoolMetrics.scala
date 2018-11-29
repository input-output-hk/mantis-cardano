package io.iohk.ethereum.transactions

import io.iohk.ethereum.metrics.{Metrics, MetricsContainer}

class TxPoolMetrics(metrics: Metrics, txsRepeated: () => Double, maxProvisions: () => Double) extends MetricsContainer {

  /**
    * The number of transactions (currently kept) that were provided by the TX-pool more than once, could mean
    * that there was a problem executing those transaction (but could be other reasons, like block gas limit)
    */
  final val TransactionsRepeated = metrics.gauge("tx-pool.txs-repeated", txsRepeated)

  /**
    * The maximum number of repeats for a single transaction (currently kept in the pool)
    */
  final val TransactionMaxProvisions = metrics.gauge("tx-pool.max-provisions", maxProvisions)

}
