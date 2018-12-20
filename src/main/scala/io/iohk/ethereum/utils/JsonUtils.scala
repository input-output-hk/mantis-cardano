package io.iohk.ethereum.utils

import io.iohk.ethereum.healthcheck.HealthcheckStatus
import org.json4s.DefaultFormats

object JsonUtils {
  implicit val Formats = DefaultFormats + HealthcheckStatus.JsonSerializer
  implicit val Serialization = org.json4s.native.Serialization

  def pretty[A <: AnyRef](obj: A): String = Serialization.writePretty(obj)
}
