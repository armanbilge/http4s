/*
 * Copyright 2019 http4s.org
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
package ember
package server

import cats.effect.IO
import org.http4s.client.testroutes.GetRoutes

class ForeverSuite extends Http4sSuite {

  test("forever") {
    EmberServerBuilder.default[IO].withHttpApp(httpApp = HttpApp[IO] { request =>
    val get = Some(request).filter(_.method == Method.GET).flatMap { r =>
      GetRoutes.getPaths.get(r.uri.path.toString)
    }

    val post = Some(request).filter(_.method == Method.POST).map { r =>
      IO(Response(body = r.body))
    }

    get.orElse(post).getOrElse(IO(Response[IO](status = Status.NotFound)))
  }).build.useForever
  }
  
}
