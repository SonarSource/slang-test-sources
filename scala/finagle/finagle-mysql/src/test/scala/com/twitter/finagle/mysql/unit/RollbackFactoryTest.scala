package com.twitter.finagle.mysql

import com.twitter.conversions.time._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.{ClientConnection, Service, ServiceFactory}
import com.twitter.util.{Await, Awaitable, Future, Time}
import org.scalatest.FunSuite

class RollbackFactoryTest extends FunSuite {

  private[this] def await[T](t: Awaitable[T]): T = Await.result(t, 5.seconds)

  test("RollbackFactory issues a rollback for each connection pool transaction") {
    var requests: Seq[Request] = Seq.empty
    val client = ServiceFactory.const[Request, Result](Service.mk[Request, Result] { req: Request =>
      requests = requests :+ req
      Future.value(EOF(0, ServerStatus(0)))
    })

    val rollbackClient = new RollbackFactory(client, NullStatsReceiver)

    await(rollbackClient().flatMap { svc =>
      for {
        _ <- svc(QueryRequest("1"))
        _ <- svc(QueryRequest("2"))
      } {
        svc.close()
      }
    })

    await(rollbackClient().flatMap { svc =>
      svc(QueryRequest("3")).ensure { svc.close() }
    })

    val expected = Seq(
      "1",
      "2",
      "ROLLBACK",
      "3",
      "ROLLBACK"
    ).map(QueryRequest)

    assert(requests == expected)
  }

  test("close is called on underlying service when rollback succeeds") {
    var closeCalled = false

    val client = new ServiceFactory[Request, Result] {
      private[this] val svc = new Service[Request, Result] {
        def apply(req: Request) = Future.value(EOF(0, ServerStatus(0)))
        override def close(when: Time) = {
          closeCalled = true
          Future.Done
        }
      }
      def apply(c: ClientConnection) = Future.value(svc)
      def close(deadline: Time): Future[Unit] = svc.close(deadline)
    }

    val rollbackClient = new RollbackFactory(client, NullStatsReceiver)

    await(rollbackClient().flatMap { svc =>
      svc(QueryRequest("1")).ensure { svc.close() }
    })
    assert(closeCalled)
  }

  test("poison request is sent when rollback fails") {
    var requests: Seq[Request] = Seq.empty
    var closeCalled = false
    val client = new ServiceFactory[Request, Result] {
      private[this] val svc = new Service[Request, Result] {
        def apply(req: Request) = req match {
          case QueryRequest("ROLLBACK") => Future.exception(new Exception("boom"))
          case _ =>
            requests = requests :+ req
            Future.value(EOF(0, ServerStatus(0)))
        }
        override def close(when: Time) = {
          closeCalled = true
          Future.Done
        }
      }
      def apply(c: ClientConnection) = Future.value(svc)
      def close(deadline: Time): Future[Unit] = svc.close(deadline)
    }

    val rollbackClient = new RollbackFactory(client, NullStatsReceiver)

    await(rollbackClient().flatMap { svc =>
      svc(QueryRequest("1")).ensure { svc.close() }
    })

    assert(requests == Seq(QueryRequest("1"), PoisonConnectionRequest))
    assert(closeCalled)
  }
}