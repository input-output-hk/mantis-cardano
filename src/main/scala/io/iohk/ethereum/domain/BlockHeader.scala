package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.crypto.{ECDSASignature, kec256}
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.rlp.{RLPList, encode => rlpEncode}
import io.iohk.ethereum.utils.{ByteStringUtils, Riemann, ToRiemann}
import io.riemann.riemann.client.EventDSL
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.util.encoders.Hex

case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString,
    signature: Option[ECDSASignature] = None) extends ToRiemann {

  override def toString: String = {
    s"""BlockHeader {
       |parentHash: ${Hex.toHexString(parentHash.toArray[Byte])}
       |ommersHash: ${Hex.toHexString(ommersHash.toArray[Byte])}
       |beneficiary: ${Hex.toHexString(beneficiary.toArray[Byte])}
       |stateRoot: ${Hex.toHexString(stateRoot.toArray[Byte])}
       |transactionsRoot: ${Hex.toHexString(transactionsRoot.toArray[Byte])}
       |receiptsRoot: ${Hex.toHexString(receiptsRoot.toArray[Byte])}
       |logsBloom: ${Hex.toHexString(logsBloom.toArray[Byte])}
       |difficulty: $difficulty,
       |number: $number,
       |gasLimit: $gasLimit,
       |gasUsed: $gasUsed,
       |unixTimestamp: $unixTimestamp,
       |extraData: ${Hex.toHexString(extraData.toArray[Byte])}
       |mixHash: ${Hex.toHexString(mixHash.toArray[Byte])}
       |nonce: ${Hex.toHexString(nonce.toArray[Byte])}
       |signature: ${signature.map(s => ByteStringUtils.hash2string(s.toBytes))}
       |}""".stripMargin
  }


  override def toRiemann: EventDSL =
    Riemann.ok("blockheader")
      .attribute("parentHash", Hex.toHexString(parentHash.toArray[Byte]))
      .attribute("ommersHash", Hex.toHexString(ommersHash.toArray[Byte]))
      .attribute("beneficiary", Hex.toHexString(beneficiary.toArray[Byte]))
      .attribute("stateRoot", Hex.toHexString(stateRoot.toArray[Byte]))
      .attribute("transactionsRoot", Hex.toHexString(transactionsRoot.toArray[Byte]))
      .attribute("receiptsRoot", Hex.toHexString(receiptsRoot.toArray[Byte]))
      .attribute("logsBloom", Hex.toHexString(logsBloom.toArray[Byte]))
      .attribute("difficulty", difficulty.toString)
      .attribute("number", number.toString)
      .attribute("gasLimit", gasLimit.toString)
      .attribute("gasUsed", gasUsed.toString)
      .attribute("unixTimestamp", unixTimestamp.toString)
      .attribute("extraData", Hex.toHexString(extraData.toArray[Byte]))
      //TODO: signature?

  /**
    * calculates blockHash for given block header
    * @return - hash that can be used to get block bodies / receipts
    */
  lazy val hash: ByteString = ByteString(kec256(this.toBytes: Array[Byte]))

  lazy val hashAsHexString: String = Hex.toHexString(hash.toArray)

  def idTag: String = {
    val numberHex = number.toString(16)
    s"$number (= 0x$numberHex : 0x$hashAsHexString)"
  }
}

object BlockHeader {

  val NumOfFields = {
    val be = ByteString.empty
    BlockHeader(be, be, be, be, be, be, be, 0, 0, 0, 0, 0, be, be, be).productArity
  }

  def getEncodedWithoutNonce(blockHeader: BlockHeader): Array[Byte] = {
    val rlpEncoded = blockHeader.toRLPEncodable match {
      case rlpList: RLPList => RLPList(rlpList.items.take(NumOfFields - 3): _*)
      case _ => throw new Exception("BlockHeader cannot be encoded without nonce and mixHash")
    }
    rlpEncode(rlpEncoded)
  }

  private def getEncodedWithoutSignature(blockHeader: BlockHeader): Array[Byte] = {
    val rlpEncoded = blockHeader.toRLPEncodable match {
      case rlpList: RLPList => RLPList(rlpList.items.take(NumOfFields - 1): _*)
      case _ => throw new Exception("BlockHeader cannot be encoded")
    }
    rlpEncode(rlpEncoded)
  }

  def sign(blockHeader: BlockHeader, keyPair: AsymmetricCipherKeyPair): ECDSASignature = {
    val m = kec256(getEncodedWithoutSignature(blockHeader))
    ECDSASignature.sign(m, keyPair)
  }

  def recoverPubKey(blockHeader: BlockHeader): Option[ByteString] = {
    val m = kec256(getEncodedWithoutSignature(blockHeader))
    blockHeader.signature.flatMap(_.publicKey(m)).map(ByteString(_))
  }

}
