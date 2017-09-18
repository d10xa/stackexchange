package ru.d10xa.stackexchange

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class HttpQueue(
  host: String,
  port: Int,
  bufferSize: Int = 1000
)(implicit system: ActorSystem) {

  import concurrent.duration._

  implicit val mat = ActorMaterializer()

  private final val pool =
    HttpQueue.proxyConfig(ConfigFactory.load().getConfig("proxy")).connectionPool(host, port)

  private final val queue = Source
    .queue[(HttpRequest, Promise[HttpResponse])](bufferSize, OverflowStrategy.dropNew)
    .throttle(10, 1.second, 1, ThrottleMode.shaping)
    .via(pool)
    .map({
      case ((Success(resp), p)) =>
        p.success(resp)
      case ((Failure(e), p)) =>
        p.failure(e)
    })
    .toMat(Sink.ignore)(Keep.left)
    .run

  /**
   * http://doc.akka.io/docs/akka-http/10.0.10/scala/http/client-side/host-level.html#using-the-host-level-api-with-a-queue
   */
  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    import system.dispatcher
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued => responsePromise.future
      case QueueOfferResult.Dropped => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed => Future.failed(
        new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later.")
      )
    }
  }
}

object HttpQueue extends StrictLogging {

  def proxyConfig(config: Config) =
    ProxyConfig(
      useProxy = config.getBoolean("use-proxy"),
      host = config.getString("host"),
      port = config.getInt("port"),
      username = Try(config.getString("username")).toOption,
      password = Try(config.getString("password")).toOption
    )

  case class ProxyConfig(
    useProxy: Boolean,
    host: String,
    port: Int,
    username: Option[String],
    password: Option[String]
  ) {

    def auth(u: String, p: String) = headers.BasicHttpCredentials(u, p)
    def proxyAddress: InetSocketAddress = InetSocketAddress.createUnresolved(host, port)
    def httpsProxyTransport: ClientTransport = (username, password) match {
      case (Some(u), Some(p)) =>
        logger.info(s"Proxy credentials: $u:$p ")
        ClientTransport.httpsProxy(proxyAddress, auth(u, p))
      case _ =>
        ClientTransport.httpsProxy(proxyAddress)
    }
    def connectionPool(host: String, port: Int)(implicit system: ActorSystem, mat: ActorMaterializer) = {
      if (useProxy) {
        logger.info(s"proxy is used: $host:$port")
        val settings = ConnectionPoolSettings(system).withTransport(httpsProxyTransport)
        Http().cachedHostConnectionPool[Promise[HttpResponse]](host, port, settings)
      } else {
        logger.info(s"proxy is not used")
        Http().cachedHostConnectionPool[Promise[HttpResponse]](host, port)
      }
    }
  }
}
