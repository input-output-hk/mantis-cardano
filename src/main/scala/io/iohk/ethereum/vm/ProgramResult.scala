package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, TxLogEntry}
import io.iohk.ethereum.utils.events._

/**
 * Represenation of the result of execution of a contract
 *
 * @param returnData bytes returned by the executed contract (set by [[RETURN]] opcode)
 * @param gasRemaining amount of gas remaining after execution
 * @param world represents changes to the world state
 * @param addressesToDelete list of addresses of accounts scheduled to be deleted
 * @param internalTxs list of internal transactions (for debugging/tracing) if enabled in config
 * @param error defined when the program terminated abnormally
 */
case class ProgramResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
  returnData: ByteString,
  gasRemaining: BigInt,
  world: W,
  addressesToDelete: Set[Address],
  logs: Seq[TxLogEntry],
  internalTxs: Seq[InternalTransaction],
  gasRefund: BigInt,
  error: Option[ProgramError])

object ProgramResult {
  def updateEventWithResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
    event: EventDSL,
    result: ProgramResult[W, S]
  ): EventDSL =
    event
    .attribute("resReturnDataSize", result.returnData.size)
    .attribute("resLogsSize", result.logs.size)
    .attributeSet("resAddressesToDelete", result.addressesToDelete)
    .attribute("resGasRemaining", result.gasRemaining)
    .attribute("resGasRefund", result.gasRefund)
}
