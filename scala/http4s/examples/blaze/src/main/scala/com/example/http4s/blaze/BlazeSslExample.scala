package com.example.http4s
package blaze

import cats.effect.IO
import com.example.http4s.ssl.SslExample
import org.http4s.server.blaze.BlazeBuilder

object BlazeSslExample extends SslExample[IO] {
  def builder: BlazeBuilder[IO] = BlazeBuilder[IO]
}
