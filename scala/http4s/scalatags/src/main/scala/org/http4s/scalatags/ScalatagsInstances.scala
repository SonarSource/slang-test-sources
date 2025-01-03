package org.http4s
package scalatags

import _root_.scalatags.Text.TypedTag
import cats.Applicative
import org.http4s.MediaType.{`text/html`}
import org.http4s.headers.`Content-Type`

trait ScalatagsInstances {

  implicit def scalatagsEncoder[F[_]: Applicative](
      implicit charset: Charset = DefaultCharset): EntityEncoder[F, TypedTag[String]] =
    contentEncoder(`text/html`)

  private def contentEncoder[F[_], C <: TypedTag[String]](
      mediaType: MediaType)(implicit F: Applicative[F], charset: Charset): EntityEncoder[F, C] =
    EntityEncoder
      .stringEncoder[F]
      .contramap[C](content => content.render)
      .withContentType(`Content-Type`(mediaType, charset))

}
