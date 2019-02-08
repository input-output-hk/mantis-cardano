package io.iohk.ethereum.metrics.json

import io.circe.Json
import io.circe.parser._
import io.iohk.ethereum.metrics.json.MeterCodecs._
import io.iohk.ethereum.metrics.json.MeterFixtures._
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class MeterCodecsSpec extends FlatSpec with Matchers with EitherValues {

  "functionTimerEncoder" should "transform FunctionTimer to flat Json" in new Fixtures with FunctionTimerFixtures {

    override val expectedJsonStr =
      s"""{
        |"$fullName.totalTime" : 90.0,
        |"$fullName.mean" : 6.0,
        |"$fullName.count" : 15.0
        |}
      """.stripMargin

    functionTimerEncoder(functionTimer) shouldEqual expectedJson
  }

  "longTaskTimerEncoder" should "transform LongTaskTimer to flat Json" in new Fixtures with LongTaskTimerFixtures {

    override val expectedJsonStr =
      s"""{
         |"$fullName.activeTasks" : 2,
         |"$fullName.duration" : 100.0
         |}
      """.stripMargin

    longTaskTimerEncoder(longTaskTimer) shouldEqual expectedJson
  }

  "functionCounterEncoder" should "transform FunctionCounter to flat Json" in new Fixtures with FunctionCounterFixtures {

    override val expectedJsonStr =
      s"""{
        |"$fullName.count" : 24.0
        |}
      """.stripMargin

    functionCounterEncoder(functionCounter) shouldEqual expectedJson
  }

  "counterEncoder" should "transform Counter to flat Json" in new Fixtures with CounterFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.count" : 24.0
        |}
      """.stripMargin

    counterEncoder(counter) shouldEqual expectedJson
  }

  "gaugeEncoder" should "transform Gauge to flat Json" in new Fixtures with GaugeFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.value" : 11.0
        |}
      """.stripMargin

    gaugeEncoder(gauge) shouldEqual expectedJson
  }

  "timeGaugeEncoder" should "transform TimeGauge to flat Json" in new Fixtures with TimeGaugeFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.value" : 11.0
        |}
      """.stripMargin

    timeGaugeEncoder(timeGauge) shouldEqual expectedJson
  }

  "timerEncoder" should "transform Timer to flat Json" in new Fixtures with TimerFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.totalTime" : 500000.0,
        |"$fullName.mean" : 5000.0,
        |"$fullName.max" : 15000.0,
        |"$fullName.count" : 100,
        |"$fullName.p50" : 0.005,
        |"$fullName.p99" : 0.01
        |}
      """.stripMargin

    timerEncoder(timer) shouldEqual expectedJson
  }

  "distributionSummaryEncoder" should "transform DistributionSummary to flat Json" in new Fixtures with DistributionSummaryFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.totalAmount" : 500000.0,
        |"$fullName.mean" : 5000.0,
        |"$fullName.max" : 15000.0,
        |"$fullName.count" : 100,
        |"$fullName.p50" : 5000.0,
        |"$fullName.p99" : 10000.0
        |}
      """.stripMargin

    distributionSummaryEncoder(distributionSummary) shouldEqual expectedJson
  }

  "meterEncoder" should "transform unhandled Meter to flat Json" in new Fixtures with MeterFixtures {
    override val expectedJsonStr =
      s"""{
        |"$fullName.description" : "unhandled meter"
        |}
      """.stripMargin

    meterEncoder(meter) shouldEqual expectedJson
  }

  trait Fixtures {
    def expectedJsonStr: String

    def expectedJson: Json = parse(expectedJsonStr).right.value
  }

}
