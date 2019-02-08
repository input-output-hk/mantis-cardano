package io.iohk.ethereum.metrics.json

import java.util.concurrent.TimeUnit

import io.circe.syntax._
import io.circe.{Encoder, Json}
import io.iohk.ethereum.metrics.{Metrics, Percentile}
import io.micrometer.core.instrument._
import io.micrometer.core.instrument.distribution.{HistogramSupport, ValueAtPercentile}

object MeterCodecs extends BaseMeterCodecs {

  override protected def basicDataOf[T <: Meter : MeterData](additionalFields: T => Seq[(String, Json)]): T => Json = {
    meter: T => {
      val baseKey = createBaseKey(meter)

      val fieldsData = MeterData.getFieldsData(meter)

      val additionalFieldsData = additionalFields(meter)

      val allFields = (fieldsData ++ additionalFieldsData).map { case (key, json) =>
        (baseKey + "." + key, json)
      }

      Json.fromFields(allFields)
    }
  }

  private def createBaseKey[T <: Meter](meter: T) = {
    import scala.collection.JavaConverters._
    val baseKey = meter.getId.getName

    val tagKey = {
      if(meter.getId.getTags.isEmpty) {
        ""
      } else {
        meter
          .getId
          .getTags
          .asScala
          .map(tag => s"${tag.getKey}.${normalizeTag(tag.getValue)}")
          .mkString(".")
      }
    }

    if(tagKey.nonEmpty) {
      baseKey + "." + tagKey
    } else baseKey
  }

  private def normalizeTag(value: String) = {
    value.split(" ").mkString("_")
  }

}

object MeterDetailsCodecs extends BaseMeterCodecs {

  override protected def basicDataOf[T <: Meter : MeterData](additionalFields: T => Seq[(String, Json)]): T => Json = {
    meter: T => {
      import scala.collection.JavaConverters._

      val tags = {
        if(meter.getId.getTags.isEmpty) {
          Seq.empty
        } else {
          Seq(("tags", meter.getId.getTags.asScala.asJson))
        }
      }
      Json.fromFields(
        Seq(
          ("name", meter.getId.getName.asJson),
          ("type", meter.getId.getType.name().asJson)
        ) ++ MeterData.getFieldsData(meter) ++ tags ++ additionalFields(meter)
      )
    }
  }

}

trait BaseMeterCodecs {

  import MeterData._

  implicit val timerEncoder: Encoder[Timer] = Encoder.instance {
    basicDataOf { t: Timer =>
      val baseTimeUnit = t.baseTimeUnit()

      val percentilesMetrics = percentileMetricsOf(t, Some(baseTimeUnit))

      percentilesMetrics
    }
  }

  implicit val distributionSummaryEncoder: Encoder[DistributionSummary] = Encoder.instance {
    basicDataOf { ds: DistributionSummary =>
      val percentilesMetrics = percentileMetricsOf(ds)

      percentilesMetrics
    }
  }

  implicit val functionTimerEncoder: Encoder[FunctionTimer] = Encoder.instance {
    emptyBasicDataOf[FunctionTimer]
  }

  implicit val longTaskTimerEncoder: Encoder[LongTaskTimer] = Encoder.instance {
    emptyBasicDataOf[LongTaskTimer]
  }

  implicit val functionCounterEncoder: Encoder[FunctionCounter] = Encoder.instance {
    emptyBasicDataOf[FunctionCounter]
  }

  implicit val counterEncoder: Encoder[Counter] = Encoder.instance {
    emptyBasicDataOf[Counter]
  }

  implicit val gaugeEncoder: Encoder[Gauge] = Encoder.instance {
    emptyBasicDataOf[Gauge]
  }

  implicit val timeGaugeEncoder: Encoder[TimeGauge] = Encoder.instance {
    emptyBasicDataOf[TimeGauge]
  }

  implicit private val defaultMeterEncoder: Encoder[Meter] = Encoder.instance {
    emptyBasicDataOf[Meter]
  }

  implicit protected val tagEncoder: Encoder[Tag] = {
    tag: Tag => {
      Json.obj(
        ("key", tag.getKey.asJson),
        ("value", tag.getValue.asJson)
      )
    }
  }

  private def emptyBasicDataOf[T <: Meter : MeterData] = basicDataOf[T](_ => Seq.empty)

  protected def basicDataOf[T <: Meter : MeterData](additionalFields: T => Seq[(String, Json)]): T => Json

  private def percentileMetricsOf[T <: HistogramSupport](histogram: T, baseTimeUnit: Option[TimeUnit] = None) = {
    val snapshot = histogram.takeSnapshot()
    val pavs = snapshot.percentileValues()

    def transformValueIfNeeded(valueAtPercentile: ValueAtPercentile) = {
      baseTimeUnit
        .fold(valueAtPercentile.value())(timeUnit => valueAtPercentile.value(timeUnit))
    }

    for {
      pav <- pavs
      Percentile(_, name) <- Metrics.percentiles.byNumber.get(pav.percentile())
    } yield (name, transformValueIfNeeded(pav).asJson)
  }

  implicit val meterEncoder: Encoder[Meter] = Encoder.instance {
    case t: Timer => t.asJson
    case ds: DistributionSummary => ds.asJson
    case ft: FunctionTimer => ft.asJson
    case fc: FunctionCounter => fc.asJson
    case c: Counter => c.asJson
    case ltt: LongTaskTimer => ltt.asJson
    case tg: TimeGauge => tg.asJson
    case g: Gauge => g.asJson
    case other => defaultMeterEncoder(other)
  }

}
