package com.example.http4swebsocket

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    WsServer.stream[IO].compile.drain.as(ExitCode.Success)
}

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.Stream
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import org.http4s.server.websocket.WebSocketBuilder2
import scala.concurrent.duration._

object WsServer {

  def stream[F[_]: Async]: Stream[F, Nothing] = {
    for {
      wsRoutes    <- Stream.eval(WsRoutes[F])
      httpApp      = (ws: WebSocketBuilder2[F]) => wsRoutes.routes(ws).orNotFound
      finalHttpApp = (ws: WebSocketBuilder2[F]) => Logger.httpApp(true, true)(httpApp(ws))
      exitCode    <- Stream.resource(
                       EmberServerBuilder
                         .default[F]
                         .withHost(ipv4"0.0.0.0")
                         .withPort(port"50444")
                         .withHttpWebSocketApp(finalHttpApp)
                         .withShutdownTimeout(1.second)
                         .build >>
                         Resource.eval(Async[F].never)
                     )
    } yield exitCode
  }.drain
}

import cats.effect.Async
import cats.syntax.all._
import fs2.concurrent.Topic
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scala.concurrent.duration._

class WsRoutes[F[_]](topic: Topic[F, String])(implicit F: Async[F]) {

  private val connect: fs2.Pipe[F, WebSocketFrame, WebSocketFrame] =
    in =>
      topic
        .subscribe(1).map(WebSocketFrame.Text(_))
        .concurrently(
          fs2.Stream.awakeDelay(1.seconds).evalTap(_ => topic.publish1("Hello!").void)
        )
        .concurrently(
          in
        )

  def routes(ws: WebSocketBuilder2[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes.of[F] { case GET -> Root => ws.build(connect) }
  }

}

object WsRoutes {

  def apply[F[_]](implicit F: Async[F]): F[WsRoutes[F]] =
    Topic[F, String].map { topic =>
      new WsRoutes(topic)
    }

}
