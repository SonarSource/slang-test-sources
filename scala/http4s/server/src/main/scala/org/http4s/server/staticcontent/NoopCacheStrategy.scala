package org.http4s
package server
package staticcontent

import cats.effect._

/** Cache strategy that doesn't cache anything, ever. */
class NoopCacheStrategy[F[_]] extends CacheStrategy[F] {
  override def cache(uriPath: String, resp: Response[F])(implicit F: Effect[F]): F[Response[F]] =
    F.pure(resp)
}

object NoopCacheStrategy {
  def apply[F[_]]: NoopCacheStrategy[F] = new NoopCacheStrategy[F]
}
