package ru.d10xa.stackexchange

import akka.actor.ActorSystem

import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import scala.util.Failure
import scala.util.Success

object Main extends StrictLogging {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("main")
    implicit val materializer = ActorMaterializer()

    val cfg = ConfigFactory.load()
    val cfgServer = cfg.getConfig("server")
    val cfgStackExchange = cfg.getConfig("stackexchange")

    val client: StackExchangeClient = new StackExchangeClientImpl(
      host = cfgStackExchange.getString("host"),
      port = cfgStackExchange.getInt("port")
    )

    val host = cfgServer.getString("host")
    val port = cfgServer.getInt("port")

    val server = new StackExchangeAggregationServer(host, port, client)

    import system.dispatcher
    server.run().onComplete {
      case Success(binding) =>
        logger.info(s"stackexchange aggregation server run at $host:$port")
      case Failure(e) =>
        e.printStackTrace()
        system.terminate()
    }
  }

}
