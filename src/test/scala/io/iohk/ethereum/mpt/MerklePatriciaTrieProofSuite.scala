package io.iohk.ethereum.mpt

import java.nio.ByteBuffer

import akka.util.ByteString
import io.iohk.ethereum.db.dataSource.EphemDataSource
import io.iohk.ethereum.db.storage.{ArchiveNodeStorage, NodeStorage}
import io.iohk.ethereum.mpt.MerklePatriciaTrie.{ProofStep, defaultByteArraySerializable, nodeDec}
import io.iohk.ethereum.{ObjectGenerators, rlp}
import org.scalatest.FunSuite
import org.scalatest.prop.PropertyChecks
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec


object Helpers {
  implicit class String2Hex(val s: String) extends AnyVal {
    def toHexBytes: Array[Byte] = Hex.decode(s)
  }

  implicit class ByteArray2ByteString(val a: Array[Byte]) extends AnyVal {
    def toByteString: ByteString = ByteString(a)

    def toHexString: String = Hex.toHexString(a)

    private[mpt] def toDebugHexString: String = {
      val hs = toHexString
      hs.substring(0, 4) + "_" + hs.substring(hs.length - 4, hs.length)
    }
  }

}
//noinspection ScalaStyle
class MerklePatriciaTrieProofSuite extends FunSuite
  with PropertyChecks
  with ObjectGenerators {

  import Helpers._

  implicit val intByteArraySerializable = new ByteArraySerializable[Int] {
    override def toBytes(input: Int): Array[Byte] = {
      val b: ByteBuffer = ByteBuffer.allocate(4)
      b.putInt(input)
      b.array
    }

    override def fromBytes(bytes: Array[Byte]): Int = ByteBuffer.wrap(bytes).getInt()
  }

  val Key_000001 = "000001"
  val Key_000002 = "000002"
  val Key_000003 = "000003"
  val Key_10F000 = "010000"
  val Key_101F00 = "101F00"
  val Key_1012F0 = "1012F0"
  val Key_20000F = "20000F"

  val Val_000001 = "one__"
  val Val_000002 = "two__"
  val Val_000003 = "three"
  val Val_10F000 = "do___"
  val Val_101F00 = "dog__"
  val Val_1012F0 = "doge_"
  val Val_20000F = "horse"

  val data = Map(
    Key_000001 → Val_000001,
    Key_000002 → Val_000002,
    Key_000003 → Val_000003,
    Key_10F000 → Val_10F000,
    Key_101F00 → Val_101F00,
    Key_1012F0 → Val_1012F0,
    Key_20000F → Val_20000F
  )

  type MPT = MerklePatriciaTrie[Array[Byte], Array[Byte]]

  @tailrec final def addAll(mpt: MPT, items: Iterator[(String, String)]): MPT =
    if(items.hasNext) {
      val (key, value) = items.next()
      val decodedKey = Hex.decode(key)

      val newMpt = mpt.put(decodedKey, value.getBytes("UTF-8"))

      val newRootHash = newMpt.getRootHash
      val targetNode = mpt.get(decodedKey)
      println(s"Added $key -> $value, new rootHash = ${newRootHash.toDebugHexString}")
      addAll(newMpt, items)
    }
    else {
      mpt
    }

  val db = new ArchiveNodeStorage(new NodeStorage(EphemDataSource()))
  val emptyMpt = MerklePatriciaTrie[Array[Byte], Array[Byte]](db)
  val initialRootHash = emptyMpt.getRootHash
  println(s"initialRootHash = ${initialRootHash.toDebugHexString}")
  val mpt = addAll(emptyMpt, data.iterator)
  val rootHash = mpt.getRootHash
  println()
  println(s"rootHash = ${rootHash.toDebugHexString}")

  def mptFind(key: String): Option[String] = mpt.get(key.toHexBytes).map(s ⇒ new String(s, "UTF-8"))
  def mptGet(key: String): String = mptFind(key).get
  def mptProve(key: String): Seq[ProofStep] = mpt.prove(key.toHexBytes).getOrElse(Seq())

  def nodeEncoded2Node(bytes: NodeStorage.NodeEncoded): MptNode = rlp.decode[MptNode](bytes)
  def dbFind(hash: ByteString): Option[MptNode] = db.get(hash).map(bytes ⇒ nodeEncoded2Node(bytes))
  def dbGet(hash: ByteString): MptNode = dbFind(hash).get
  def dbFind(hash: Array[Byte]): Option[MptNode] = dbFind(ByteString(hash))
  def dbGet(hash: Array[Byte]): MptNode = dbGet(ByteString(hash))


  test("Proof that key exists") {
    // key to prove existence for
    val key = Key_1012F0 // TODO: randomize
    val value = mptGet(key)
    assert(value == data(key))

    // Now, first hash is the hash of the node we got the key for
    // and  last  hash is the root hash
    val proofSteps = mptProve(Key_1012F0)
    for {
      (proofStep, index) ← proofSteps.zipWithIndex
    } {
      val hash = proofStep.hashToDebugHexString
      val snibbles = proofStep.nibblesToString
      println(s"$index. $snibbles - $hash")
    }
    val proofStepRoot = proofSteps.head

    // Verify that the root returned is the well known root
    assert(proofStepRoot.hash sameElements rootHash)

    // Now verify each proof step by descending from the root to the last node
    @tailrec
    def verify(proofSteps: Iterator[ProofStep]): Unit = {
      if(proofSteps.hasNext) {
        val proofStep = proofSteps.next()
        println(s"Checking $proofStep")
        val hashByteString = proofStep.hashToByteString

        // Use the hash in the proof to get the node from storage.
        // Note that it is RLP-encoded, so we must decode it.
        val nodeOpt = db.get(hashByteString).map(nodeEncoded2Node)
        val node = nodeOpt.get // verification will fail on exception
        MerklePatriciaTrie.verifyProofStep(proofStep, node)

        verify(proofSteps)
      }
    }

    // FIXME: It fails in the last step.
    verify(proofSteps.iterator)
  }
}
