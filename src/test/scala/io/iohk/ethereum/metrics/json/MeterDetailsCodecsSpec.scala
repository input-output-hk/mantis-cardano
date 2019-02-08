package io.iohk.ethereum.metrics.json

import io.circe.Json
import io.circe.parser._
import io.iohk.ethereum.metrics.json.MeterDetailsCodecs._
import io.iohk.ethereum.metrics.json.MeterFixtures._
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class MeterDetailsCodecsSpec extends FlatSpec with Matchers with EitherValues {

  "functionTimerEncoder" should "transform FunctionTimer to Json with details" in new Fixtures with FunctionTimerFixtures {
    override val additionalJsonFields =
      """
        |"totalTime" : 90.0,
        |"mean" : 6.0,
        |"count" : 15.0
      """.stripMargin

    functionTimerEncoder(functionTimer) shouldEqual expectedJson
  }

  "longTaskTimerEncoder" should "transform LongTaskTimer to Json with details" in new Fixtures with LongTaskTimerFixtures {
    override val additionalJsonFields =
      """
        |"activeTasks" : 2,
        |"duration" : 100.0
      """.stripMargin

    longTaskTimerEncoder(longTaskTimer) shouldEqual expectedJson
  }

  "functionCounterEncoder" should "transform FunctionCounter to Json with details" in new Fixtures with FunctionCounterFixtures {
    override val additionalJsonFields =
      """
        |"count" : 24.0
      """.stripMargin

    functionCounterEncoder(functionCounter) shouldEqual expectedJson
  }

  "counterEncoder" should "transform Counter to Json with details" in new Fixtures with CounterFixtures {
    override val additionalJsonFields =
      """
        |"count" : 24.0
      """.stripMargin

    counterEncoder(counter) shouldEqual expectedJson
  }

  "gaugeEncoder" should "transform Gauge to Json with details" in new Fixtures with GaugeFixtures {
    override val additionalJsonFields =
      """
        |"value" : 11.0
      """.stripMargin

    gaugeEncoder(gauge) shouldEqual expectedJson
  }

  "timeGaugeEncoder" should "transform TimeGauge to Json with details" in new Fixtures with TimeGaugeFixtures {
    override val additionalJsonFields =
      """
        |"value" : 11.0
      """.stripMargin

    timeGaugeEncoder(timeGauge) shouldEqual expectedJson
  }

  "timerEncoder" should "transform Timer to Json with details" in new Fixtures with TimerFixtures {
    override val additionalJsonFields =
      """
        |"totalTime" : 500000.0,
        |"mean" : 5000.0,
        |"max" : 15000.0,
        |"count" : 100,
        |"p50" : 0.005,
        |"p99" : 0.01
      """.stripMargin

    timerEncoder(timer) shouldEqual expectedJson
  }

  "distributionSummaryEncoder" should "transform DistributionSummary to Json with details" in new Fixtures with DistributionSummaryFixtures {
    override val additionalJsonFields =
      """
        |"totalAmount" : 500000.0,
        |"mean" : 5000.0,
        |"max" : 15000.0,
        |"count" : 100,
        |"p50" : 5000.0,
        |"p99" : 10000.0
      """.stripMargin

    distributionSummaryEncoder(distributionSummary) shouldEqual expectedJson
  }

  "meterEncoder" should "transform unhandled Meter to Json with details" in new Fixtures with MeterFixtures {
    override val additionalJsonFields =
      """
        |"description" : "unhandled meter"
      """.stripMargin

    meterEncoder(meter) shouldEqual expectedJson
  }

  trait Fixtures {
    self: MeterBase =>

    val tagsJsonStr =
      """
        |"tags" : [
        | {
        |   "key" : "state",
        |   "value" : "Big Success"
        | }
        |]
      """.stripMargin

    def additionalJsonFields: String

    def jsonStr: String =
      s"""
         |{
         |  "name" : "$name",
         |  "type" : "$meterType",
         |  $tagsJsonStr,
         |  $additionalJsonFields
         |}
      """.stripMargin

    def expectedJson: Json = parse(jsonStr).right.value
  }

}
