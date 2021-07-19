/*
 * Copyright 2021 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package node
package server

import org.http4s.server.Server
import cats.effect.std.Dispatcher
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import com.comcast.ip4s.{Host, SocketAddress}
import fs2.internal.jsdeps.node.httpMod
import fs2.internal.jsdeps.node.netMod
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import cats.syntax.all._
import cats.effect.syntax.all._
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Method
import org.http4s.Uri
import org.http4s.Headers
import fs2.internal.jsdeps.node.streamMod
import org.http4s.Header
import scala.scalajs.js.|
import com.comcast.ip4s.Port
import fs2.internal.jsdeps.node.nodeStrings

object NodeServer {

  def apply[F[_]](
      host: Option[Host] = Host.fromString(org.http4s.server.defaults.IPv4Host),
      port: Port = Port.fromInt(org.http4s.server.defaults.HttpPort).get,
      httpApp: HttpApp[F],
      insecureHttpParser: Boolean = false,
      maxHeaderSize: Int = 16384
  )(implicit F: Async[F]): Resource[F, Server] =
    for {
      dispatcher <- Dispatcher[F]
      server <- Resource.make(
        F.delay(
          httpMod.createServer(
            httpMod
              .ServerOptions()
              .setInsecureHTTPParser(insecureHttpParser)
              .setMaxHeaderSize(maxHeaderSize.toDouble),
            mkHandler(httpApp, dispatcher))))(server =>
        F.async_ { cb =>
          server.asInstanceOf[netMod.Server].close { e =>
            cb(e.toLeft(()).leftMap(js.JavaScriptException(_)))
          }
        })
      error <- F.deferred[Throwable].toResource
      _ <- F.delay {
        server
          .asInstanceOf[netMod.Server]
          .on_error(
            nodeStrings.error,
            e => dispatcher.unsafeRunAndForget(error.complete(js.JavaScriptException(e))))
      }.toResource
      _ <- error.get
        .race(F.async_[Unit] { cb =>
          val options = netMod.ListenOptions()
          host.map(_.toString).foreach(options.setHost)
          options.setPort(port.value.toDouble)
          server.asInstanceOf[netMod.Server].listen(options, () => cb(Right(())))
        })
        .toResource
        .rethrow
      ipAddress <- F.delay {
        val info = server.asInstanceOf[netMod.Server].address().asInstanceOf[netMod.AddressInfo]
        (Host.fromString(info.address), Port.fromInt(info.port.toInt)).mapN(SocketAddress(_, _)).get
      }.toResource
    } yield new Server {
      override val address: SocketAddress[Host] = ipAddress
      override def isSecure: Boolean = false
    }

  private def mkHandler[F[_]](app: HttpApp[F], dispatcher: Dispatcher[F])(implicit
      F: Async[F]): js.Function2[httpMod.IncomingMessage, httpMod.ServerResponse, Unit] = {
    (req, res) =>
      val run = for {
        method <- F.fromEither(Method.fromString(req.method.get))
        uri <- F.fromEither(Uri.fromString(req.url.get))
        headers = Headers(req.headers.asInstanceOf[js.Dictionary[String]].toList)
        body = fromReadable(F.pure(req.asInstanceOf[streamMod.Readable]))
        request = Request(method, uri, headers = headers, body = body)
        response <- app.run(request)
        _ <- F.delay[Unit] {
          val headers = response.headers.headers.map { case Header.Raw(name, value) =>
            js.Array(name.toString, value.toString): Double | java.lang.String | js.Array[
              java.lang.String]
          }.toJSArray
          res.writeHead(response.status.code.toDouble, response.status.reason, headers)
        }
        _ <- response.body
          .through(fromWritable(F.pure(res.asInstanceOf[streamMod.Writable])))
          .compile
          .drain
        _ <- F.async_[Unit](cb => res.asInstanceOf[streamMod.Writable].end(() => cb(Right(()))))
      } yield ()

      dispatcher.unsafeRunAndForget(run)
  }

}
