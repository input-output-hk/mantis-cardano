package io.iohk.ethereum.healthcheck

import io.iohk.ethereum.healthcheck.HealthcheckResult.Mapper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * A [[io.iohk.ethereum.healthcheck.Healthcheck Healthcheck]]
 * transforms the outcome of a business function `f` into a
 * [[io.iohk.ethereum.healthcheck.HealthcheckResult HealthcheckResult]].
 *
 * <p/>
 * It represents a health check, runs it and interprets the outcome.
 * The health check run can produce either a normal result, an application error, or
 * an (unexpected) exception. For each case, the caller can provide functions to transform
 * the outcome to a user-friendly representation, in the form of an error message
 * and possible metadata.
 *
 * @param description An one-word description of the health check.
 * @param f           The function that runs the health check.
 * @param mapResult    A function that interprets the result.
 * @param mapError     A function that interprets the application error.
 * @param mapException A function that interprets the (unexpected) exception.
 * @tparam Error  The type of the application error.
 * @tparam Result The type of the actual value expected by normal termination of `f`.
 *
 * @see [[io.iohk.ethereum.healthcheck.HealthcheckResult]]
 * @see [[io.iohk.ethereum.healthcheck.HealthcheckResult#Mapper]]
 */
case class Healthcheck[Error, Result](
  description: String,
  f: () ⇒ Future[Either[Error, Result]],
  mapResult: Mapper[Result] = Mapper.StdForResult,
  mapError: Mapper[Error] = Mapper.StdForError,
  mapException: Mapper[Throwable] = Mapper.StdForException
) {

  def apply()(implicit ec: ExecutionContext): Future[HealthcheckResult] = {
    f().transform {
      case Success(Left(error)) ⇒
        val (mappedError, mappedMeta) = mapError(error)
        Success(HealthcheckResult(description, mappedError, mappedMeta))

      case Success(Right(result)) ⇒
        val (mappedError, mappedMeta) = mapResult(result)
        Success(HealthcheckResult(description, mappedError, mappedMeta))

      case Failure(t) ⇒
        val (mappedError, mappedMeta) = mapException(t)
        Success(HealthcheckResult(description, mappedError, mappedMeta))
    }
  }
}
