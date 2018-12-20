package io.iohk.ethereum.healthcheck

import org.json4s.JsonAST.JString
import org.json4s.{CustomSerializer, Formats}

sealed trait HealthcheckStatus
object HealthcheckStatus {
  final case object OK extends HealthcheckStatus
  final case object ERROR extends HealthcheckStatus

  object JsonSerializer extends CustomSerializer[HealthcheckStatus]((_: Formats) ⇒ (
    {
      case JString("OK") ⇒ OK
      case JString("ERROR") ⇒ ERROR
    },
    {
      case OK ⇒ JString("OK")
      case ERROR ⇒ JString("ERROR")
    }
  ))
}
