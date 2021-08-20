package com.permutive.pubsub.http.util

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

object RefCache {

  /**
    * Caches a single instance of type `A` for a period of time before refreshing it automatically.
    *
    * The time between refreshes is dynamic and based on the value of each `A` itself. This is similar to
    * [[RefreshableEffect]] except that only exposes a fixed refresh frequency.
    *
    * An old value is only made unavailable _after_ a new value has been acquired. This means that the time each value
    * is exposed for is `cacheDuration` plus the time to evaluate `fa`.
    *
    * @param fa                    generate a new value of `A`
    * @param cacheDuration         how long to cache a newly generated value of `A` for, effectul to allow arbitary
    *                              calculations
    * @param backgroundFailureHook what to do if retrying to refresh the value fails, up to user handle failing their
    *                              service, the refresh fiber will have stopped at this point
    * @param onNewValue            a callback invoked whenever a new value is generated, the [[FiniteDuration]] is the
    *                              period that will be waited before the next new value
    */
  def resource[F[_]: Concurrent: Timer, A](
    fa: F[A],
    cacheDuration: A => F[FiniteDuration],
    backgroundFailureHook: PartialFunction[Throwable, F[Unit]],
    onNewValue: Option[(A, FiniteDuration) => F[Unit]] = None,
  ): Resource[F, F[A]] = {
    val newValueHook: (A, FiniteDuration) => F[Unit] =
      onNewValue.getOrElse((_, _) => Applicative[F].unit)

    for {
      initialA <- Resource.eval(fa)
      ref      <- Resource.eval(Ref.of(initialA))
      // `.background` means the refresh loop runs in a fiber, but leaving the scope of the `Resource` will cancel
      // it for us. Use the provided callback if a failure occurs in the background fiber, there is no other way to
      // signal a failure from the background.
      _ <- refreshLoop(initialA, fa, ref, cacheDuration, newValueHook).onError(backgroundFailureHook).attempt.background
    } yield ref.get
  }

  private def refreshLoop[F[_]: Concurrent: Timer, A](
    initialA: A,
    fa: F[A],
    ref: Ref[F, A],
    cacheDuration: A => F[FiniteDuration],
    onNewValue: (A, FiniteDuration) => F[Unit],
  ): F[Unit] = {
    def innerLoop(currentA: A): F[Unit] =
      for {
        duration <- cacheDuration(currentA)
        _        <- onNewValue(currentA, duration)
        _        <- Timer[F].sleep(duration)
        // Note the old value is only removed from the `Ref` after we have acquired a new value.
        // We could remove the old value instantly if this implementation also used a `Deferred` and consumers block on
        // the empty deferred during acquisition of a new value. This would lead to edge cases that would be unpleasant
        // though; for example we'd need to handle the case of failing to acquire a new value ensuring consumers do not
        // block on an empty deferred forever.
        newA <- fa
        _    <- ref.set(newA)
        _    <- innerLoop(newA)
      } yield ()

    innerLoop(initialA)
  }

}
