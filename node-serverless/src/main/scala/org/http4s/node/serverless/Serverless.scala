package org.http4s
package node.serverless

import scala.scalajs.js
import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher

object Serverless {
  
  type Request = js.Any with js.Dynamic
  type Response = js.Any with js.Dynamic

  def apply[F[_]](httpApp: HttpApp[F])(implicit F: Async[F]): Resource[F, js.Function2[Request, Response, Unit]] =
    Dispatcher[F].flatMap { dispatcher =>
      { (req: Request, res: Response) =>
        ()
      }
    }

}
