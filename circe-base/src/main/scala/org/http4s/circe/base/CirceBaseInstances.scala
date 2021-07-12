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
package base

import cats.data.NonEmptyList
import cats.syntax.either._
import fs2.{Chunk, Pull, Stream}
import io.circe._
import org.http4s.headers.`Content-Type`

trait CirceBaseInstances {

  protected def defaultPrinter: Printer = Printer.noSpaces

  protected def circeParseExceptionMessage: ParsingFailure => DecodeFailure =
    CirceBaseInstances.defaultCirceParseError

  protected def jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
    CirceBaseInstances.defaultJsonDecodeError

  implicit def jsonEncoder[F[_]]: EntityEncoder[F, Json] =
    jsonEncoderWithPrinter(defaultPrinter)

  def jsonEncoderWithPrinter[F[_]](printer: Printer): EntityEncoder[F, Json] =
    EntityEncoder[F, Chunk[Byte]]
      .contramap[Json](CirceBaseInstances.fromJsonToChunk(printer))
      .withContentType(`Content-Type`(MediaType.application.json))

  def jsonEncoderOf[F[_], A: Encoder]: EntityEncoder[F, A] =
    jsonEncoderWithPrinterOf(defaultPrinter)

  def jsonEncoderWithPrinterOf[F[_], A](printer: Printer)(implicit
      encoder: Encoder[A]): EntityEncoder[F, A] =
    jsonEncoderWithPrinter[F](printer).contramap[A](encoder.apply)

  implicit def streamJsonArrayEncoder[F[_]]: EntityEncoder[F, Stream[F, Json]] =
    streamJsonArrayEncoderWithPrinter(defaultPrinter)

  /** An [[EntityEncoder]] for a [[fs2.Stream]] of JSONs, which will encode it as a single JSON array. */
  def streamJsonArrayEncoderWithPrinter[F[_]](printer: Printer): EntityEncoder[F, Stream[F, Json]] =
    EntityEncoder
      .streamEncoder[F, Chunk[Byte]]
      .contramap[Stream[F, Json]] { stream =>
        stream.through(CirceBaseInstances.streamedJsonArray(printer)).chunks
      }
      .withContentType(`Content-Type`(MediaType.application.json))

  def streamJsonArrayEncoderOf[F[_], A: Encoder]: EntityEncoder[F, Stream[F, A]] =
    streamJsonArrayEncoderWithPrinterOf(defaultPrinter)

  /** An [[EntityEncoder]] for a [[fs2.Stream]] of values, which will encode it as a single JSON array. */
  def streamJsonArrayEncoderWithPrinterOf[F[_], A](printer: Printer)(implicit
      encoder: Encoder[A]): EntityEncoder[F, Stream[F, A]] =
    streamJsonArrayEncoderWithPrinter[F](printer).contramap[Stream[F, A]](_.map(encoder.apply))

  implicit val encodeUri: Encoder[Uri] =
    Encoder.encodeString.contramap[Uri](_.toString)

  implicit val decodeUri: Decoder[Uri] =
    Decoder.decodeString.emap { str =>
      Uri.fromString(str).leftMap(_ => "Uri")
    }

  // implicit final def toMessageSyntax[F[_]](req: Message[F]): CirceBaseInstances.MessageSyntax[F] =
  //   new CirceBaseInstances.MessageSyntax(req)

}

object CirceBaseInstances {

  // These are lazy since they are used when initializing the `builder`!

  private[circe] lazy val defaultCirceParseError: ParsingFailure => DecodeFailure =
    pe => MalformedMessageBodyFailure("Invalid JSON", Some(pe))

  private[circe] lazy val defaultJsonDecodeError
      : (Json, NonEmptyList[DecodingFailure]) => DecodeFailure = { (json, failures) =>
    jsonDecodeErrorHelper(json, _.toString, failures)
  }

  private[circe] def jsonDecodeErrorHelper(
      json: Json,
      jsonToString: Json => String,
      failures: NonEmptyList[DecodingFailure]
  ): DecodeFailure = {

    val str: String = jsonToString(json)

    InvalidMessageBodyFailure(
      s"Could not decode JSON: $str",
      if (failures.tail.isEmpty) Some(failures.head) else Some(DecodingFailures(failures)))
  }

  // Constant byte chunks for the stream as JSON array encoder.

  private def fromJsonToChunk(printer: Printer)(json: Json): Chunk[Byte] =
    Chunk.ByteBuffer.view(printer.printToByteBuffer(json))

  private def streamedJsonArray[F[_]](printer: Printer)(s: Stream[F, Json]): Stream[F, Byte] =
    s.pull.uncons1.flatMap {
      case None => Pull.output(emptyArray)
      case Some((hd, tl)) =>
        Pull.output(
          Chunk.concat(Vector(CirceBaseInstances.openBrace, fromJsonToChunk(printer)(hd)))
        ) >> // Output First Json As Chunk with leading `[`
          tl.repeatPull {
            _.uncons.flatMap {
              case None => Pull.pure(None)
              case Some((hd, tl)) =>
                val interspersed = {
                  val bldr = Vector.newBuilder[Chunk[Byte]]
                  bldr.sizeHint(hd.size * 2)
                  hd.foreach { o =>
                    bldr += CirceBaseInstances.comma
                    bldr += fromJsonToChunk(printer)(o)
                  }
                  Chunk.concat(bldr.result())
                }
                Pull.output(interspersed) >> Pull.pure(Some(tl))
            }
          }.pull
            .echo >>
          Pull.output(closeBrace)
    }.stream

  private final val openBrace: Chunk[Byte] =
    Chunk.singleton('['.toByte)

  private final val closeBrace: Chunk[Byte] =
    Chunk.singleton(']'.toByte)

  private final val emptyArray: Chunk[Byte] =
    Chunk.array(Array('['.toByte, ']'.toByte))

  private final val comma: Chunk[Byte] =
    Chunk.singleton(','.toByte)

  // Extension methods.

  // private[circe] final class MessageSyntax[F[_]](private val req: Message[F]) extends AnyVal {
  //   def asJson(implicit F: JsonDecoder[F]): F[Json] =
  //     F.asJson(req)

  //   def asJsonDecode[A](implicit F: JsonDecoder[F], decoder: Decoder[A]): F[A] =
  //     F.asJsonDecode(req)

  //   def decodeJson[A](implicit F: Concurrent[F], jsonDecoder: JsonDecoder[F], decoder: Decoder[A]): F[A] =
  //     req.as(F, jsonOf[F, A])

  //   def json(implicit F: Concurrent[F], decoder: JsonDecoder[F]): F[Json] =
  //     decoder.asJson(req)
  // }
}
