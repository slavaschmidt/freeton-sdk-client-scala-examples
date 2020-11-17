# Examples for the TON SDK client Scala binding

This repository is a collection of standalone examples using [scala binding](https://github.com/slavaschmidt/ton-sdk-client-scala-binding)
for [Freeton SDK client](https://github.com/tonlabs/TON-SDK).
It aims to provide insight into intended usage scenarios for the binding.

## Prerequisites

This project has the same prerequisites as the binding project. Please consult this [prerequisites description](https://github.com/slavaschmidt/ton-sdk-client-scala-binding#prerequisites)
for detailed information.

## Running examples

Check prerequisites. Clone the repository. Navigate into the project folder with some terminal. Execute `sbt run` command.

## General aspects

Common for all examples, this repository demonstrates following aspects of using the binding library:

### build.sbt

- the [build.sbt](build.sbt) contains the definitions of the appropriate environment variables for windows and linux 
needed for the OS do load the native libraries:
```scala
envVars in run := Map(
  "LD_LIBRARY_PATH" -> (baseDirectory.value / "lib").getPath,
  "PATH"            -> (baseDirectory.value / "lib").getPath
)
```
- the main process must be forked in order for environment variables to take effect:
```scala
fork in run := true
```
- forking is also necessary for running examples multiple times in sbt [interactive mode](https://www.scala-sbt.org/1.x/docs/Howto-Interactive-Mode.html) 

- following demonstrates how the target for native libraries can be defined:
```scala
javaOptions in run += s"-Djava.io.freetontmpdir=${(baseDirectory.value / "lib").getPath}"
```

- the binding library users [logback](http://logback.qos.ch/) for logging but does not include any logging backends. This project includes `logback-classic` as logging backend:
```scala
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
``` 

### logback.xml

The [logback.xml](src/main/resources/logback.xml) overrides default log4j configuration to reveal internal communication flow between the binding code and the ton client.
Changing the `"TRACE"` logging level to `"DEBUG"`, `"INFO"`, `"WARN"` or `"ERROR"` makes the binding successively less noisy.

  
## Examples

### Depool deployer

The [DePoolDeployer](src/main/scala/example/DePoolDeployer.scala) demonstrates following aspects of library usage:

- TON mnemonic generation and keypair derivation from it
- contract deployment
- contract call
- message decoding

The DePoolDeployer fully automates depool contract deployment from zero state by going over the following steps:

- Downloading and caching the contract code for multisig wallet, depool and depoolhelper
- Generating seed phrases for three custodians of the multisig wallet, depool and depoolhelper
- Generating five key pairs from the seed phrases
- Generating addresses for the three contracts to be deployed
- Initializing generated addresses with 100 grams by calling [giver contract](src/main/resources/Giver.abi.json)
- Deploying multisig wallet with three custodians and two required signatures
- Deploying depool helper contract
- Deploying depool contract with the configuration specified at the top of the [DePoolDeployer](src/main/scala/example/DePoolDeployer.scala)
source code

From the technical point of view, following aspects are illustrated:

- initial loading of native libraries with `NativeLoader.apply()`
- usage of `futureEffect` and `executionContext` for asyncronous processing
- nested contexts with shadowing
- for-comprehension for nice interaction with `Future`s
- waiting for the resulting effect to complete at the bottom of the example
- it looks like the use of the string function id [depool contract](out/contracts/DePool.abi.json) itself is not fully compatible with the client v1.0.0.
This example uses version customized by replacing function id in string hex form `"0x4E73744B"` with its decimal numeric equivalent `1316189259`. 

Please consult the source code of the [DePoolDeployer](src/main/scala/example/DePoolDeployer.scala) for further details.

