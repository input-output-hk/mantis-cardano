package io.iohk.ethereum.jsonrpc

import io.iohk.ethereum.healthcheck.Healthcheck
import io.iohk.ethereum.healthcheck.HealthcheckResult.Mapper

object JsonRpcHealthcheck {
  /**
   * Constructs a domain-specific version of a [[io.iohk.ethereum.healthcheck.Healthcheck Healthcheck]],
   * the domain being JSON/RPC calls.
   */
  def apply[Result](
    description: String,
    f: () ⇒ ServiceResponse[Result],
    mapResult: Mapper[Result] = Mapper.StdForResult,
    mapError: Mapper[JsonRpcError] = Mapper.StdForError,
    mapException: Mapper[Throwable] = Mapper.StdForException
  ): Healthcheck[JsonRpcError, Result] =
    Healthcheck(description, f, mapResult, mapError, mapException)
}
