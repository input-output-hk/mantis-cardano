package io.iohk.ethereum.network.p2p.messages

import akka.util.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.ObjectGenerators
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62.BlockBody
import org.scalatest.prop.PropertyChecks
import org.scalatest.FunSuite
import org.spongycastle.util.encoders.Hex
import NewBlock._
import io.iohk.ethereum.nodebuilder.SecureRandomBuilder
import io.iohk.ethereum.Fixtures.Blocks.BlockOps

class NewBlockSpec extends FunSuite with PropertyChecks  with ObjectGenerators with SecureRandomBuilder {

  val chainId = Hex.decode("3d").head

  test("NewBlock messages are encoded and decoded properly") {
    forAll(newBlockGen(secureRandom, Some(chainId))) { newBlock =>
      val encoded: Array[Byte] = newBlock.toBytes
      val decoded: NewBlock = encoded.toNewBlock
      assert(decoded == newBlock)
    }
  }

  //Expected encoded NewBlock obtained from EthereumJ
  test("NewBlock messages are properly encoded") {
    val actualEncoded = Hex.toHexString(newBlock.toBytes)
    val expectedEncoded = "f90200f901f9f901f4a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347943333333333333333333333333333333333333333a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000830f0000808408000000808000a0000000000000000000000000000000000000000000000000000000000000000088deadbeefdeadbeefc0c0830f0000"
    assert(actualEncoded == expectedEncoded)

    val actualEncoded2 = Hex.toHexString(newBlock2.toBytes)
    val expectedEncoded2 = "f9021cf90215f90210a098352d9c1300bd82334cb3e5034c3ec622d437963f55cf5a00a49642806c2f32a01dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347942cad6e80c7c0b58845fcd71ecad6867c3bd4de20a09b56d589ad6a12373e212fdb6cb4f64d1d7745aea551c7116c665a81a31f9492a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000830f0b3f0a8407ec167f808458a6eb7098d783010507846765746887676f312e372e33856c696e7578a0ea0dec34a635401af44f5245a77b2cd838345615c555c322a3001df4dd0505fe8860d53a11c10d46fbc0c0830f0b3f"
    assert(actualEncoded2 == expectedEncoded2)
  }

  test("NewBlock messages are properly encoded for signed blocks") {
    val keyPair = crypto.generateKeyPair(secureRandom)

    forAll(newBlockGen(secureRandom, Some(chainId))) { newBlock =>
      val signedNewBlock = newBlock.copy(block = newBlock.block.sign(keyPair))

      val encoded: Array[Byte] = signedNewBlock.toBytes
      val decoded: NewBlock = encoded.toNewBlock

      assert(decoded == signedNewBlock)
    }
  }

  val newBlock = NewBlock(
    Block(
      BlockHeader(
        parentHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
        ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
        beneficiary = ByteString(Hex.decode("3333333333333333333333333333333333333333")),
        stateRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        logsBloom = ByteString(Hex.decode("00" * 256)),
        difficulty = BigInt("983040"),
        number = 0,
        gasLimit = 134217728,
        gasUsed = 0,
        unixTimestamp = 0,
        extraData = ByteString(Hex.decode("00")),
        mixHash = ByteString(Hex.decode("00" * 32)),
        nonce = ByteString(Hex.decode("deadbeefdeadbeef"))
      ),
      BlockBody(Seq(), Seq())
    ),
    BigInt("983040")
  )

  val newBlock2 = NewBlock(
    Block(
      BlockHeader(
        parentHash = ByteString(Hex.decode("98352d9c1300bd82334cb3e5034c3ec622d437963f55cf5a00a49642806c2f32")),
        ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
        beneficiary = ByteString(Hex.decode("2cad6e80c7c0b58845fcd71ecad6867c3bd4de20")),
        stateRoot = ByteString(Hex.decode("9b56d589ad6a12373e212fdb6cb4f64d1d7745aea551c7116c665a81a31f9492")),
        transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        logsBloom = ByteString(Hex.decode("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")),
        difficulty = BigInt("985919"),
        number = 10,
        gasLimit = 132912767,
        gasUsed = 0,
        unixTimestamp = 1487334256,
        extraData = ByteString(Hex.decode("d783010507846765746887676f312e372e33856c696e7578")),
        mixHash = ByteString(Hex.decode("ea0dec34a635401af44f5245a77b2cd838345615c555c322a3001df4dd0505fe")),
        nonce = ByteString(Hex.decode("60d53a11c10d46fb"))
      ),
      BlockBody(Seq(), Seq())
    ), BigInt("985919")
  )
}
