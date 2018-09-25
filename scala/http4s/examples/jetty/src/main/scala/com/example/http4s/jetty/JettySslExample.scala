package com.example.http4s
package jetty

import cats.effect.IO
import com.example.http4s.ssl.SslExample
import org.http4s.server.jetty.JettyBuilder

object JettySslExample extends SslExample[IO] {
  def builder: JettyBuilder[IO] = JettyBuilder[IO]
}
