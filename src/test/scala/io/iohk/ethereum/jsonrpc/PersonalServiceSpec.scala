package io.iohk.ethereum.jsonrpc

import java.time.Duration

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.ByteString
import com.miguno.akka.testing.VirtualTime
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain._
import io.iohk.ethereum.jsonrpc.JsonRpcErrors._
import io.iohk.ethereum.jsonrpc.PersonalService._
import io.iohk.ethereum.keystore.KeyStore.{DecryptionFailed, IOError, KeyStoreError}
import io.iohk.ethereum.keystore.{KeyStore, Wallet}
import io.iohk.ethereum.transactions.TransactionPool._
import io.iohk.ethereum.utils._
import io.iohk.ethereum.{Fixtures, NormalPatience, Timeouts}
import org.scalamock.matchers.Matcher
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex

import scala.concurrent.duration.FiniteDuration

class PersonalServiceSpec extends FlatSpec with Matchers with MockFactory with ScalaFutures with NormalPatience
  with Eventually with PropertyChecks {

  "PersonalService" should "import private keys" in new TestSetup {
    (keyStore.importPrivateKey _).expects(prvKey, passphrase).returning(Right(address))

    val req = ImportRawKeyRequest(prvKey, passphrase)
    val res = personal.importRawKey(req).futureValue

    res shouldEqual Right(ImportRawKeyResponse(address))
  }

  it should "create new accounts" in new TestSetup {
    (keyStore.newAccount _).expects(passphrase).returning(Right(address))

    val req = NewAccountRequest(passphrase)
    val res = personal.newAccount(req).futureValue

    res shouldEqual Right(NewAccountResponse(address))
  }

  it should "list accounts" in new TestSetup {
    val addresses = List(123, 42, 1).map(Address(_))
    (keyStore.listAccounts _).expects().returning(Right(addresses))

    val res = personal.listAccounts(ListAccountsRequest()).futureValue

    res shouldEqual Right(ListAccountsResponse(addresses))
  }

  it should "translate KeyStore errors to JsonRpc errors" in new TestSetup {
    (keyStore.listAccounts _).expects().returning(Left(IOError("boom!")))
    val res1 = personal.listAccounts(ListAccountsRequest()).futureValue
    res1 shouldEqual Left(LogicError("boom!"))

    (keyStore.unlockAccount _).expects(*, *).returning(Left(KeyStore.KeyNotFound))
    val res2 = personal.unlockAccount(UnlockAccountRequest(Address(42), "passphrase", None)).futureValue
    res2 shouldEqual Left(KeyNotFound)


    (keyStore.unlockAccount _).expects(*, *).returning(Left(KeyStore.DecryptionFailed))
    val res3 = personal.unlockAccount(UnlockAccountRequest(Address(42), "passphrase", None)).futureValue
    res3 shouldEqual Left(InvalidPassphrase)
  }

  it should "return an error when trying to import an invalid key" in new TestSetup {
    val invalidKey = prvKey.tail
    val req = ImportRawKeyRequest(invalidKey, passphrase)
    val res = personal.importRawKey(req).futureValue
    res shouldEqual Left(InvalidKey)
  }

  it should "unlock an account given a correct passphrase" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase).returning(Right(wallet))

    val req = UnlockAccountRequest(address, passphrase, None)
    val res = personal.unlockAccount(req).futureValue

    res shouldEqual Right(UnlockAccountResponse(true))
  }

  it should "send a transaction (given sender address and a passphrase)" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    (appStateStorage.getBestBlockNumber _).expects().returning(1234)
    (blockchain.getAccount _).expects(address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (appStateStorage.getBestBlockNumber _).expects().returning(blockchainConfig.eip155BlockNumber - 1)

    val req = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res = personal.sendTransaction(req)

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "send a transaction when having pending txs from the same sender" in new TestSetup {
    val newTx = wallet.signTx(tx.toTransaction(nonce + 1), None)

    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    (appStateStorage.getBestBlockNumber _).expects().returning(1234)
    (blockchain.getAccount _).expects(address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (appStateStorage.getBestBlockNumber _).expects().returning(blockchainConfig.eip155BlockNumber - 1)

    val req = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res = personal.sendTransaction(req)

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Seq(PendingTransaction(stx, 0))))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(newTx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(newTx))
  }

  it should "fail to send a transaction given a wrong passphrase" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Left(KeyStore.DecryptionFailed))

    val req = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res = personal.sendTransaction(req).futureValue

    res shouldEqual Left(InvalidPassphrase)
    txPool.expectNoMsg()
  }

  it should "send a transaction (given sender address and using an unlocked account)" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).futureValue

    (appStateStorage.getBestBlockNumber _).expects().returning(1234)
    (blockchain.getAccount _).expects(address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (appStateStorage.getBestBlockNumber _).expects().returning(blockchainConfig.eip155BlockNumber - 1)

    val req = SendTransactionRequest(tx)
    val res = personal.sendTransaction(req)

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "fail to send a transaction when account is locked" in new TestSetup {
    val req = SendTransactionRequest(tx)
    val res = personal.sendTransaction(req).futureValue

    res shouldEqual Left(AccountLocked)
    txPool.expectNoMsg()
  }

  it should "lock an unlocked account" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).futureValue

    val lockRes = personal.lockAccount(LockAccountRequest(address)).futureValue
    val txRes = personal.sendTransaction(SendTransactionRequest(tx)).futureValue

    lockRes shouldEqual Right(LockAccountResponse(true))
    txRes shouldEqual Left(AccountLocked)
  }

  it should "sign a message when correct passphrase is sent" in new TestSetup {

    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    val message = ByteString(Hex.decode("deadbeaf"))

    val r = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v = ByteString(Hex.decode("1b")).last

    val req = SignRequest(message, address, Some(passphrase))

    val res = personal.sign(req).futureValue
    res shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))

    // Account should still be locked after calling sign with passphrase
    val txReq = SendTransactionRequest(tx)
    val txRes = personal.sendTransaction(txReq).futureValue
    txRes shouldEqual Left(AccountLocked)

  }

  it should "sign a message using an unlocked account" in new TestSetup {

    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    val message = ByteString(Hex.decode("deadbeaf"))

    val r = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v = ByteString(Hex.decode("1b")).last

    val req = SignRequest(message, address, None)

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).futureValue
    val res = personal.sign(req).futureValue
    res shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))
  }

  it should "return an error if signing a message using a locked account" in new TestSetup {

    val message = ByteString(Hex.decode("deadbeaf"))

    val req = SignRequest(message, address, None)

    val res = personal.sign(req).futureValue
    res shouldEqual Left(AccountLocked)
  }

  it should "return an error when signing a message if passphrase is wrong" in new TestSetup {

    val wrongPassphase = "wrongPassphrase"

    (keyStore.unlockAccount _ ).expects(address, wrongPassphase)
      .returning(Left(DecryptionFailed))

    val message = ByteString(Hex.decode("deadbeaf"))

    val req = SignRequest(message, address, Some(wrongPassphase))

    val res = personal.sign(req).futureValue
    res shouldEqual Left(InvalidPassphrase)
  }

  it should "return an error when signing if unexistent address is sent" in new TestSetup {

    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Left(KeyStore.KeyNotFound))

    val message = ByteString(Hex.decode("deadbeaf"))

    val req = SignRequest(message, address, Some(passphrase))

    val res = personal.sign(req).futureValue
    res shouldEqual Left(KeyNotFound)
  }

  it should "recover address form signed message" in new TestSetup {
    val sigAddress = Address(ByteString(Hex.decode("12c2a3b877289050FBcfADC1D252842CA742BE81")))

    val message = ByteString(Hex.decode("deadbeaf"))

    val r: ByteString = ByteString(Hex.decode("117b8d5b518dc428d97e5e0c6f870ad90e561c97de8fe6cad6382a7e82134e61"))
    val s: ByteString = ByteString(Hex.decode("396d881ef1f8bc606ef94b74b83d76953b61f1bcf55c002ef12dd0348edff24b"))
    val v: Byte = ByteString(Hex.decode("1b")).last

    val req = EcRecoverRequest(message, ECDSASignature(r, s, v))

    val res = personal.ecRecover(req).futureValue
    res shouldEqual Right(EcRecoverResponse(sigAddress))
  }

  it should "allow to sign and recover the same message" in new TestSetup {

    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    val message = ByteString(Hex.decode("deadbeaf"))

    personal.sign(SignRequest(message, address, Some(passphrase)))
      .futureValue.left.map(_ => fail())
      .map(response => EcRecoverRequest(message, response.signature))
      .foreach{ req =>
        val res = personal.ecRecover(req).futureValue
        res shouldEqual Right(EcRecoverResponse(address))
      }

  }

  it should "produce not chain specific transaction before eip155" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    (appStateStorage.getBestBlockNumber _).expects().returning(1234)
    (blockchain.getAccount _).expects(address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (appStateStorage.getBestBlockNumber _).expects().returning(blockchainConfig.eip155BlockNumber - 1)

    val req = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res = personal.sendTransaction(req)

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "produce chain specific transaction after eip155" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase)
      .returning(Right(wallet))

    (appStateStorage.getBestBlockNumber _).expects().returning(1234)
    (blockchain.getAccount _).expects(address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    val forkBlock = new Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    (appStateStorage.getBestBlockNumber _).expects().returning(blockchainConfig.eip155BlockNumber)

    val req = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res = personal.sendTransaction(req)

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(chainSpecificStx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(chainSpecificStx))
  }

  it should "return an error when importing a duplicated key" in new TestSetup {
    (keyStore.importPrivateKey _).expects(prvKey, passphrase).returning(Left(KeyStore.DuplicateKeySaved))

    val req = ImportRawKeyRequest(prvKey, passphrase)
    val res = personal.importRawKey(req).futureValue
    res shouldEqual Left(LogicError("account already exists"))
  }

  it should "unlock an account given a correct passphrase for specified duration" in new TestSetup {
    (keyStore.unlockAccount _ ).expects(address, passphrase).returning(Right(wallet))

    implicit val patienceConfig =
      PatienceConfig(timeout = scaled(Span(3, Seconds)), interval = scaled(Span(100, Millis)))

    val message = ByteString(Hex.decode("deadbeaf"))

    val r = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v = ByteString(Hex.decode("1b")).last

    val reqSign = SignRequest(message, address, None)

    val req = UnlockAccountRequest(address, passphrase, Some(Duration.ofSeconds(2)))
    val res = personal.unlockAccount(req).futureValue
    res shouldEqual Right(UnlockAccountResponse(true))

    val res2 = personal.sign(reqSign).futureValue
    res2 shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))

    eventually {
      personal.sign(reqSign).futureValue shouldEqual Left(AccountLocked)
    }
  }

  it should "delete existing wallet" in new TestSetup {
    (keyStore.deleteWallet _ ).expects(address)
      .returning(Right(true))

    val delRes = personal.deleteWallet(DeleteWalletRequest(address)).futureValue
    delRes shouldEqual Right(DeleteWalletResponse(true))
  }

  it should "return error when deleting not existing wallet" in new TestSetup {
    (keyStore.deleteWallet _ ).expects(address)
      .returning(Left(KeyStore.KeyNotFound))

    val delRes = personal.deleteWallet(DeleteWalletRequest(address)).futureValue
    delRes shouldEqual Left(KeyNotFound)
  }

  it should "handle changing passwords" in new TestSetup {
    type KeyStoreRes = Either[KeyStoreError, Unit]
    type ServiceRes = Either[JsonRpcError, ChangePassphraseResponse]

    val table = Table[KeyStoreRes, ServiceRes](
      ("keyStoreResult", "serviceResult"),
      (Right(()), Right(ChangePassphraseResponse())),
      (Left(KeyStore.KeyNotFound), Left(KeyNotFound)),
      (Left(KeyStore.DecryptionFailed), Left(InvalidPassphrase))
    )

    val request = ChangePassphraseRequest(address, "weakpass", "very5tr0ng&l0ngp4s5phr4s3")

    forAll(table) { (keyStoreResult, serviceResult) =>
      (keyStore.changePassphrase _).expects(address, request.oldPassphrase, request.newPassphrase)
        .returning(keyStoreResult)

      val result = personal.changePassphrase(request).futureValue
      result shouldEqual serviceResult
    }

  }


  trait TestSetup {
    val prvKey = ByteString(Hex.decode("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
    val address = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val passphrase = "aaa"

    val nonce = 7
    val txValue = 128000

    val blockchainConfig = BlockchainConfig(Config.config).copy(
      eip155BlockNumber = 12345,
      chainId = 0x03.toByte
    )

    val wallet = Wallet(address, prvKey)
    val tx = TransactionRequest(from = address, to = Some(Address(42)), value = Some(txValue))
    val stx: SignedTransaction = wallet.signTx(tx.toTransaction(nonce), None)
    val chainSpecificStx: SignedTransaction = wallet.signTx(tx.toTransaction(nonce), Some(blockchainConfig.chainId))

    implicit val system = ActorSystem("personal-service-test")

    val txPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 30
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.normalTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout
    }

    val time = new VirtualTime

    val keyStore = mock[KeyStore]
    val blockchain = mock[BlockchainImpl]
    val txPool = TestProbe()
    val appStateStorage = mock[AppStateStorage]
    val personal = new PersonalService(keyStore, blockchain, txPool.ref, appStateStorage, blockchainConfig, txPoolConfig)

    def array[T](arr: Array[T]): Matcher[Array[T]] =
      argThat((_: Array[T]) sameElements arr)
  }
}
