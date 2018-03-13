package io.iohk.ethereum.ommers

import akka.actor.{Actor, Props}
import io.iohk.ethereum.domain.{BlockHeader, Blockchain}
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, GetOmmers, RemoveOmmers}

class OmmersPool(blockchain: Blockchain, ommersPoolSize: Int) extends Actor {

  var ommersPool: Seq[BlockHeader] = Nil

  val ommerGenerationLimit: Int = 6 //Stated on section 11.1, eq. (143) of the YP
  val ommerSizeLimit: Int = 2

  override def receive: Receive = {
    case AddOmmers(ommers) =>
      ommersPool = (ommers ++ ommersPool).take(ommersPoolSize).distinct

    case RemoveOmmers(ommers) =>
      val toDelete = ommers.map(_.hash).toSet
      ommersPool = ommersPool.filter(b => !toDelete.contains(b.hash))

    case GetOmmers(blockNumber) =>
      val ommers = ommersPool.filter { b =>
        val generationDifference = blockNumber - b.number
        generationDifference > 0 && generationDifference <= ommerGenerationLimit
      }.filter { b =>
        blockchain.getBlockHeaderByHash(b.parentHash).isDefined
      }.take(ommerSizeLimit)
      sender() ! OmmersPool.Ommers(ommers)
  }
}

object OmmersPool {
  def props(blockchain: Blockchain, ommersPoolSize: Int): Props = Props(new OmmersPool(blockchain, ommersPoolSize))

  case class AddOmmers(ommers: List[BlockHeader])

  object AddOmmers {
    def apply(b: BlockHeader*): AddOmmers = AddOmmers(b.toList)
  }

  case class RemoveOmmers(ommers: List[BlockHeader])

  object RemoveOmmers {
    def apply(b: BlockHeader*): RemoveOmmers = RemoveOmmers(b.toList)
  }

  case class GetOmmers(blockNumber: BigInt)

  case class Ommers(headers: Seq[BlockHeader])
}
