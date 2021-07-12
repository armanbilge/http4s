/*
 * Copyright 2015 http4s.org
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

import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.syntax.either._
import fs2.Stream
import io.circe._
import io.circe.jawn._
import org.http4s.circe.base.CirceBaseInstances
import org.http4s.jawn.JawnInstances
import org.typelevel.jawn.ParseException
import org.typelevel.jawn.fs2.unwrapJsonArray

import java.nio.ByteBuffer

trait CirceInstances extends CirceBaseInstances with JawnInstances {
  protected val circeSupportParser =
    new CirceSupportParser(maxValueSize = None, allowDuplicateKeys = false)

  import circeSupportParser.facade

  override protected def defaultPrinter: Printer = super.defaultPrinter

  override protected def circeParseExceptionMessage: ParsingFailure => DecodeFailure =
    super.circeParseExceptionMessage

  override protected def jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
    super.jsonDecodeError

  def jsonDecoderIncremental[F[_]: Concurrent]: EntityDecoder[F, Json] =
    this.jawnDecoder[F, Json]

  def jsonDecoderByteBuffer[F[_]: Concurrent]: EntityDecoder[F, Json] =
    EntityDecoder.decodeBy(MediaType.application.json)(jsonDecoderByteBufferImpl[F])

  private def jsonDecoderByteBufferImpl[F[_]: Concurrent](m: Media[F]): DecodeResult[F, Json] =
    EntityDecoder.collectBinary(m).subflatMap { chunk =>
      val bb = ByteBuffer.wrap(chunk.toArray)
      if (bb.hasRemaining)
        circeSupportParser
          .parseFromByteBuffer(bb)
          .toEither
          .leftMap(e => circeParseExceptionMessage(ParsingFailure(e.getMessage(), e)))
      else
        Left(jawnEmptyBodyMessage)
    }

  // default cutoff value is based on benchmarks results
  implicit def jsonDecoder[F[_]: Concurrent]: EntityDecoder[F, Json] =
    jsonDecoderAdaptive(cutoff = 100000, MediaType.application.json)

  def jsonDecoderAdaptive[F[_]: Concurrent](
      cutoff: Long,
      r1: MediaRange,
      rs: MediaRange*): EntityDecoder[F, Json] =
    EntityDecoder.decodeBy(r1, rs: _*) { msg =>
      msg.contentLength match {
        case Some(contentLength) if contentLength < cutoff =>
          jsonDecoderByteBufferImpl[F](msg)
        case _ => this.jawnDecoderImpl[F, Json](msg)
      }
    }

  def jsonOf[F[_]: Concurrent, A: Decoder]: EntityDecoder[F, A] =
    jsonOfWithMedia(MediaType.application.json)

  def jsonOfSensitive[F[_]: Concurrent, A: Decoder](redact: Json => String): EntityDecoder[F, A] =
    jsonOfWithSensitiveMedia(redact, MediaType.application.json)

  def jsonOfWithMedia[F[_], A](r1: MediaRange, rs: MediaRange*)(implicit
      F: Concurrent[F],
      decoder: Decoder[A]): EntityDecoder[F, A] =
    jsonOfWithMediaHelper[F, A](r1, jsonDecodeError, rs: _*)

  def jsonOfWithSensitiveMedia[F[_], A](redact: Json => String, r1: MediaRange, rs: MediaRange*)(
      implicit
      F: Concurrent[F],
      decoder: Decoder[A]): EntityDecoder[F, A] =
    jsonOfWithMediaHelper[F, A](
      r1,
      (json, nelDecodeFailures) =>
        CirceBaseInstances.jsonDecodeErrorHelper(json, redact, nelDecodeFailures),
      rs: _*
    )

  private def jsonOfWithMediaHelper[F[_], A](
      r1: MediaRange,
      decodeErrorHandler: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure,
      rs: MediaRange*)(implicit F: Concurrent[F], decoder: Decoder[A]): EntityDecoder[F, A] =
    jsonDecoderAdaptive[F](cutoff = 100000, r1, rs: _*).flatMapR { json =>
      decoder
        .decodeJson(json)
        .fold(
          failure => DecodeResult.failureT(decodeErrorHandler(json, NonEmptyList.one(failure))),
          DecodeResult.successT(_)
        )
    }

  /** An [[EntityDecoder]] that uses circe's accumulating decoder for decoding the JSON.
    *
    * In case of a failure, returns an [[InvalidMessageBodyFailure]] with the cause containing
    * a [[DecodingFailures]] exception, from which the errors can be extracted.
    */
  def accumulatingJsonOf[F[_], A](implicit
      F: Concurrent[F],
      decoder: Decoder[A]): EntityDecoder[F, A] =
    jsonDecoder[F].flatMapR { json =>
      decoder
        .decodeAccumulating(json.hcursor)
        .fold(
          failures => DecodeResult.failureT(jsonDecodeError(json, failures)),
          DecodeResult.successT(_)
        )
    }

  override implicit def jsonEncoder[F[_]]: EntityEncoder[F, Json] =
    super.jsonEncoder

  override def jsonEncoderWithPrinter[F[_]](printer: Printer): EntityEncoder[F, Json] =
    super.jsonEncoderWithPrinter(printer)

  override def jsonEncoderOf[F[_], A: Encoder]: EntityEncoder[F, A] =
    super.jsonEncoderOf

  override def jsonEncoderWithPrinterOf[F[_], A](printer: Printer)(implicit
      encoder: Encoder[A]): EntityEncoder[F, A] =
    super.jsonEncoderWithPrinterOf(printer)

  override implicit def streamJsonArrayEncoder[F[_]]: EntityEncoder[F, Stream[F, Json]] =
    super.streamJsonArrayEncoder

  implicit def streamJsonArrayDecoder[F[_]: Concurrent]: EntityDecoder[F, Stream[F, Json]] =
    EntityDecoder.decodeBy(MediaType.application.json) { media =>
      DecodeResult.successT(media.body.chunks.through(unwrapJsonArray))
    }

  /** An [[EntityEncoder]] for a [[fs2.Stream]] of JSONs, which will encode it as a single JSON array. */
  override def streamJsonArrayEncoderWithPrinter[F[_]](printer: Printer): EntityEncoder[F, Stream[F, Json]] =
    super.streamJsonArrayEncoderWithPrinter(printer)

  override def streamJsonArrayEncoderOf[F[_], A: Encoder]: EntityEncoder[F, Stream[F, A]] =
    super.streamJsonArrayEncoderOf

  /** An [[EntityEncoder]] for a [[fs2.Stream]] of values, which will encode it as a single JSON array. */
  override def streamJsonArrayEncoderWithPrinterOf[F[_], A](printer: Printer)(implicit
      encoder: Encoder[A]): EntityEncoder[F, Stream[F, A]] =
    super.streamJsonArrayEncoderWithPrinterOf(printer)

  override implicit val encodeUri: Encoder[Uri] =
    Encoder.encodeString.contramap[Uri](_.toString)

  override implicit val decodeUri: Decoder[Uri] =
    Decoder.decodeString.emap { str =>
      Uri.fromString(str).leftMap(_ => "Uri")
    }

  implicit final def toMessageSyntax[F[_]](req: Message[F]): CirceInstances.MessageSyntax[F] =
    new CirceInstances.MessageSyntax(req)
}

sealed abstract case class CirceInstancesBuilder private[circe] (
    defaultPrinter: Printer = Printer.noSpaces,
    jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
      CirceBaseInstances.defaultJsonDecodeError,
    circeParseExceptionMessage: ParsingFailure => DecodeFailure =
      CirceBaseInstances.defaultCirceParseError,
    jawnParseExceptionMessage: ParseException => DecodeFailure =
      JawnInstances.defaultJawnParseExceptionMessage,
    jawnEmptyBodyMessage: DecodeFailure = JawnInstances.defaultJawnEmptyBodyMessage,
    circeSupportParser: CirceSupportParser =
      new CirceSupportParser(maxValueSize = None, allowDuplicateKeys = false)
) { self =>
  def withPrinter(pp: Printer): CirceInstancesBuilder =
    this.copy(defaultPrinter = pp)

  def withJsonDecodeError(
      f: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure): CirceInstancesBuilder =
    this.copy(jsonDecodeError = f)

  def withJawnParseExceptionMessage(f: ParseException => DecodeFailure): CirceInstancesBuilder =
    this.copy(jawnParseExceptionMessage = f)
  def withCirceParseExceptionMessage(f: ParsingFailure => DecodeFailure): CirceInstancesBuilder =
    this.copy(circeParseExceptionMessage = f)

  def withEmptyBodyMessage(df: DecodeFailure): CirceInstancesBuilder =
    this.copy(jawnEmptyBodyMessage = df)

  def withCirceSupportParser(csp: CirceSupportParser): CirceInstancesBuilder =
    this.copy(circeSupportParser = csp)

  protected def copy(
      defaultPrinter: Printer = self.defaultPrinter,
      jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
        self.jsonDecodeError,
      circeParseExceptionMessage: ParsingFailure => DecodeFailure = self.circeParseExceptionMessage,
      jawnParseExceptionMessage: ParseException => DecodeFailure = self.jawnParseExceptionMessage,
      jawnEmptyBodyMessage: DecodeFailure = self.jawnEmptyBodyMessage,
      circeSupportParser: CirceSupportParser = self.circeSupportParser
  ): CirceInstancesBuilder =
    new CirceInstancesBuilder(
      defaultPrinter,
      jsonDecodeError,
      circeParseExceptionMessage,
      jawnParseExceptionMessage,
      jawnEmptyBodyMessage,
      circeSupportParser) {}

  def build: CirceInstances =
    new CirceInstances {
      override val circeSupportParser: CirceSupportParser = self.circeSupportParser
      override val defaultPrinter: Printer = self.defaultPrinter
      override val jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
        self.jsonDecodeError
      override val circeParseExceptionMessage: ParsingFailure => DecodeFailure =
        self.circeParseExceptionMessage
      override val jawnParseExceptionMessage: ParseException => DecodeFailure =
        self.jawnParseExceptionMessage
      override val jawnEmptyBodyMessage: DecodeFailure = self.jawnEmptyBodyMessage
    }
}

object CirceInstances {

  def withPrinter(p: Printer): CirceInstancesBuilder =
    builder.withPrinter(p)

  val builder: CirceInstancesBuilder = new CirceInstancesBuilder() {}

  // Extension methods.

  private[circe] final class MessageSyntax[F[_]](private val req: Message[F]) extends AnyVal {
    def asJson(implicit F: JsonDecoder[F]): F[Json] =
      F.asJson(req)

    def asJsonDecode[A](implicit F: JsonDecoder[F], decoder: Decoder[A]): F[A] =
      F.asJsonDecode(req)

    def decodeJson[A](implicit F: Concurrent[F], decoder: Decoder[A]): F[A] =
      req.as(F, jsonOf[F, A])

    def json(implicit F: Concurrent[F]): F[Json] =
      req.as(F, jsonDecoder[F])
  }
}
