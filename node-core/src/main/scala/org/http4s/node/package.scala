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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.Chunk
import fs2.INothing
import fs2.Pipe
import fs2.Pull
import fs2.Stream
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.eventsMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.streamMod

import scala.annotation.nowarn
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.TypedArrayBuffer
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.|
import scala.scalajs.{js => scalajs}

// TODO terrible copy-pasta until merged into fs2
package object node {

  def fromReadable[F[_]](readable: F[streamMod.Readable])(implicit F: Async[F]): Stream[F, Byte] =
    Stream
      .resource(for {
        readable <- Resource.makeCase(readable) {
          case (readable, Resource.ExitCase.Succeeded) => F.delay(readable.destroy())
          case (readable, Resource.ExitCase.Errored(ex)) =>
            F.delay(readable.destroy(scalajs.Error(ex.getMessage())))
          case (readable, Resource.ExitCase.Canceled) => F.delay(readable.destroy())
        }
        dispatcher <- Dispatcher[F]
        queue <- Queue.synchronous[F, Unit].toResource
        ended <- F.deferred[Either[Throwable, Unit]].toResource
        _ <- registerListener0(readable, nodeStrings.readable)(_.on_readable(_, _)) { () =>
          dispatcher.unsafeRunAndForget(queue.offer(()))
        }
        _ <- registerListener0(readable, nodeStrings.end)(_.on_end(_, _)) { () =>
          dispatcher.unsafeRunAndForget(ended.complete(Right(())))
        }
        _ <- registerListener[scalajs.Error](readable, nodeStrings.error)(_.on_error(_, _)) { e =>
          dispatcher.unsafeRunAndForget(ended.complete(Left(scalajs.JavaScriptException(e))))
        }
      } yield (readable, queue, ended))
      .flatMap { case (readable, queue, ended) =>
        Stream.fromQueueUnterminated(queue).interruptWhen(ended) >>
          Stream.evalUnChunk(
            F.delay(
              Option(readable.read().asInstanceOf[bufferMod.global.Buffer])
                .fold(Chunk.empty[Byte])(_.toChunk)
            )
          )
      }

  def toReadable[F[_]](s: Stream[F, Byte])(implicit F: Async[F]): Resource[F, streamMod.Readable] =
    for {
      dispatcher <- Dispatcher[F]
      semaphore <- Semaphore[F](1).toResource
      ref <- F.ref(s).toResource
      read = semaphore.permit.use { _ =>
        Pull
          .eval(ref.get)
          .flatMap(_.pull.uncons)
          .flatMap {
            case Some((head, tail)) =>
              Pull.eval(ref.set(tail)) >> Pull.output(head)
            case None =>
              Pull.done
          }
          .stream
          .chunks
          .compile
          .last
      }
      readable <- Resource.make {
        F.pure {
          new streamMod.Readable(streamMod.ReadableOptions().setRead { (readable, size) =>
            dispatcher.unsafeRunAndForget(
              read.attempt.flatMap {
                case Left(ex) => F.delay(readable.destroy(scalajs.Error(ex.getMessage)))
                case Right(chunk) => F.delay(readable.push(chunk.map(_.toUint8Array).orNull)).void
              }
            )
          })
        }
      } { readable =>
        F.delay(if (!readable.readableEnded) readable.destroy())
      }
    } yield readable

  def fromWritable[F[_]](
      writable: F[streamMod.Writable]
  )(implicit F: Async[F]): Pipe[F, Byte, INothing] =
    in =>
      Stream.eval(writable).flatMap { writable =>
        def go(
            s: Stream[F, Byte]
        ): Pull[F, INothing, Unit] = s.pull.uncons.flatMap {
          case Some((head, tail)) =>
            Pull.eval {
              F.async_[Unit] { cb =>
                writable.write(
                  head.toUint8Array: scalajs.Any,
                  e => cb(e.toLeft(()).leftMap(scalajs.JavaScriptException))
                )
              }
            } >> go(tail)
          case None => Pull.eval(F.delay(writable.end())) >> Pull.done
        }

        go(in).stream.handleErrorWith { ex =>
          Stream.eval(F.delay(writable.destroy(scalajs.Error(ex.getMessage))))
        }.drain
      }

  def mkWritable[F[_]](implicit F: Async[F]): Resource[F, (streamMod.Writable, Stream[F, Byte])] =
    for {
      dispatcher <- Dispatcher[F]
      queue <- Queue.synchronous[F, Option[Chunk[Byte]]].toResource
      error <- F.deferred[Throwable].toResource
      writable <- Resource.make {
        F.pure {
          new streamMod.Writable(
            streamMod
              .WritableOptions()
              .setWrite { (writable, chunk, encoding, cb) =>
                dispatcher.unsafeRunAndForget(
                  queue
                    .offer(Some(Chunk.uint8Array(chunk.asInstanceOf[Uint8Array])))
                    .attempt
                    .flatMap(e =>
                      F.delay(
                        cb(
                          e.left.toOption.fold[scalajs.Error | Null](null)(e =>
                            scalajs.Error(e.getMessage()))
                        )
                      ))
                )
              }
              .setFinal { (writable, cb) =>
                dispatcher.unsafeRunAndForget(
                  queue
                    .offer(None)
                    .attempt
                    .flatMap(e =>
                      F.delay(
                        cb(
                          e.left.toOption.fold[scalajs.Error | Null](null)(e =>
                            scalajs.Error(e.getMessage()))
                        )
                      ))
                )
              }
              .setDestroy { (writable, err, cb) =>
                dispatcher.unsafeRunAndForget {
                  Option(err).fold(F.unit) { err =>
                    error
                      .complete(scalajs.JavaScriptException(err))
                      .attempt
                      .flatMap(e =>
                        F.delay(
                          cb(
                            e.left.toOption
                              .fold[scalajs.Error | Null](null)(e => scalajs.Error(e.getMessage()))
                          )
                        ))
                  }
                }
              }
          )
        }
      } { writable =>
        F.delay(if (!writable.writableEnded) writable.destroy())
      }
      stream = Stream
        .fromQueueNoneTerminatedChunk(queue)
        .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Unit])))
    } yield (writable, stream)

  def registerListener0[F[_]: Sync, E, V](emitter: E, event: V)(
      register: (E, V, scalajs.Function0[Unit]) => Unit
  )(callback: scalajs.Function0[Unit]): Resource[F, Unit] =
    Resource.make(Sync[F].delay(register(emitter, event, callback)))(_ =>
      Sync[F].delay(
        emitter
          .asInstanceOf[eventsMod.EventEmitter]
          .removeListener(
            event.asInstanceOf[String],
            callback.asInstanceOf[scalajs.Function1[scalajs.Any, Unit]]
          )
      ))

  def registerListener[A] = new RegisterListenerPartiallyApplied[A]

  @nowarn
  final class RegisterListenerPartiallyApplied[A](private val dummy: Boolean = false)
      extends AnyVal {

    def apply[F[_]: Sync, E, V](emitter: E, event: V)(
        register: (E, V, scalajs.Function1[A, Unit]) => Unit
    )(callback: scalajs.Function1[A, Unit]): Resource[F, Unit] =
      Resource.make(Sync[F].delay(register(emitter, event, callback)))(_ =>
        Sync[F].delay(
          emitter
            .asInstanceOf[eventsMod.EventEmitter]
            .removeListener(
              event.asInstanceOf[String],
              callback.asInstanceOf[scalajs.Function1[scalajs.Any, Unit]]
            )
        ))
  }

  implicit def toByteChunkOps(chunk: Chunk[Byte]): ByteChunkOps = new ByteChunkOps(chunk)
  implicit def toBufferOps(buffer: bufferMod.global.Buffer): BufferOps = new BufferOps(buffer)

  @nowarn
  final class ByteChunkOps(val chunk: Chunk[Byte]) extends AnyVal {
    def toBuffer: bufferMod.global.Buffer = bufferMod.Buffer.from(chunk.toUint8Array)
  }

  @nowarn
  final class BufferOps(val buffer: bufferMod.global.Buffer) extends AnyVal {
    def toChunk: Chunk[Byte] = Chunk.byteBuffer(
      TypedArrayBuffer
        .wrap(
          buffer.buffer.asInstanceOf[ArrayBuffer],
          buffer.byteOffset.toInt,
          buffer.byteLength.toInt
        )
    )
  }

}
