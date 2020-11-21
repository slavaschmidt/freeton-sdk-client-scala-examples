package example

import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import ton.sdk.client.binding.Api.SdkClientError
import ton.sdk.client.binding.Context._
import ton.sdk.client.binding._
import ton.sdk.client.jni.NativeLoader
import ton.sdk.client.modules._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  */
object Elector extends App {

  NativeLoader.apply()

  final case class CodeAndData(code:   String, data: String)
  final case class ParticipantsWrapper(output: Seq[Json])

  implicit val executionContext: ExecutionContext       = ExecutionContext.Implicits.global
  implicit val ef:               Context.Effect[Future] = futureEffect

  private val accFilter = Map("id" -> Map("eq" -> "-1:3333333333333333333333333333333333333333333333333333333333333333")).asJson

  def readStakesFromElector() = mainNet { implicit ctx =>
    for {
      account <- call(Net.Request.QueryCollection("accounts", filter = Option(accFilter), result = "code data", limit = Option(1)))
      electorData = account.result.head.as[CodeAndData].toOption.get
      stateInit   = Abi.StateInitSource.fromStateInit(electorData.code, electorData.data, None)
      elector <- call(Abi.Request.EncodeAccount(stateInit, None, None, None))
      json    <- call(Tvm.Request.RunGet(elector.account, "participant_list"))
      participants = json.as[ParticipantsWrapper].toOption.get
      stakes       = foldStakes(participants.output.head).toList.sorted.reverse
      _ = println(stakes.mkString("\n"))
    } yield stakes
  }

  // actually execute the main method
  private val result: Future[List[BigDecimal]] = readStakesFromElector()

  // output the result as soon as it completes
  result.onComplete {
    case Success(s) => println(s"Done successfully: $s")
    case Failure(er: SdkClientError) => println(s"Failure: ${er.message} - ${er.data.spaces2}")
    case Failure(exception) => println("Failure."); exception.printStackTrace()
  }

  // wait for the async result
  Await.result(result, 10.minutes)

  private def foldStakes(in: Json): Stream[BigDecimal] = {
    in.as[Seq[Json]].toOption match {
      case Some(arr) if arr.nonEmpty =>
        val stakeStr: String = arr.head.asArray.get.last.as[String].toOption.get
        (BigDecimal(stakeStr)  / 1000000000l) #:: foldStakes(arr.last)
      case Some(_) => Stream.empty[BigDecimal]
      case None if in.isNull => Stream.empty[BigDecimal]
      case None =>
        throw new IllegalStateException(s"Unexpected value while parsing participants list: $in")
    }
  }

}
