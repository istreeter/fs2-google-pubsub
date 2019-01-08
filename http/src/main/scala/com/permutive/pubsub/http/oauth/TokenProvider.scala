package com.permutive.pubsub.http.oauth

import scala.util.control.NoStackTrace

trait TokenProvider[F[_]] {
  def accessToken: F[AccessToken]
}

object TokenProvider {
  case object TokenValidityTooLong
    extends RuntimeException("Valid for duration cannot be longer than maximum of the OAuth provider")
      with NoStackTrace

  case object FailedToGetToken
    extends RuntimeException("Failed to get token after many attempts")
}





