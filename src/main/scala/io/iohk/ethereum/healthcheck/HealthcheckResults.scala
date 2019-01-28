package io.iohk.ethereum.healthcheck

final case class HealthcheckResults(checks: List[HealthcheckResult]) {
  lazy val isOK: Boolean = checks.forall(_.isOK)
}
