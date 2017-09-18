package ru.d10xa.stackexchange

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.ignoreTrailingSlash
import akka.http.scaladsl.server.Directives.parameterMultiMap
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import io.circe.Encoder
import io.circe.Json
import ru.d10xa.stackexchange.StackExchangeClientImpl.Statistics
import ru.d10xa.stackexchange.StackExchangeClientImpl.StatisticsFailure
import ru.d10xa.stackexchange.StackExchangeClientImpl.StatisticsResult

import scala.concurrent.Future

class StackExchangeAggregationServer(
  host: String,
  port: Int,
  client: StackExchangeClient
)(implicit system: ActorSystem, materializer: ActorMaterializer) {

  def searchPath = ignoreTrailingSlash & get & path("search")
  def extractTags = parameterMultiMap.map(_.getOrElse("tag", List.empty[String]))

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  implicit val encodeFoo: Encoder[Statistics] = {
    case StatisticsResult(total, answered) =>
      Json.obj("total" -> Json.fromInt(total), "answered" -> Json.fromInt(answered))
    case StatisticsFailure(failureMessage) =>
      Json.obj("failure" -> Json.fromString(failureMessage))
  }

  val route: Route = (searchPath & extractTags) {
    case Nil => complete("empty")
    case tags => complete(client.query(tags: _*))
  }

  def run(): Future[Http.ServerBinding] = Http().bindAndHandle(route, host, port)

}
