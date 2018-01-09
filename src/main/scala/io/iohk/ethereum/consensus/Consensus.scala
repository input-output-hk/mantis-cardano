package io.iohk.ethereum.consensus

import io.iohk.ethereum.validators.BlockHeaderValidator

/**
 * Provides generic requirements from an abstracted consensus scheme.
 * Finer-grained consensus detailed can be hidden in the specific implementation.
 */
// FIXME Lot'f of stuff to do...
trait Consensus {
  def blockHeaderValidator: BlockHeaderValidator
}
