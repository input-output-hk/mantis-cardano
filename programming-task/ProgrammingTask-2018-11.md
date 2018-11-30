# High-level description
The idea is to extend a component of our open-source Ethereum Classic client software (called: Mantis). This means that you will need to familiarize yourself with parts of an actual system, identify the relevant parts that need modification and write a test case to verify the contribution works as expected.

# How to obtain the software

We assume a Unix-like working environment, such as Linux and macOS. The below have been tested on Ubuntu 18.04.

Please clone `https://github.com/input-output-hk/mantis` and checkout branch `phase/iele_testnet`. You should be at commit `3ae8d202cb2011c326dc069114234d9813fbe1a5`:


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

# The task
You will need to extend the [Ethereum Virtual Machine (EVM)](https://github.com/ethereum/wiki/wiki/Ethereum-Virtual-Machine-(EVM)-Awesome-List) with one new bytecode and write a test case that uses the bytecode end-to-end, calling Mantis externally via its [JSON-RPC Endpoint](https://github.com/ethereum/wiki/wiki/JSON-RPC).