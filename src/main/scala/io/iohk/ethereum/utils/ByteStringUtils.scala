package io.iohk.ethereum.utils

import akka.util.ByteString
import org.spongycastle.util.encoders.Hex


object ByteStringUtils {
  def hash2string(hash: ByteString): String = Hex.toHexString(hash.toArray)

  def string2hash(hash: String): ByteString = ByteString(Hex.decode(hash))
}
