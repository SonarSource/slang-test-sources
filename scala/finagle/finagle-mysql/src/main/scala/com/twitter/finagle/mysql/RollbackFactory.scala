package com.twitter.finagle.mysql

import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import com.twitter.util.{Future, Return, Stopwatch, Throw, Time}

object RollbackFactory {
  private val RollbackQuery = QueryRequest("ROLLBACK")

  private val log = Logger.get()

  val Role: Stack.Role = Stack.Role("RollbackFactory")

  private[finagle] def module: Stackable[ServiceFactory[Request, Result]] =
    new Stack.Module1[param.Stats, ServiceFactory[Request, Result]] {
      val role: Stack.Role = Role
      val description: String = "Installs a rollback factory in the stack"
      def make(sr: param.Stats, next: ServiceFactory[Request, Result]): ServiceFactory[Request, Result] = {
        new RollbackFactory(next, sr.statsReceiver)
      }
    }
}

/**
 * A `ServiceFactory` that ensures a ROLLBACK statement is issued when a service is put
 * back into the connection pool.
 *
 * @see https://dev.mysql.com/doc/en/implicit-commit.html
 */
final class RollbackFactory(
  client: ServiceFactory[Request, Result],
  statsReceiver: StatsReceiver
) extends ServiceFactoryProxy(client) {
  import RollbackFactory._

  private[this] val rollbackLatencyStat = statsReceiver.stat(s"rollback_latency_ms")

  private[this] def wrap(underlying: Service[Request, Result]): Service[Request, Result] =
    new ServiceProxy[Request, Result](underlying) {
      override def close(deadline: Time): Future[Unit] = {
        val elapsed = Stopwatch.start()
        self(RollbackQuery).transform { result =>
          rollbackLatencyStat.add(elapsed().inMillis)
          result match {
            case Return(_) => self.close(deadline)
            case Throw(t) =>
              log.warning(t, "rollback failed when putting service back into pool, closing connection")
              // we want to close the connection if we can't issue a rollback
              // since we assume it isn't a "clean" connection to put back into
              // the pool.
              self(PoisonConnectionRequest).transform { _ => self.close(deadline) }
          }
        }
      }
    }

  private[this] val wrapFn: Service[Request, Result] => Service[Request, Result] =
    { svc => wrap(svc) }

  override def apply(conn: ClientConnection): Future[Service[Request, Result]] =
    super.apply(conn).map(wrapFn)
}
