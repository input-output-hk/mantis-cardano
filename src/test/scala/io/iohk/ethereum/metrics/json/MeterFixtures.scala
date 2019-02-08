package io.iohk.ethereum.metrics.json

import java.{lang, util}
import java.util.concurrent.{Callable, TimeUnit}
import java.util.function.Supplier

import io.micrometer.core.instrument.Meter.Id
import io.micrometer.core.instrument.distribution.{HistogramSnapshot, ValueAtPercentile}
import io.micrometer.core.instrument._

import scala.collection.JavaConverters._

object MeterFixtures {

  trait MeterBase {
    val tags: util.List[Tag] = Seq(Tag.of("state", "Big Success")).asJava
    val timeUnit = TimeUnit.MILLISECONDS
    def name: String
    def meterType: Meter.Type
    def id: Id = new Id(name, tags, timeUnit.toString, null, meterType)
    def fullName: String = name + ".state.Big_Success"
  }

  trait FunctionTimerFixtures extends MeterBase {
    override val name: String = "function.timer"

    override val meterType: Meter.Type = Meter.Type.TIMER

    val functionTimer = new FunctionTimer {
      override def count(): Double = 15.0

      override def totalTime(unit: TimeUnit): Double = 90.0

      override def baseTimeUnit(): TimeUnit = timeUnit

      override def getId: Id = id
    }
  }

  trait LongTaskTimerFixtures extends MeterBase {
    override val name: String = "long.task.timer"

    override val meterType: Meter.Type = Meter.Type.LONG_TASK_TIMER

    val longTaskTimer = new LongTaskTimer {
      override def start(): LongTaskTimer.Sample = ???

      override def stop(task: Long): Long = ???

      override def duration(task: Long, unit: TimeUnit): Double = ???

      override def duration(unit: TimeUnit): Double = 100.0

      override def activeTasks(): Int = 2

      override def getId: Id = id
    }
  }

  trait FunctionCounterFixtures extends MeterBase {
    override val name: String = "function.counter"

    override val meterType: Meter.Type = Meter.Type.COUNTER

    val functionCounter = new FunctionCounter {
      override def count(): Double = 24.0

      override def getId: Id = id
    }
  }

  trait CounterFixtures extends MeterBase {
    override val name: String = "counter"

    override val meterType: Meter.Type = Meter.Type.COUNTER

    val counter = new Counter {
      override def increment(amount: Double): Unit = ???

      override def count(): Double = 24.0

      override def getId: Id = id
    }
  }

  trait GaugeFixtures extends MeterBase {
    override val name: String = "gauge"

    override val meterType: Meter.Type = Meter.Type.GAUGE

    val gauge = new Gauge {
      override def value(): Double = 11.0

      override def getId: Id = id
    }
  }

  trait TimeGaugeFixtures extends MeterBase {
    override val name: String = "time.gauge"

    override val meterType: Meter.Type = Meter.Type.GAUGE

    val timeGauge = new TimeGauge {
      override def baseTimeUnit(): TimeUnit = timeUnit

      override def value(): Double = 11.0

      override def getId: Id = id
    }
  }

  trait HistogramFixtures {
    val percentileValues = Array(
      new ValueAtPercentile(0.5, 5000.0),
      new ValueAtPercentile(0.99, 10000.0)
    )

    val histogramCount = 100
    val histogramMax = 15000.0
    val histogramTotal = 500000.00

    val histogramSnapshot = new HistogramSnapshot(
      histogramCount,
      histogramTotal,
      histogramMax,
      percentileValues,
      null,
      null
    )
  }

  trait TimerFixtures extends MeterBase with HistogramFixtures {
    override val name: String = "timer"

    override val meterType: Meter.Type = Meter.Type.TIMER

    val timer = new Timer {
      override def record(amount: Long, unit: TimeUnit): Unit = ???

      override def record[T](f: Supplier[T]): T = ???

      override def recordCallable[T](f: Callable[T]): T = ???

      override def record(f: Runnable): Unit = ???

      override def count(): Long = histogramCount

      override def totalTime(unit: TimeUnit): Double = histogramTotal

      override def max(unit: TimeUnit): Double = histogramMax

      override def baseTimeUnit(): TimeUnit = timeUnit

      override def takeSnapshot(): HistogramSnapshot = histogramSnapshot

      override def getId: Id = id
    }
  }

  trait DistributionSummaryFixtures extends MeterBase with HistogramFixtures {
    override val name: String = "distribution.summary"

    override val meterType: Meter.Type = Meter.Type.DISTRIBUTION_SUMMARY

    val distributionSummary = new DistributionSummary {
      override def record(amount: Double): Unit = ???

      override def count(): Long = histogramCount

      override def totalAmount(): Double = histogramTotal

      override def max(): Double = histogramMax

      override def takeSnapshot(): HistogramSnapshot = histogramSnapshot

      override def getId: Id = id
    }
  }

  trait MeterFixtures extends MeterBase {
    override val name: String = "unhandled.meter"

    override val meterType: Meter.Type = Meter.Type.OTHER

    val meter = new Meter {
      override def getId: Id = id

      override def measure(): lang.Iterable[Measurement] = ???
    }
  }
}
