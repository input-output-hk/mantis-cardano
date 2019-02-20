package io.iohk.ethereum.metrics.json

import java.util.concurrent.TimeUnit

import io.circe.Json
import io.circe.syntax._
import io.micrometer.core.instrument._

trait MeterData[T <: Meter] {
  def getFieldsData(meter: T): Seq[(String, Json)]
}

object MeterData {

  def apply[T <: Meter](implicit md: MeterData[T]): MeterData[T] = md

  def getFieldsData[T <: Meter](meter: T)(implicit md: MeterData[T]): Seq[(String, Json)] = md.getFieldsData(meter)

  implicit val timerData: MeterData[Timer] = (meter: Timer) => {
    val baseTimeUnit = meter.baseTimeUnit()

    Seq(
      ("totalTime", meter.totalTime(baseTimeUnit).asJson),
      ("mean", meter.mean(baseTimeUnit).asJson),
      ("max", meter.max(baseTimeUnit).asJson),
      ("count", meter.count().asJson)
    )
  }

  implicit val distributionSummaryData: MeterData[DistributionSummary] = (meter: DistributionSummary) => {
    Seq(
      ("totalAmount", meter.totalAmount().asJson),
      ("mean", meter.mean().asJson),
      ("max", meter.max().asJson),
      ("count", meter.count().asJson)
    )
  }

  implicit val functionTimerData: MeterData[FunctionTimer] = (meter: FunctionTimer) => {
    val baseTimeUnit = meter.baseTimeUnit()
    Seq(
      ("totalTime", meter.totalTime(baseTimeUnit).asJson),
      ("mean", meter.mean(baseTimeUnit).asJson),
      ("count", meter.count().asJson)
    )
  }

  implicit val longTaskTimerData: MeterData[LongTaskTimer] = (meter: LongTaskTimer) => {
    Seq(
      ("activeTasks", meter.activeTasks().asJson),
      ("duration", meter.duration(TimeUnit.NANOSECONDS).asJson)
    )
  }

  implicit val functionCounterData: MeterData[FunctionCounter] = (meter: FunctionCounter) => {
    Seq(
      ("count", meter.count().asJson)
    )
  }

  implicit val counterData: MeterData[Counter] = (meter: Counter) => {
    Seq(
      ("count", meter.count().asJson)
    )
  }

  implicit val gaugeData: MeterData[Gauge] = (meter: Gauge) => {
    Seq(
      ("value", meter.value().asJson)
    )
  }

  implicit val timeGaugeData: MeterData[TimeGauge] = (meter: TimeGauge) => {
    val baseTimeUnit = meter.baseTimeUnit()
    Seq(
      ("value", meter.value(baseTimeUnit).asJson)
    )
  }

  implicit val defaultMeterData: MeterData[Meter] = (_: Meter) => {
    Seq(
      ("description", "unhandled meter".asJson)
    )
  }
}
