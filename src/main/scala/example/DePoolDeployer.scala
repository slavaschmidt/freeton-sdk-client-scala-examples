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
  * Deploys MultiSig with a single custodian.
  * Then deploys DePool as described [[https://docs.ton.dev/86757ecb2/p/37a848-run-depool here]].
  * Uses testnet giver to initialize all addresses.
  */
object DePoolDeployer extends App {

  val depoolConfiguration = Map[String, Long](
    "minStake"                  -> 100000000L, // minimum stake (in nanotons) that DePool accepts from participants. It's recommended to set it not less than 10 Tons.
    "validatorAssurance"        -> 1000000000000L, // minimal stake for validator (in nanotons). If validator has stake less than validatorAssurance, DePool won't be taking part in elections.
    "participantRewardFraction" -> 90L, // percentage of the total DePool reward (in integers, up to 99 inclusive) that goes to Participants.
    "balanceThreshold"          -> 10000000000L // DePool's own balance (in nanotokens), which it will aim to maintain. It is never staked and is spent on DePool operations only.
  )

  NativeLoader.apply()

  implicit val executionContext: ExecutionContext       = ExecutionContext.Implicits.global
  implicit val ef:               Context.Effect[Future] = futureEffect

  def doIt() = devNet { implicit ctx =>
    for {
      version <- call(Client.Request.Version)
      _ = println(s"Deploying DePool using SDK client version ${version.version}")
      signerKeys <- genKeys(SIGNER)
      devopsKeys <- genKeys(DEVOPS)
      sfmsigKeys <- genKeys(SFMSIG)
      sfmsigAddr <- genAddress(SFMSIG, sfmsigKeys)
      _          <- sendGrams(sfmsigAddr.address)
      depoolKeys <- genKeys(DEPOOL)
      depoolAddr <- genAddress(DEPOOL, depoolKeys)
      _          <- sendGrams(depoolAddr.address)
      helperKeys <- genKeys(HELPER)
      helperAddr <- genAddress(HELPER, helperKeys)
      _          <- sendGrams(helperAddr.address)

      _ = dumpReport(report)

      msigAccount   <- deployMsig(devopsKeys, sfmsigKeys, signerKeys)
      helperAccount <- deployDePoolHelper(helperKeys, depoolAddr.address)
      depoolAccount <- deployDePool(depoolKeys, sfmsigAddr.address)

    } yield (msigAccount, depoolAccount, helperAccount)
  }

  private def deployMsig(keys: KeyPair*)(implicit ctx: Context) = {
    println("Deploying SafeMultisigWallet contract")
    val keysSeq =  keys.map(k => s"0x${k.public}")
    val params = Map("owners" -> keysSeq.asJson, "reqConfirms" -> (keys.length-1).asJson)
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

  private def deploy(name: String, keys: KeyPair, params: Map[String, Json])(implicit ctx: Context) = {
    val signer = Signer.fromKeypair(keys)
    val (abi, tvc) = contracts(name)
    val abiJson       = abiFromBytes(abi)
    val deploySet     = DeploySet(tvcFromBytes(tvc))
    val callSet       = CallSet("constructor", None, Option(params))
    val messageParams = MessageEncodeParams(abiJson, signer, None, Option(deploySet), Option(callSet))
    for {
      (data, messages, errors) <- callS(Processing.Request.ProcessMessageWithEvents(messageParams))
      _ = while (! messages.isClosed) {
        println(messages.collect(10.seconds).mkString("\n"))
        println(errors.collect(10.seconds).mkString("\n"))
      }
    } yield data
  }

  private def genKeys(name: String)(implicit ctx: Context) =
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

  private val proxyContractCode = Source.fromFile(getClass.getClassLoader.getResource("proxyContractCode.base64").getFile).mkString.trim

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

  private def sendGrams(address: String)(implicit ctx: Context): Future[Processing.Result.ResultOfProcessMessage] = {
    val giver   = "0:653b9a6452c7a982c6dc92b2da9eba832ade1c467699ebb3b43dca6d77b780dd"
    val abi     = AbiJson.fromResource("Giver.abi.json").toOption.get
    val callSet = CallSet("grant", input = Option(Map("addr" -> address.asJson)))
    val params  = MessageEncodeParams(abi, Signer.none, Option(giver), None, Option(callSet))
    call(Processing.Request.ProcessMessageWithoutEvents(params))
  }

  private def base64Bytes(b: Array[Byte]) = new String(java.util.Base64.getEncoder.encode(b))

  Await.ready(doIt(), 30.minutes).value.get match {
    case Success(s) => println(s"Done successfully, please inspects the results of the 'out' folder. $s")
    case Failure(er: SdkClientError) => println(s"Failure: ${er.message} - ${er.data.spaces2}")
    case Failure(exception) => println("Failure."); exception.printStackTrace()
  }
}
