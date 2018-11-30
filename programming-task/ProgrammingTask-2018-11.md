# High-level description
The idea is to extend a component of our open-source Ethereum Classic client software (called: Mantis). This means that you will need to familiarize yourself with parts of an actual system, identify the relevant parts that need modification and write a test case to verify the contribution works as expected.

# How to obtain the software

We assume a Unix-like working environment, such as Linux and macOS. The below have been tested on Ubuntu 18.04.

In order to build the Mantis client, first install a prerequisite tool `sbt-verify`:

```
$ git clone https://github.com/input-output-hk/sbt-verify
$ cd sbt-verify
$ sbt publishLocal
```

Then proceed to the client. Please clone `https://github.com/input-output-hk/mantis` and checkout branch `phase/iele_testnet`. You should be at commit `3ae8d202cb2011c326dc069114234d9813fbe1a5`:

```
$ git clone --branch phase/iele_testnet --recursive https://github.com/input-output-hk/mantis.git mantis-iele

$ cd mantis-iele
$ git rev-parse HEAD
3ae8d202cb2011c326dc069114234d9813fbe1a5
```

The `--recursive` part is to handle git submodules.

Run `sbt` to create a distribution zip, verifying everything is OK.

```
$ sbt dist
[...]
[info] Your package is ready in .../target/universal/mantis-1.0-daedalus-rc1.zip


$ ls -al target/universal/mantis-1.0-daedalus-rc1.zip
-rw-rw-r-- 1 christos christos 67937114 Nov 30 12:10 target/universal/mantis-1.0-daedalus-rc1.zip
```

You can use your favorite development environment to browse the codebase.

# Running the client

1. Move and unzip the distribution and `cd` into it.
2. Edit `conf/mantis.conf`: find and uncomment `include "private-testnet.conf"`
3. Edit `conf/private-testnet.conf` and add setting: `mantis.consensus.mining-enabled = true`
4. Edit `conf/private-genesis.json` and under `alloc` specify any accounts and their balances that you need. (Note: this file cannot be changed for a started blockchain. If you need to update this configuration you will need to purge the existing blockchain and start fresh - easiest way: `rm -rf ~/.mantis`)
5. Start the client by running:

    ```
    $ ./bin/mantis
    ```

    After a while you should see log messages like:

    ```
    16:29:29 Imported new block 2 (= 0x2 : 0x3aeb68f24825a0ad69ba175b268c919ba4ccea65693027bd3bc24fd6464ce0e9) to the top of chain
    ```

    meaning the blockchain is growing.
6. Verify JSON-RPC is working with:

    ```
    $ curl -X POST http://localhost:8546/ -H 'content-type: application/json' -d '{"jsonrpc":"2.0","method":"eth_protocolVersion","params":[],"id":1}'
    {"jsonrpc":"2.0","result":"0x3f","id":1}
    ```

# The task
You are asked to extend our implementation of [Ethereum Virtual Machine (EVM)](https://github.com/ethereum/wiki/wiki/Ethereum-Virtual-Machine-(EVM)-Awesome-List) with `REVERT` instruction. The specification of this instruction can be found in [EIP-140](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-140.md) or in the [Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf).

The relevant code can be found in the [vm package](https://github.com/input-output-hk/mantis/blob/phase/iele_testnet/src/main/scala/io/iohk/ethereum/vm/OpCode.scala). Please also extend the corresponding unit tests for that package.

Additionally, please write a test (any language) that demonstrates the functionality of the instruction end-to-end, by sending a transaction to a running Mantis node via its [JSON-RPC](https://github.com/ethereum/wiki/wiki/JSON-RPC). The simpler solution the better, but it has to prove that the instruction works as expected. Hints:

* In order to send a transaction you will need an account with funds (Ether).

    You can use this private key:
    `0x3a11880d5c6676ca7c92c81ac0e359c932302868df5ad5363f3bc9b20f170791`
    which corresponds to Ethereum address:
    `0xdf37048d89191d985295c5e495baf17fdd0d6725`
    (or you can generate your own)

    The address should be added to `conf/private-genesis.json` with funds sufficient for running transactions.

* Then you can use `personal_*` RPC methods like `importPrivateKey`, `unlockAccount` and `sendTransaction`. These methods take care of signing and serializing a transaction (which can also be achieved by other means, and then a serialized transaction bytes are submitted to `eth_sendRawTransaction` method).

* You can check an account's balance an state with methods `eth_getBalance` and `eth_getStorageAt`.
