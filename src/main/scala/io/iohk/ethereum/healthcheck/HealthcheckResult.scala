package io.iohk.ethereum.healthcheck

final case class HealthcheckResult private(
  description: String,
  status: HealthcheckStatus,
  error: Option[String],
  meta: Map[String, String]
) {
  assert(
    status == HealthcheckStatus.OK    && error.isEmpty   ||
    status == HealthcheckStatus.ERROR && error.isDefined
  )

  def isOK: Boolean = status == HealthcheckStatus.OK
}

object HealthcheckResult {
  type MapperResult = (Option[String], Map[String, String])
  type Mapper[A] = A ⇒ MapperResult

  object Mapper {
    private[this] final val MapsResultToNone: Mapper[Any] = (_: Any) ⇒ (None, Map.empty)
    private[this] final val MapsErrorToString: Mapper[Any] = (error: Any) ⇒ (Some(String.valueOf(error)), Map.empty)
    private[this] final val MapsExceptionToString: Mapper[Throwable] = MapsErrorToString

    def StdForResult[R]: Mapper[R] = MapsResultToNone.asInstanceOf[Mapper[R]]

    def StdForError[E]: Mapper[E] = MapsErrorToString.asInstanceOf[Mapper[E]]

    def StdForException: Mapper[Throwable] = MapsExceptionToString
  }

  def apply(description: String, error: Option[String], meta: Map[String, String] = Map()): HealthcheckResult =
    new HealthcheckResult(
      description = description,
      status = if(error.isDefined) HealthcheckStatus.ERROR else HealthcheckStatus.OK,
      error = error,
      meta = meta
    )

  def apply(description: String, error: Option[String], meta: (String, String)*): HealthcheckResult =
    apply(description, error, Map(meta:_*))
}
