package io.iohk.ethereum

import akka.actor.Props

package object async {

  /**
   * A configuration id for dispatchers.
   * We use `configPath` as a path to the underlying application configuration.
   *
   * @see [[io.iohk.ethereum.utils.Config Config]]
   */
  final case class DispatcherId(configPath: String)

  implicit class RichProps(val props: Props) extends AnyVal {
    def withDispatcher(dispatcherId: DispatcherId): Props = props.withDispatcher(dispatcherId.configPath)
  }
}
