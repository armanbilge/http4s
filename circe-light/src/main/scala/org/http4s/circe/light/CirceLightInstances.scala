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
package circe
package light

import cats.data.EitherT
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.Decoder
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.base.CirceBaseInstances

trait CirceLightInstances extends CirceBaseInstances {
  
  implicit def jsonDecoder[F[_]: Concurrent]: EntityDecoder[F, Json] =
    EntityDecoder.decodeBy(MediaType.application.json) { msg =>
      EitherT(msg.bodyText.compile.foldMonoid.map(parse)).leftMap { ex =>
        MalformedMessageBodyFailure("Invalid JSON", ex.some)
      }
    }
    
  def jsonOf[F[_]: Concurrent, A](implicit decoder: Decoder[A]): EntityDecoder[F, A] =
    jsonDecoder.flatMapR { json =>
      decoder
        .decodeJson(json)
        .fold(
          failure => DecodeResult.failureT(jsonDecodeError(json, NonEmptyList.one(failure))),
          DecodeResult.successT(_)
        )
    }

}

