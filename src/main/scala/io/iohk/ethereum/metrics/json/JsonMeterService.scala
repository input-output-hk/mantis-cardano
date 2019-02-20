package io.iohk.ethereum.metrics.json

import io.circe.{Encoder, Json}
import io.circe.syntax._
import io.iohk.ethereum.metrics.Metrics
import io.micrometer.core.instrument.Meter

import scala.collection.JavaConverters._

object JsonMeterService {

  private lazy val metrics = Metrics.get()

  def getMetrics: Json = {
    getMetricsJson(MeterCodecs.meterEncoder).foldLeft(Json.obj())(_.deepMerge(_))
  }

  def getMetricsDetails: Json = {
    getMetricsJson(MeterDetailsCodecs.meterEncoder).asJson
  }

  private def getMetricsJson(meterEncoder: Encoder[Meter]) = {
    metrics.registry.getMeters.asScala.map(_.asJson(meterEncoder))
  }

}
