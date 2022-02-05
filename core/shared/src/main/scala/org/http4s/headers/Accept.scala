/*
 * Copyright 2013 http4s.org
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
package headers

import cats.data.NonEmptyList
import cats.parse.Parser
import org.http4s.internal.parsing.Rfc7230.headerRep1
import org.http4s.util.Renderable
import org.http4s.util.Writer
import org.typelevel.ci._

object Accept {
  def apply(head: MediaRangeAndQValue, tail: MediaRangeAndQValue*): Accept =
    apply(NonEmptyList(head, tail.toList))

  def parse(s: String)(implicit mimeDB: MimeDB): ParseResult[Accept] =
    parseImpl(s, MimeDB.default)

  private[http4s] def parse(s: String): ParseResult[Accept] =
    parseImpl(s, MimeDB.default)

  private def parseImpl(s: String, mimeDB: MimeDB): ParseResult[Accept] =
    ParseResult.fromParser(parser(mimeDB), "Invalid Accept header")(s)

  private[http4s] def parser(implicit mimeDB: MimeDB): Parser[Accept] = {
    val acceptParams =
      (QValue.parser ~ MediaRange.mediaTypeExtensionParser.rep0).map { case (qValue, ext) =>
        (
          qValue,
          ext,
        )
      }

    val qAndExtension =
      acceptParams.orElse(MediaRange.mediaTypeExtensionParser.rep.map { s =>
        (org.http4s.QValue.One, s.toList)
      })

    val fullRange: Parser[MediaRangeAndQValue] = (MediaRange.parser ~ qAndExtension.?).map {
      case (mr, params) =>
        val (qValue, extensions) = params.getOrElse((QValue.One, Seq.empty))
        mr.withExtensions(extensions.toMap).withQValueImpl(qValue, mimeDB)
    }

    headerRep1(fullRange).map(xs => Accept(xs.head, xs.tail: _*))
  }

  private[http4s] def headerInstance: Header[Accept, Header.Recurring] =
    headerInstance(MimeDB.default)

  implicit def headerInstance(implicit mimeDB: MimeDB): Header[Accept, Header.Recurring] = {
    implicit val renderer = MediaType.http4sHttpCodecForMediaType(mimeDB)
    Header.createRendered(
      ci"Accept",
      _.values,
      parseImpl(_, mimeDB),
    )
  }

  implicit val headerSemigroupInstance: cats.Semigroup[Accept] =
    (a, b) => Accept(a.values.concatNel(b.values))
}

final class MediaRangeAndQValue private[http4s] (mediaRange: MediaRange, qValue: QValue = QValue.One, mimeDB: MimeDB = MimeDB.default)
    extends Renderable {
  private[http4s] def this(mediaRange: MediaRange, qValue: QValue) =
    this(mediaRange, qValue, MimeDB.default)

  def render(writer: Writer): writer.type = {
    implicit val renderer = MediaRange.http4sHttpCodecForMediaRange(mimeDB)
    writer << mediaRange.withExtensions(Map.empty) << qValue
    MediaRange.renderExtensions(writer, mediaRange)
    writer
  }
}

object MediaRangeAndQValue {
  private[headers] def apply(mediaRange: MediaRange, qValue: QValue): MediaRangeAndQValue =
    new MediaRangeAndQValue(mediaRange, qValue, MimeDB.default)

  def apply(mediaRange: MediaRange, qValue: QValue = QValue.One)(implicit mimeDB: MimeDB): MediaRangeAndQValue =
    new MediaRangeAndQValue(mediaRange, qValue, mimeDB)

  private[http4s] def withDefaultQValue(mediaRange: MediaRange): MediaRangeAndQValue =
    new MediaRangeAndQValue(mediaRange, QValue.One, MimeDB.default)

  implicit def withDefaultQValue(mediaRange: MediaRange)(implicit mimeDB: MimeDB): MediaRangeAndQValue =
    new MediaRangeAndQValue(mediaRange, QValue.One, mimeDB)
}

final case class Accept(values: NonEmptyList[MediaRangeAndQValue])
