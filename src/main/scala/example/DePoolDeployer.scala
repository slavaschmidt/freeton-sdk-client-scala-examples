package example

import java.io.{File, FileOutputStream, PrintWriter}
import java.net.URL
import java.nio.file.Files

import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import ton.sdk.client.binding.Api.SdkClientError
import ton.sdk.client.binding.Context._
import ton.sdk.client.binding._
import ton.sdk.client.jni.NativeLoader
import ton.sdk.client.modules.Abi._
import ton.sdk.client.modules.Client._
import ton.sdk.client.modules.Crypto._
import ton.sdk.client.modules.Processing._
import ton.sdk.client.modules.{Abi, Client, Crypto, Processing}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}

/**
  * Deploys MultiSig with a three custodians and two confirmations.*
  * Then deploys DePool as described [[https://docs.ton.dev/86757ecb2/p/37a848-run-depool here]].
  * Also deplos DePoolHelper, just for the case.
  * Downloads all of the needed contract resources as needed.
  * Uses Devnet giver to initialize all addresses.
  */
object DePoolDeployer extends App {

  val depoolConfiguration = Map[String, Long](
    "minStake"                  -> 10000000000L, // minimum stake (in nanotons) that DePool accepts from participants. It's recommended to set it not less than 10 Tons.
    "validatorAssurance"        -> 100000000000000L, // minimal stake for validator (in nanotons). If validator has stake less than validatorAssurance, DePool won't be taking part in elections.
    "participantRewardFraction" -> 90L, // percentage of the total DePool reward (in integers, up to 99 inclusive) that goes to Participants.
    "balanceThreshold"          -> 10000000000L // DePool's own balance (in nanotokens), which it will aim to maintain. It is never staked and is spent on DePool operations only.
  )

  NativeLoader.apply()

  implicit val executionContext: ExecutionContext       = ExecutionContext.Implicits.global
  implicit val ef:               Context.Effect[Future] = futureEffect

  def doIt() = devNet { implicit ctx =>
    for {
      (signerKeys, devopsKeys, sfmsigKeys) <- local { implicit ctx =>
        for {
          version <- call(Client.Request.Version)
          _ = println(s"Deploying DePool using SDK client version ${version.version}")

          signerKeys <- genMnemonicAndKeys(SIGNER)
          devopsKeys <- genMnemonicAndKeys(DEVOPS)
          sfmsigKeys <- genMnemonicAndKeys(SFMSIG)
        } yield (signerKeys, devopsKeys, sfmsigKeys)
      }

      sfmsigAddr <- genAddress(SFMSIG, sfmsigKeys)
      _          <- sendGrams(sfmsigAddr.address)
      depoolKeys <- genMnemonicAndKeys(DEPOOL)
      depoolAddr <- genAddress(DEPOOL, depoolKeys)
      _          <- sendGrams(depoolAddr.address)
      helperKeys <- genMnemonicAndKeys(HELPER)
      helperAddr <- genAddress(HELPER, helperKeys)
      _          <- sendGrams(helperAddr.address)

      _ = dumpReport(report)

      msigAccount   <- deployMsig(sfmsigKeys, devopsKeys, signerKeys)
      helperAccount <- deployDePoolHelper(helperKeys, depoolAddr.address)
      depoolAccount <- deployDePool(depoolKeys, sfmsigAddr.address)

    } yield (msigAccount, depoolAccount, helperAccount)
  }

  private def deployMsig(keys: KeyPair*)(implicit ctx: Context) = {
    println("Deploying SafeMultisigWallet contract")
    val keysSeq = keys.map(k => s"0x${k.public}")
    val params  = Map("owners" -> keysSeq.asJson, "reqConfirms" -> (keys.length - 1).asJson)
    deploy(SFMSIG, keys.head, params)
  }

  private def deployDePool(keys: KeyPair, msigAddress: String)(implicit ctx: Context) = {
    println("Deploying DePool contract")
    val parameters = Map("proxyCode" -> proxyContractCode, "validatorWallet" -> msigAddress)
    val params     = depoolConfiguration.mapValues(_.asJson) ++ parameters.mapValues(_.asJson)
    deploy(DEPOOL, keys, params)
  }

  private def deployDePoolHelper(keys: KeyPair, depoolAddress: String)(implicit ctx: Context) = {
    println("Deploying DePoolHelper contract")
    val params = Map("pool" -> depoolAddress.asJson)
    deploy(HELPER, keys, params)
  }

  // Generates name contract taking its contents from `contracts` map using provided keypair for signing
  private def deploy(name: String, keys: KeyPair, params: Map[String, Json])(implicit ctx: Context) = {
    val signer = Signer.fromKeypair(keys)
    val (abi, tvc) = contracts(name)
    val abiJson       = abiFromBytes(abi)
    val deploySet     = DeploySet(tvcFromBytes(tvc))
    val callSet       = CallSet("constructor", None, Option(params))
    val messageParams = MessageEncodeParams(abiJson, signer, None, Option(deploySet), Option(callSet))
    call(Processing.Request.ProcessMessageWithoutEvents(messageParams))
  }

  private def genMnemonicAndKeys(name: String)(implicit ctx: Context) =
    for {
      seed <- call(Crypto.Request.MnemonicFromRandom())
      keys <- call(Crypto.Request.MnemonicDeriveSignKeys(seed.phrase))
      _ = writeToFile(keys.asJson.spaces2, s"$name.keys.json")
      _ = report.append(s"$name:\t'${seed.phrase}'\n")
    } yield keys

  private def genAddress(name: String, keys: KeyPair)(implicit ctx: Context) = {
    val signer = Signer.fromKeypair(keys)
    val (abi, tvc) = contracts(name)
    val deploySet = DeploySet(tvcFromBytes(tvc))
    for {
      encoded <- call(Abi.Request.EncodeMessage(abiFromBytes(abi), None, Option(deploySet), None, signer))
      _ = writeToFile(encoded.address, s"$name.addr")
    } yield encoded
  }

  private val SFMSIG = "sfmsig"
  private val DEPOOL = "depool"
  private val DEVOPS = "devops"
  private val SIGNER = "signer"
  private val HELPER = "helper"

  private val outPath = {
    val path = "out"
    val full = new File(path)
    full.mkdirs()
    full.getAbsolutePath + File.separator
  }

  private val contractPath = {
    val f = new File(outPath + "contracts")
    f.mkdir()
    f.getAbsolutePath + File.separator
  }

  private val dePoolAbiUrl       = "https://raw.githubusercontent.com/tonlabs/ton-labs-contracts/master/solidity/depool/DePool.abi.json"
  private val dePoolTvcUrl       = "https://github.com/tonlabs/ton-labs-contracts/raw/master/solidity/depool/DePool.tvc"
  private val dePoolHelperAbiUrl = "https://raw.githubusercontent.com/tonlabs/ton-labs-contracts/master/solidity/depool/DePoolHelper.abi.json"
  private val dePoolHelperTvcUrl = "https://github.com/tonlabs/ton-labs-contracts/raw/master/solidity/depool/DePoolHelper.tvc"
  private val multiSigAbiUrl     = "https://raw.githubusercontent.com/tonlabs/ton-labs-contracts/master/solidity/safemultisig/SafeMultisigWallet.abi.json"
  private val multiSigTvcUrl     = "https://github.com/tonlabs/ton-labs-contracts/raw/master/solidity/safemultisig/SafeMultisigWallet.tvc"

  private val contracts = Map(
    SFMSIG -> (fromCacheOrUrl(multiSigAbiUrl), fromCacheOrUrl(multiSigTvcUrl)),
    DEPOOL -> (fromCacheOrUrl(dePoolAbiUrl), fromCacheOrUrl(dePoolTvcUrl)),
    HELPER -> (fromCacheOrUrl(dePoolHelperAbiUrl), fromCacheOrUrl(dePoolHelperTvcUrl))
  )

  private val proxyContractCode =
    "te6ccgECHgEABVUAAib/APSkICLAAZL0oOGK7VNYMPShBwEBCvSkIPShAgIDzkAEAwAp32omhp/+mf6YB8NT/8MPwzfDH8MUAgFYBgUALV+ELIy//4Q88LP/hGzwsA+EoBzsntVIAMNfhBbpLwCN5opvxgINMf0z8zIYIQ/////rqOQXBopvtglWim/mAx34IQBV1KgKG1f/hKyM+FiM4B+gKNBEAAAAAAAAAAAAAAAAABD2mXdM8WIc8LP/hJzxbJcfsA3l8D8AeAIBIBEIAgFuEAkCASAPCgIBIA4LAYj6f40IYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABPhpIe1E0CDXScIBjhHT/9M/0wD4an/4Yfhm+GP4YgwB/o4+9AWNCGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAT4anABgED0DvK91wv/+GJw+GNw+GZ/+GHi0wABn4ECANcYIPkBWPhC+RDyqN7TPwGOHvhDIbkgnzAg+COBA+iogggbd0Cgud6S+GPggDTyNNjTHyHBAyINADKCEP////28sZVx8AHwBeAB8AH4R26S8AXeAKu0t7mSfCC3SXgEb2mf6Lg0U32wSrRTfzAY78EIAq6lQFDav/wlZGfCxGcA/QFGgiAAAAAAAAAAAAAAAAAB8+0HGmeLEOeFn/wk54tkuP2AGHgDv/wzwAC3tt0SEz4QW6S8Aje0z/TH9FwaKb7YJVopv5gMd+CEAVdSoChtX/4SsjPhYjOAfoCjQRAAAAAAAAAAAAAAAAAATPrwXTPFiLPCz8hzwsf+EnPFslx+wBb8Ad/+GeAAt7nN6KmfCC3SXgEb2mf6Y/ouDRTfbBKtFN/MBjvwQgCrqVAUNq//CVkZ8LEZwD9AUaCIAAAAAAAAAAAAAAAAAE7ZAsmZ4sRZ4WfkOeFj/wk54tkuP2ALfgDv/wzwAgEgFxICASAVEwHpuotV8/+EFujlztRNAg10nCAY4R0//TP9MA+Gp/+GH4Zvhj+GKOPvQFjQhgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE+GpwAYBA9A7yvdcL//hicPhjcPhmf/hh4t74RvJzcfhm0XBwkyDBAoFABajh3IIPhJzxYizwsHMSDJ+QBTM5Uw+EIhut80W6S1B+gw8uBm+En4avAHf/hnAQm7Wws2WBYA+vhBbpLwCN7TP/pA0fhJ+ErHBfLgZnBopvtglWim/mAx34IQBV1KgKG1f/gnbxAhghA7msoAoL7y4GdwaKb7YJVopv5gMd+CEAVdSoChtX8iyM+FiM4B+gKNBEAAAAAAAAAAAAAAAAACOyuhJM8WI88LP8lx+wBfA/AHf/hnAgEgGRgAn7rbxCnvhBbpLwCN7R+EqCEDuaygAiwP+OLSTQ0wH6QDAxyM+HIM6NBAAAAAAAAAAAAAAAAArbxCnozxYizxYhzws/yXH7AN5bkvAH3n/4Z4AgEgHRoBCbhxdZGQGwH8+EFukvAI3tM/0//TH9Mf1w3/ldTR0NP/3yDXS8ABAcAAsJPU0dDe1PpBldTR0PpA39H4SfhKxwXy4GZwaKb7YJVopv5gMd+CEAVdSoChtX/4J28QIYIQO5rKAKC+8uBncGim+2CVaKb+YDHfghAFXUqAobV/IsjPhYjOAfoCHABggGrPQM+DyM+ROc3RLinPCz8ozwv/J88LHybPCx8lzwv/JM8Uzclx+wBfCPAHf/hnAHTccCLQ0wP6QDD4aak4ANwhxwAglzAh0x8hwADf3CHBAyKCEP////28sZVx8AHwBeAB8AH4R26S8AXe"
  private val giverAbiCode = Source.fromResource("Giver.abi.json").mkString.trim

  // ------===== misc helper methods =====------ //

  private val report = StringBuilder.newBuilder

  private def writeToFile(str: String, name: String) = new PrintWriter(outPath + name) {
    write(str)
    close()
  }

  private def abiFromBytes(buf: Array[Byte]): AbiJson =
    AbiJson
      .fromString(new String(buf))
      .fold(
        cause => throw new IllegalArgumentException("Could not parse abi json", cause),
        identity
      )

  private def tvcFromBytes(b: Array[Byte]) = this.base64Bytes(b)

  private def dumpReport(report: StringBuilder) = writeToFile(report.result(), "report.txt")

  // reads contract contents from the cache or if not yet cached downloads and caches it
  private def fromCacheOrUrl(address: String): Array[Byte] = {
    val url      = new URL(address)
    val fileName = url.getFile.split("/").last
    val file     = new File(contractPath + fileName)
    if (file.exists() && file.canRead) {
      Files.readAllBytes(file.toPath)
    } else {
      val is     = url.openStream()
      val result = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
      is.close()
      val out = new FileOutputStream(file)
      out.write(result)
      out.close()
      result
    }
  }

  // sends 100 grams to given address using giver contract
  private def sendGrams(address: String)(implicit ctx: Context) = {
    val giver   = "0:653b9a6452c7a982c6dc92b2da9eba832ade1c467699ebb3b43dca6d77b780dd"
    val abi     = AbiJson.fromString(giverAbiCode).toOption.get
    val callSet = CallSet("grant", input = Option(Map("addr" -> address.asJson)))
    val params  = MessageEncodeParams(abi, Signer.none, Option(giver), None, Option(callSet))
    call(Processing.Request.ProcessMessageWithoutEvents(params))
  }

  private def base64Bytes(b: Array[Byte]) = new String(java.util.Base64.getEncoder.encode(b))

  // actually execute the main method
  private lazy val result = doIt()

  // output the result as soon as it completes
  result.onComplete {
    case Success(s) => println(s"Done successfully, please inspect the results of the 'out' folder. $s")
    case Failure(er: SdkClientError) => println(s"Failure: ${er.message} - ${er.data.spaces2}")
    case Failure(exception) => println("Failure."); exception.printStackTrace()
  }

  // wait for the async result
  Await.result(result, 10.minutes)
}
