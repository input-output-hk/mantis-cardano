package io.iohk.ethereum

import io.iohk.ethereum.extvm.msg.CallResult

package object extvm {
  val QueueBufferSize: Int = 16 * 1024
  val LengthPrefixSize: Int = 4

  /**
   * This is used as a `returnCode` in [[io.iohk.ethereum.extvm.msg.CallResult CallResult]]
   * when there is an exception in the Mantis-VM communication path.
   *
   * FIXME Where are the (other) error codes defined?
   */
  val AwaitMessageFailureCode = 1999

  /**
   * This is used to signal a Mantis-VM error, e.g. Futures timing out.
   *
   * @note `gasRefund` and `gasRemaining` are given because although they are optional in protobuf,
   *        their default values (empty bytestring) cannot be parsed as valid numbers.
   *
   * @note  `returnCode` See comment about
   *        [[io.iohk.ethereum.extvm#AwaitMessageFailureCode AwaitMessageFailureCode]].
   *
   */
  def ErrorCallResult(returnCode: BigInt, gasRemaining: BigInt, gasRefund: BigInt): CallResult = {
    import Implicits.bigintToGByteString

    msg.CallResult(
      error = true,
      returnCode = returnCode,
      gasRemaining = gasRemaining,
      gasRefund = gasRefund
    )
  }
}
