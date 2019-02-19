package io.iohk.ethereum.healthcheck

import com.typesafe.config.Config
import io.iohk.ethereum.utils.VmConfig
import io.iohk.ethereum.utils.VmConfig.ExternalConfig

import scala.concurrent.duration._

case class HealthcheckServiceConfig(
  uptimeGracePeriod: Duration,
  lastImportGracePeriod: Duration,
  isIeleVM: Boolean
)

object HealthcheckServiceConfig {
  object Keys {
    /**
     * Corresponds the `healthchecks` section under `mantis`.
     * This whole section can be omitted, in which case default values
     * will be used.
     */
    final val Healthchecks = "healthchecks"

    final val UptimeGracePeriod = "uptime-grace-period"
    final val LastImportGracePeriod = "last-import-grace-period"
  }

  // Slightly different than the default value of "1.0 min" for the
  // `healthchecks.uptime-grace-period` configuration option, so that
  // we can detect possibly missing configuration at runtime ;)
  //
  //    {
  //      "description":"blockImportLatency",
  //      "status":"OK",
  //      "meta":{
  //        "uptime":"0.21355000000000002 min",
  //        "gracePeriod":"1.0166666666666666 min", <--- THIS is caused by the 61 seconds
  //        "check":"uptime"
  //      }
  //    }
  //
  val StdSeconds = 61

  val StdUptimeGracePeriod     = Duration(StdSeconds, SECONDS)
  val StdLastImportGracePeriod = Duration(StdSeconds, SECONDS)


  def apply(mantisConfig: Config, vmConfig: VmConfig): HealthcheckServiceConfig = {
    val isIeleVM = vmConfig.externalConfig.exists(_.vmType == ExternalConfig.VmTypeIele)

    if(!mantisConfig.hasPath(Keys.Healthchecks)) {
      HealthcheckServiceConfig(StdUptimeGracePeriod, StdLastImportGracePeriod, isIeleVM)
    }
    else {
      val config = mantisConfig.getConfig(Keys.Healthchecks)

      def getDuration(path: String, default: Duration): Duration =
        if(config.hasPath(path)) config.getDuration(path).toMillis.millis else default

      val uptimeGracePeriod = getDuration(Keys.UptimeGracePeriod, StdUptimeGracePeriod)
      val lastImportGracePeriod = getDuration(Keys.LastImportGracePeriod, StdLastImportGracePeriod)

      HealthcheckServiceConfig(uptimeGracePeriod, lastImportGracePeriod, isIeleVM)
    }
  }
}
