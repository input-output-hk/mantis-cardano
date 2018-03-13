package io.iohk.ethereum.ledger

import io.iohk.ethereum.utils.{BlockchainConfig, Config}
import io.iohk.ethereum.Mocks.MockVM
import io.iohk.ethereum.blockchain.sync.EphemBlockchainTestSetup
import io.iohk.ethereum.domain.{Account, Address, BlockchainImpl, UInt256}
import io.iohk.ethereum.utils.Config.SyncConfig
import io.iohk.ethereum.vm.VM
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

class DeleteAccountsSpec extends FlatSpec with Matchers with MockFactory {

  val blockchainConfig = BlockchainConfig(Config.config)
  val syncConfig = SyncConfig(Config.config)

  val blockchain = mock[BlockchainImpl]

  it should "delete no accounts when none of them should be deleted" in new TestSetup {
    val newWorld = InMemoryWorldStateProxy.persistState(ledger.deleteAccounts(Set.empty)(worldState))
    accountAddresses.foreach{ a => assert(newWorld.getAccount(a).isDefined) }
    newWorld.stateRootHash shouldBe worldState.stateRootHash
  }

  it should "delete the accounts listed for deletion" in new TestSetup {
    val newWorld = ledger.deleteAccounts(accountAddresses.tail)(worldState)
    accountAddresses.tail.foreach{ a => assert(newWorld.getAccount(a).isEmpty) }
    assert(newWorld.getAccount(accountAddresses.head).isDefined)
  }

  it should "delete all the accounts if they are all listed for deletion" in new TestSetup {
    val newWorld = InMemoryWorldStateProxy.persistState(ledger.deleteAccounts(accountAddresses)(worldState))
    accountAddresses.foreach{ a => assert(newWorld.getAccount(a).isEmpty) }
    newWorld.stateRootHash shouldBe Account.EmptyStorageRootHash
  }

  // scalastyle:off magic.number
  it should "delete account that had storage updated before" in new TestSetup {
    val worldStateWithStorage = worldState.saveStorage(
      validAccountAddress,
      worldState.getStorage(validAccountAddress).store(1, 123))

    val updatedWorldState = ledger.deleteAccounts(accountAddresses)(worldStateWithStorage)

    val newWorld = InMemoryWorldStateProxy.persistState(updatedWorldState)
    newWorld.getAccount(validAccountAddress) shouldBe 'empty
  }

  // scalastyle:off magic.number
  trait TestSetup extends EphemBlockchainTestSetup {
    //+ cake overrides
    override lazy val vm: VM = new MockVM()

    override lazy val ledger: LedgerImpl = newLedger()
    //- cake overrides

    val validAccountAddress = Address(0xababab)
    val validAccountAddress2 = Address(0xcdcdcd)
    val validAccountAddress3 = Address(0xefefef)

    val accountAddresses = Set(validAccountAddress, validAccountAddress2, validAccountAddress3)

    val worldStateWithoutPersist: InMemoryWorldStateProxy =
      BlockchainImpl(storagesInstance.storages).getWorldStateProxy(-1, UInt256.Zero, None)
        .saveAccount(validAccountAddress, Account(balance = 10))
        .saveAccount(validAccountAddress2, Account(balance = 20))
        .saveAccount(validAccountAddress3, Account(balance = 30))
    val worldState = InMemoryWorldStateProxy.persistState(worldStateWithoutPersist)
  }

}
