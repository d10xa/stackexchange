package ru.d10xa.stackexchange

import akka.actor.ActorSystem
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import io.circe.Json
import io.circe.ParsingFailure
import io.circe.parser.parse
import ru.d10xa.stackexchange.StackExchangeClientImpl.Statistics

import scala.concurrent.Future

trait StackExchangeClient {
  def query(tags: String*): Future[Map[String, Statistics]]
}

class StackExchangeClientImpl(
  host: String = "api.stackexchange.com",
  port: Int = 80,
  bufferSize: Int = 1000
)(implicit system: ActorSystem) extends StackExchangeClient {
  import StackExchangeClientImpl._
  import system.dispatcher

  implicit val mat = ActorMaterializer()

  private val httpQueue = new HttpQueue(
    host = host,
    port = port,
    bufferSize = bufferSize
  )

  override def query(tags: String*): Future[Map[String, Statistics]] =
    queueRequests(tags.map(tag => tag -> mkRequest(tag))).map(_.toMap)

  def queueRequests(requests: Seq[(String, HttpRequest)]): Future[Seq[(String, Statistics)]] = {
    def queueAndRecover(tag: String, request: HttpRequest): Future[(String, Statistics)] =
      httpQueue.queueRequest(request)
        .flatMap(extractStatistics)
        .recover { case e => StatisticsFailure(e.getMessage) }
        .map(s => tag -> s)
    val futures: Seq[Future[(String, Statistics)]] =
      requests map (queueAndRecover _).tupled
    Future.sequence(futures)
  }

  def mkUri(tag: String): Uri = Uri("/2.2/search")
    .withQuery(Query(
      "pagesize" -> "100",
      "order" -> "desc",
      "sort" -> "creation",
      "tagged" -> tag,
      "site" -> "stackoverflow",
      "filter" -> "!Tht3Tcqv9"
    ))

  def mkRequest(tag: String) = HttpRequest(uri = mkUri(tag))

  def parseAndAggregate(json: String): Statistics = {
    def toBooleans(json: Json): Seq[Boolean] =
      (json \\ "items")
        .map(_ \\ "is_answered")
        .flatMap(_.flatMap(_.asBoolean))

    def toResult(booleans: Seq[Boolean]): (Int, Int) =
      (booleans.size, booleans.count(_ == true))

    val either: Either[ParsingFailure, StatisticsResult] = parse(json)
      .map(toBooleans)
      .map(toResult)
      .map { case (total, answered) => StatisticsResult(total, answered) }

    either match {
      case Left(parsingFailure) => StatisticsFailure(parsingFailure.message)
      case Right(statisticsResult) => statisticsResult
    }
  }

  def extractStatistics(response: HttpResponse): Future[Statistics] =
    response
      .entity
      .dataBytes
      .via(Gzip.decoderFlow)
      .runWith(Sink.head)
      .map(re => re.utf8String)
      .map(parseAndAggregate)
      .recover { case e => StatisticsFailure(e.getMessage) }

}

object StackExchangeClientImpl {

  sealed trait Statistics

  final case class StatisticsResult(
    total: Int,
    answered: Int
  ) extends Statistics

  final case class StatisticsFailure(
    failure: String
  ) extends Statistics
}
