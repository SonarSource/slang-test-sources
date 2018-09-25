package com.twitter.finagle.loadbalancer

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.stats.{Counter, InMemoryStatsReceiver}
import com.twitter.util.{Await, Future, Time}
import org.scalacheck.Gen
import org.scalatest.FunSuite
import org.scalatest.concurrent.Conductors
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scala.language.reflectiveCalls

class BalancerTest extends FunSuite with Conductors with GeneratorDrivenPropertyChecks {

  private class TestBalancer(
    protected val statsReceiver: InMemoryStatsReceiver = new InMemoryStatsReceiver
  ) extends Balancer[Unit, Unit] {

    def maxEffort: Int = 5
    def emptyException: Throwable = ???

    def additionalMetadata: Map[String, Any] = Map.empty

    def stats: InMemoryStatsReceiver = statsReceiver
    protected[this] val maxEffortExhausted: Counter = stats.counter("max_effort_exhausted")

    def nodes: Vector[Node] = dist.vector
    def factories: Set[ServiceFactory[Unit, Unit]] = nodes.map(_.factory).toSet

    def nodeOf(fac: ServiceFactory[Unit, Unit]): Node = nodes.find(_.factory == fac).get

    def _dist(): Distributor = dist
    def _rebuild(): Unit = rebuild()

    def rebuildDistributor(): Unit = {}

    case class Distributor(vec: Vector[Node], gen: Int = 1) extends DistributorT[Node](vec) {
      type This = Distributor
      def pick(): Node = vector.head
      def rebuild(): This = {
        rebuildDistributor()
        copy(gen = gen + 1)
      }
      def rebuild(vector: Vector[Node]): This = {
        rebuildDistributor()
        copy(vector, gen = gen + 1)
      }
      def needsRebuild: Boolean = false
    }

    class Node(val factory: EndpointFactory[Unit, Unit]) extends NodeT[Unit, Unit] {
      def load: Double = ???
      def pending: Int = ???
      def close(deadline: Time): Future[Unit] = TestBalancer.this.synchronized {
        factory.close()
        Future.Done
      }
      def apply(conn: ClientConnection): Future[Service[Unit, Unit]] = Future.never
    }

    protected def newNode(factory: EndpointFactory[Unit, Unit]): Node = new Node(factory)
    protected def failingNode(cause: Throwable): Node = ???

    protected def initDistributor(): Distributor = Distributor(Vector.empty)
  }

  def newFac(_status: Status = Status.Open) = new EndpointFactory[Unit, Unit] {
    def address: Address = Address.Failed(new Exception)
    def remake(): Unit = ()

    def apply(conn: ClientConnection): Future[Service[Unit, Unit]] = Future.never
    override def status: Status = _status

    @volatile var ncloses = 0

    def close(deadline: Time): Future[Unit] = {
      synchronized { ncloses += 1 }
      Future.Done
    }
  }

  private val genStatus = Gen.oneOf(Status.Open, Status.Busy, Status.Closed)
  private val genSvcFac = genStatus.map(newFac)
  private val genLoadedNode = for (fac <- genSvcFac) yield fac
  private val genNodes = Gen.containerOf[Vector, EndpointFactory[Unit, Unit]](genLoadedNode)

  test("status: balancer with no nodes is Closed") {
    val bal = new TestBalancer
    assert(bal.nodes.isEmpty)
    assert(bal.status == Status.Closed)
  }

  test("status: balancer status is bestOf underlying nodes") {
    forAll(genNodes) { loadedNodes =>
      val bal = new TestBalancer
      bal.update(loadedNodes)
      val best = Status.bestOf[ServiceFactory[Unit, Unit]](loadedNodes, _.status)
      assert(bal.status == best)
    }
  }

  test("status: balancer with at least one Open node is Open") {
    val bal = new TestBalancer
    val f1, f2 = newFac(Status.Closed)
    val f3 = newFac(Status.Open)
    bal.update(Vector(f1, f2, f3))

    assert(bal.status == Status.Open)

    // all closed
    bal.update(Vector(f1, f2))
    assert(bal.status == Status.Closed)

    // one busy, remainder closed
    val busy = newFac(Status.Busy)
    bal.update(Vector(f1, f2, busy))
    assert(bal.status == Status.Busy)
  }

  test("max_effort_exhausted counter updated properly") {
    val bal = new TestBalancer()
    val closed = newFac(Status.Closed)
    val open = newFac(Status.Open)

    // start out all closed
    bal.update(Vector(closed))
    bal(ClientConnection.nil)
    assert(1 == bal.stats.counters(Seq("max_effort_exhausted")))

    // now have it be open and a pick must succeed
    bal.update(Vector(open))
    bal(ClientConnection.nil)
    assert(1 == bal.stats.counters(Seq("max_effort_exhausted")))
  }

  test("updater: keeps nodes up to date") {
    val bal = new TestBalancer
    val f1, f2, f3 = newFac()

    val adds = bal.stats.counter("adds")
    val rems = bal.stats.counter("removes")
    val size = bal.stats.gauges(Seq("size"))
    assert(bal.nodes.isEmpty)
    assert(size() == 0)
    assert(adds() == 0)
    assert(rems() == 0)

    bal.update(Vector(f1, f2, f3))
    assert(bal.factories == Set(f1, f2, f3))
    assert(size() == 3)
    assert(adds() == 3)
    assert(rems() == 0)
    for (f <- Seq(f1, f2, f3))
      assert(f.ncloses == 0)

    bal.update(Vector(f1, f3))
    assert(size() == 2)
    assert(adds() == 3)
    assert(rems() == 1)
    assert(bal.factories == Set(f1, f3))
    assert(f1.ncloses == 0)
    assert(f2.ncloses == 0)
    assert(f3.ncloses == 0)

    bal.update(Vector(f1, f2, f3))
    assert(bal.factories == Set(f1, f2, f3))
    assert(size() == 3)
    assert(adds() == 4)
    assert(rems() == 1)
  }

  test("update order and element caching") {
    val bal = new TestBalancer
    val f1, f2, f3 = newFac()

    val update0 = Vector(f1, f2, f3)
    bal.update(update0)
    assert(bal._dist().vector.indices.forall { i =>
      update0(i) eq bal._dist().vector(i).factory
    })

    val update1 = Vector(f1, f2, f3, newFac(), newFac())
    bal.update(update1)

    assert(bal._dist().vector(0).factory eq f1)
    assert(bal._dist().vector(1).factory eq f2)
    assert(bal._dist().vector(2).factory eq f3)
    assert(bal._dist().vector.indices.forall { i =>
      update1(i) eq bal._dist().vector(i).factory
    })
  }

  test("close unregisters from the registry") {
    val label = "test_balancer_" + System.currentTimeMillis
    val bal = new TestBalancer()
    val registry = BalancerRegistry.get
    registry.register(label, bal)
    assert(registry.allMetadata.exists(_.label == label))

    Await.result(bal.close(), 5.seconds)
    assert(!registry.allMetadata.exists(_.label == label))
  }

}
