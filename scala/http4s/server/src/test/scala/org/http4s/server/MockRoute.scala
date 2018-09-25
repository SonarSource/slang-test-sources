package org.http4s
package server

import cats.effect._
import cats.implicits._
import org.http4s.Status.{Accepted, Ok}
import org.http4s.server.middleware.PushSupport._

object MockRoute extends Http4s {

  def route(): HttpService[IO] = HttpService {
    case req if req.uri.path === "/ping" =>
      Response[IO](Ok).withBody("pong")

    case req if req.method === Method.POST && req.uri.path === "/echo" =>
      IO.pure(Response[IO](body = req.body))

    case req if req.uri.path === "/withslash" =>
      IO.pure(Response(Ok))

    case req if req.uri.path === "/withslash/" =>
      IO.pure(Response(Accepted))

    case req if req.uri.path === "/fail" =>
      sys.error("Problem!")

    /** For testing the PushSupport middleware */
    case req if req.uri.path === "/push" =>
      Response[IO](Ok).withBody("Hello").push("/ping")(req)
  }
}
