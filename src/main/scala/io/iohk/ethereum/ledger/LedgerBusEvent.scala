package io.iohk.ethereum.ledger

import io.iohk.ethereum.eventbus.BusEvent

sealed trait LedgerBusEvent extends BusEvent
object LedgerBusEvent {
  final case class ImportedBlocks(whenImportedMillis: Long) extends LedgerBusEvent
}
