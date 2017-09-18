package ru.d10xa.stackexchange

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Random
import scala.util.Success

object StackExchangeStubServer extends StrictLogging {

  case class Response(items: Seq[Item])
  case class Item(is_answered: Boolean)

  def bind(bindHost: String, bindPort: Int)(implicit system: ActorSystem): Future[Http.ServerBinding] = {
    import akka.http.scaladsl.server.Directives._
    implicit val materializer = ActorMaterializer()
    import io.circe.generic.auto._
    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    def items = (1 to Random.nextInt(100)) map (_ => Item(Random.nextBoolean()))
    val route: Route = encodeResponseWith(Gzip)(complete(Response(items)))
    Http().bindAndHandle(route, bindHost, bindPort)
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("stub-server")
    import system.dispatcher
    val cfg = ConfigFactory.load().getConfig("stub-server")
    val host = cfg.getString("host")
    val port = cfg.getInt("port")
    bind(host, port)
      .onComplete {
        case Success(binding) =>
          logger.info(s"stackexchange stub server run at $host:$port")
        case Failure(e) =>
          e.printStackTrace()
          system.terminate()
      }
  }

}
