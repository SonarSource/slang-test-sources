package org.http4s
package client
package middleware

import cats.data.Kleisli
import cats.effect.IO
import cats.implicits.{catsSyntaxEither => _, _}
import fs2.Stream
import fs2.async.Ref
import org.http4s.dsl.io._
import org.specs2.specification.Tables
import scala.concurrent.duration._

class RetrySpec extends Http4sSpec with Tables {

  val route = HttpService[IO] {
    case req @ _ -> Root / "status-from-body" =>
      req.as[String].flatMap {
        case "OK" => Ok()
        case "" => InternalServerError()
      }
    case _ -> Root / status =>
      Response(Status.fromInt(status.toInt).valueOr(throw _)).pure[IO]
  }

  val defaultClient: Client[IO] = Client.fromHttpService(route)

  def countRetries(
      client: Client[IO],
      method: Method,
      status: Status,
      body: EntityBody[IO]): Int = {
    val max = 2
    var attemptsCounter = 1
    val policy = RetryPolicy[IO] { attempts: Int =>
      if (attempts >= max) None
      else {
        attemptsCounter = attemptsCounter + 1
        Some(10.milliseconds)
      }
    }
    val retryClient = Retry[IO](policy)(client)
    val req = Request[IO](method, uri("http://localhost/") / status.code.toString).withBody(body)
    retryClient
      .fetch(req) { _ =>
        IO.unit
      }
      .attempt
      .unsafeRunSync()
    attemptsCounter
  }

  "defaultRetriable" should {
    "retry GET based on status code" in {
      "status" | "retries" |>
        Ok ! 1 |
        Found ! 1 |
        BadRequest ! 1 |
        NotFound ! 1 |
        RequestTimeout ! 2 |
        InternalServerError ! 2 |
        NotImplemented ! 1 |
        BadGateway ! 2 |
        ServiceUnavailable ! 2 |
        GatewayTimeout ! 2 |
        HttpVersionNotSupported ! 1 | { countRetries(defaultClient, GET, _, EmptyBody) must_== _ }
    }

    "not retry non-idempotent methods" in prop { s: Status =>
      countRetries(defaultClient, POST, s, EmptyBody) must_== 1
    }

    def resubmit(method: Method)(
        retriable: (Request[IO], Either[Throwable, Response[IO]]) => Boolean) =
      Ref[IO, Boolean](false)
        .flatMap { ref =>
          val body = Stream.eval(ref.get.flatMap {
            case false => ref.modify(_ => true) *> IO.pure("")
            case true => IO.pure("OK")
          })
          val req = Request[IO](method, uri("http://localhost/status-from-body")).withBody(body)
          val policy = RetryPolicy[IO]({ attempts: Int =>
            if (attempts >= 2) None
            else Some(Duration.Zero)
          }, retriable)
          val retryClient = Retry[IO](policy)(defaultClient)
          retryClient.status(req)
        }
        .unsafeRunSync()

    "defaultRetriable does not resubmit bodies on idempotent methods" in {
      resubmit(POST)(RetryPolicy.defaultRetriable) must_== Status.InternalServerError
    }
    "unsafeRetriable does not resubmit bodies on non-idempotent methods" in {
      resubmit(POST)(RetryPolicy.unsafeRetriable) must_== Status.InternalServerError
    }
    "unsafeRetriable resubmits bodies on idempotent methods" in {
      resubmit(PUT)(RetryPolicy.unsafeRetriable) must_== Status.Ok
    }
    "recklesslyRetriable resubmits bodies on non-idempotent methods" in {
      resubmit(POST)((req, result) => RetryPolicy.recklesslyRetriable(result)) must_== Status.Ok
    }

    "retry exceptions" in {
      val failClient = Client[IO](Kleisli.liftF(IO.raiseError(new Exception("boom"))), IO.unit)
      countRetries(failClient, GET, InternalServerError, EmptyBody) must_== 2
    }
  }
}
