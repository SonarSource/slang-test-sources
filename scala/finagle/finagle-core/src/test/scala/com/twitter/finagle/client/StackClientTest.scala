package com.twitter.finagle.client

import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Module0
import com.twitter.finagle._
import com.twitter.finagle.client.utils.{PushStringClient, StringClient}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.dispatch.SerialClientDispatcher
import com.twitter.finagle.filter.ClearContextValueFilter
import com.twitter.finagle.naming.BindingFactory
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.finagle.naming.{DefaultInterpreter, NameInterpreter}
import com.twitter.finagle.netty4.Netty4Transporter
import com.twitter.finagle.server.utils.StringServer
import com.twitter.finagle.service.FailFastFactory.FailFast
import com.twitter.finagle.service.PendingRequestFilter
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.{Transport, TransportContext}
import com.twitter.finagle.util.StackRegistry
import com.twitter.finagle.{Name, param}
import com.twitter.util._
import com.twitter.util.registry.{Entry, GlobalRegistry, SimpleRegistry}
import java.net.{InetAddress, InetSocketAddress, SocketAddress}
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

private object StackClientTest {
  case class LocalCheckingStringClient(
    localKey: Contexts.local.Key[String],
    stack: Stack[ServiceFactory[String, String]] = StackClient.newStack,
    params: Stack.Params = Stack.Params.empty
  ) extends StdStackClient[String, String, LocalCheckingStringClient] {

    protected def copy1(
      stack: Stack[ServiceFactory[String, String]] = this.stack,
      params: Stack.Params = this.params
    ): LocalCheckingStringClient = copy(localKey, stack, params)

    protected type In = String
    protected type Out = String
    protected type Context = TransportContext

    protected def newTransporter(
      addr: SocketAddress
    ): Transporter[String, String, TransportContext] =
      Netty4Transporter.raw(StringClient.StringClientPipeline, addr, params)

    protected def newDispatcher(
      transport: Transport[In, Out] { type Context <: LocalCheckingStringClient.this.Context }
    ): Service[String, String] = {
      Contexts.local.get(localKey) match {
        case Some(s) =>
          Service.constant(
            Future.exception(new IllegalStateException("should not have a local context: " + s))
          )
        case None =>
          new SerialClientDispatcher(transport)
      }
    }
  }
}

class StdStackClientTest extends AbstractStackClientTest {
  type ClientType = StringClient.Client
  def baseClient: ClientType = StringClient.client
  def transporterName: String = "Netty4Transporter"
}

class PushStackClientTest extends AbstractStackClientTest {
  type ClientType = PushStringClient.Client
  def baseClient: ClientType = PushStringClient.client
  def transporterName: String = "Netty4PushTransporter"
}

abstract class AbstractStackClientTest
    extends FunSuite
    with BeforeAndAfter
    with Eventually
    with IntegrationPatience {

  type ClientType <: EndpointerStackClient[String, String, ClientType]

  def baseClient: ClientType
  def transporterName: String

  trait Ctx {
    val sr = new InMemoryStatsReceiver
    val client = baseClient.configured(param.Stats(sr))
  }

  def await[T](awaitable: Awaitable[T]): T = Await.result(awaitable, 5.seconds)

  after {
    NameInterpreter.global = DefaultInterpreter
  }

  test("client stats are scoped to label")(new Ctx {
    // use dest when no label is set
    client.newService("inet!127.0.0.1:8080")
    eventually {
      val counter = sr.counters(Seq("inet!127.0.0.1:8080", "loadbalancer", "adds"))
      assert(
        counter == 1,
        s"The instance should be to the loadbalancer once instead of $counter times."
      )
    }

    // use param.Label when set
    client.configured(param.Label("myclient")).newService("127.0.0.1:8080")
    eventually {
      assert(sr.counters(Seq("myclient", "loadbalancer", "adds")) == 1)
    }

    // use evaled label when both are set
    client.configured(param.Label("myclient")).newService("othername=127.0.0.1:8080")
    eventually {
      assert(sr.counters(Seq("othername", "loadbalancer", "adds")) == 1)
    }
  })

  test("Client added to client registry")(new Ctx {
    ClientRegistry.clear()

    val name = "testClient"
    client.newClient(Name.bound(Address(8080)), name)
    client.newClient(Name.bound(Address(8080)), name)

    assert(ClientRegistry.registrants.count { e: StackRegistry.Entry =>
      val param.Label(actual) = e.params[param.Label]
      name == actual
    } == 1)
  })

  test("FailFast is respected") {
    val ctx = new Ctx {}

    val ex = new RuntimeException("lol")
    val alwaysFail = new Module0[ServiceFactory[String, String]] {
      val role = Stack.Role("lol")
      val description = "lool"

      def make(next: ServiceFactory[String, String]) =
        ServiceFactory.apply[String, String](() => Future.exception(ex))
    }

    val alwaysFailStack = new StackBuilder(stack.nilStack[String, String])
      .push(alwaysFail)
      .result
    val stk = ctx.client.stack.concat(alwaysFailStack)

    def newClient(name: String, failFastOn: Option[Boolean]): Service[String, String] = {
      var stack = ctx.client
        .configured(param.Label(name))
        .withStack(stk)
      failFastOn.foreach { ffOn =>
        stack = stack.configured(FailFast(ffOn))
      }
      val client = stack.newClient("/$/inet/localhost/0")
      new FactoryToService[String, String](client)
    }

    def testClient(name: String, failFastOn: Option[Boolean]): Unit = {
      val svc = newClient(name, failFastOn)
      val e = intercept[RuntimeException] { await(svc("hi")) }
      assert(e == ex)
      failFastOn match {
        case Some(on) if !on =>
          assert(!ctx.sr.counters.contains(Seq(name, "failfast", "marked_dead")))
          intercept[RuntimeException] { await(svc("hi2")) }
        case _ =>
          eventually {
            assert(ctx.sr.counters(Seq(name, "failfast", "marked_dead")) == 1)
          }
          intercept[FailedFastException] { await(svc("hi2")) }
      }
    }

    testClient("ff-client-default", None)
    testClient("ff-client-enabled", Some(true))
    testClient("ff-client-disabled", Some(false))
  }

  test("FactoryToService close propagated to underlying service") {
    /*
     * This test ensures that the following one doesn't succeed vacuously.
     */

    var closed = false

    val underlyingFactory = new ServiceFactory[Unit, Unit] {
      def apply(conn: ClientConnection) =
        Future.value(new Service[Unit, Unit] {
          def apply(request: Unit): Future[Unit] = Future.Unit

          override def close(deadline: Time) = {
            closed = true
            Future.Done
          }
        })

      def close(deadline: Time) = Future.Done
    }

    val stack = StackClient
      .newStack[Unit, Unit]
      .concat(Stack.leaf(Stack.Role("role"), underlyingFactory))
      // don't pool or else we don't see underlying close until service is ejected from pool
      .remove(DefaultPool.Role)

    val factory = stack.make(
      Stack.Params.empty +
        FactoryToService.Enabled(true) +

        // default Dest is /$/fail
        BindingFactory.Dest(Name.Path(Path.read("/$/inet/localhost/0")))
    )

    val service = new FactoryToService(factory)
    await(service(()))

    assert(closed)
  }

  test("prepFactory above FactoryToService") {
    /*
     * This approximates code in finagle-http which wraps services (in
     * prepFactory) so the close is delayed until the chunked response
     * has been read. We need prepFactory above FactoryToService or
     * else FactoryToService closes the underlying service too soon.
     */

    var closed = false

    val underlyingFactory = new ServiceFactory[Unit, Unit] {
      def apply(conn: ClientConnection) =
        Future.value(new Service[Unit, Unit] {
          def apply(request: Unit): Future[Unit] = Future.Unit

          override def close(deadline: Time) = {
            closed = true
            Future.Done
          }
        })

      def close(deadline: Time) = Future.Done
    }

    val stack = StackClient
      .newStack[Unit, Unit]
      .concat(Stack.leaf(Stack.Role("role"), underlyingFactory))
      // don't pool or else we don't see underlying close until service is ejected from pool
      .remove(DefaultPool.Role)
      .replace(
        StackClient.Role.prepFactory, { next: ServiceFactory[Unit, Unit] =>
          next map { service: Service[Unit, Unit] =>
            new ServiceProxy[Unit, Unit](service) {
              override def close(deadline: Time) = Future.never
            }
          }
        }
      )

    val factory = stack.make(
      Stack.Params.empty +
        FactoryToService.Enabled(true) +

        // default Dest is /$/fail
        BindingFactory.Dest(Name.Path(Path.read("/$/inet/localhost/0")))
    )

    val service = new FactoryToService(factory)
    await(service(()))

    assert(!closed)
  }

  trait RequeueCtx {
    var sessionDispatchCount = 0
    var sessionCloseCount = 0
    var _svcFacStatus: Status = Status.Open
    var _sessionStatus: Status = Status.Open

    var runSideEffect = (_: Int) => false
    var sideEffect = () => ()
    var closeSideEffect = () => ()

    val stubLB = new ServiceFactory[String, String] {
      def apply(conn: ClientConnection) =
        Future.value(new Service[String, String] {
          def apply(request: String): Future[String] = {
            sessionDispatchCount += 1
            if (runSideEffect(sessionDispatchCount)) sideEffect()
            Future.exception(WriteException(new Exception("boom")))
          }

          override def close(deadline: Time) = {
            sessionCloseCount += 1
            closeSideEffect()
            Future.Done
          }

          override def status: Status = _sessionStatus
        })

      def close(deadline: Time) = Future.Done

      override def status = _svcFacStatus
    }

    val sr = new InMemoryStatsReceiver
    val client = baseClient.configured(param.Stats(sr))

    val stk = client.stack.replace(
      LoadBalancerFactory.role,
      (_: ServiceFactory[String, String]) => stubLB
    )

    val cl = client
      .withStack(stk)
      .configured(param.Label("myclient"))
      .newClient("/$/inet/localhost/0")

    def requeues = sr.counters.get(Seq("myclient", "retries", "requeues"))
    def budget = sr.gauges(Seq("myclient", "retries", "budget"))()
  }

  // we get 20% of the budget, which is given 100 minimum retries
  private val DefaultRequeues = 20

  test("requeue failing requests when the stack is Open")(new RequeueCtx {
    val session = cl()
    val b = budget
    // failing request and Open load balancer => max requeues
    await(session.map(_("hi")))
    assert(requeues == Some(DefaultRequeues))
    assert(budget == b - DefaultRequeues)
  })

  for (status <- Seq(Status.Busy, Status.Closed)) {
    test(s"don't requeue failing requests when the stack is $status")(new RequeueCtx {
      // failing request and Busy | Closed load balancer => zero requeues
      _svcFacStatus = status
      await(cl().map(_("hi")))
      assert(requeues == Some(0))
    })
  }

  test("dynamically stop requeuing")(new RequeueCtx {
    // load balancer begins Open, becomes Busy after 10 requeues => 10 requeues
    _svcFacStatus = Status.Open
    runSideEffect = _ > DefaultRequeues
    sideEffect = () => _svcFacStatus = Status.Busy
    await(cl().map(_("hi")))
    assert(requeues == Some(DefaultRequeues))
  })

  test("service acquisition requeues use a separate fixed budget")(new RequeueCtx {
    override val stubLB = new ServiceFactory[String, String] {
      def apply(conn: ClientConnection) = Future.exception(
        Failure.rejected("unable to establish session")
      )
      def close(deadline: Time) = Future.Done
    }

    intercept[Failure] { await(cl()) }
    assert(requeues.isDefined)
    assert(budget > 0)
  })

  test("service acquisition requeues respect FailureFlags.Retryable")(new RequeueCtx {
    override val stubLB = new ServiceFactory[String, String] {
      def apply(conn: ClientConnection) = Future.exception(
        Failure("don't restart this!")
      )
      def close(deadline: Time) = Future.Done
    }

    intercept[Failure] { await(cl()) }

    assert(requeues == Some(0))
    assert(budget > 0)
  })

  test("service acquisition requeues respect Status.Open")(new RequeueCtx {
    _svcFacStatus = Status.Closed
    await(cl())
    assert(requeues == Some(0))
    assert(budget > 0)
  })

  test("service acquisition requeues will close Status.Closed sessions") {
    val ctx = new RequeueCtx { }
    ctx._svcFacStatus = Status.Open
    ctx._sessionStatus = Status.Closed
    ctx.closeSideEffect = () => ctx._sessionStatus = Status.Open
    await(ctx.cl())
    assert(ctx.sessionCloseCount == 1)
  }

  test("Requeues all go to the same cluster in a Union") {
    /*
     * Once we have distributed a request to a particular cluster (in
     * BindingFactory), retries should go to the same cluster rather
     * than being redistributed (possibly to a different cluster).
     */
    class CountFactory extends ServiceFactory[Unit, Unit] {
      var count = 0

      val service = new Service[Unit, Unit] {
        def apply(request: Unit): Future[Unit] = {
          count = count + 1
          Future.exception(WriteException(null))
        }
      }

      def apply(conn: ClientConnection) = Future.value(service)
      def close(deadline: Time) = Future.Done
    }

    val fac1 = new CountFactory
    val fac2 = new CountFactory

    val addr1 = Address(1729)
    val addr2 = Address(1730)

    val baseDtab = Dtab.read("/s=>/test")

    // override name resolution to a Union of two addresses, and check
    // that the base dtab is properly passed in
    NameInterpreter.global = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
        assert(dtab == baseDtab)
        Activity.value(
          NameTree.Union(
            NameTree.Weighted(1D, NameTree.Leaf(Name.bound(addr1))),
            NameTree.Weighted(1D, NameTree.Leaf(Name.bound(addr2)))
          )
        )
      }
    }

    val stack = StackClient
      .newStack[Unit, Unit]

      // direct the two addresses to the two service factories instead
      // of trying to connect to them
      .replace(
        LoadBalancerFactory.role,
        new Stack.Module1[LoadBalancerFactory.Dest, ServiceFactory[Unit, Unit]] {
          val role = new Stack.Role("role")
          val description = "description"
          def make(dest: LoadBalancerFactory.Dest, next: ServiceFactory[Unit, Unit]) = {
            val LoadBalancerFactory.Dest(va) = dest
            va.sample() match {
              case Addr.Bound(addrs, _) if addrs == Set(addr1) => fac1
              case Addr.Bound(addrs, _) if addrs == Set(addr2) => fac2
              case _ => throw new IllegalArgumentException("wat")
            }
          }
        }
      )

    val sr = new InMemoryStatsReceiver

    val service =
      new FactoryToService(
        stack.make(
          Stack.Params.empty +
            FactoryToService.Enabled(true) +
            param.Stats(sr) +
            BindingFactory.BaseDtab(() => baseDtab)
        )
      )

    intercept[Failure] {
      await(service(()))
    }

    val requeues = sr.counters(Seq("retries", "requeues"))

    // all retries go to one service
    assert(
      (fac1.count == requeues + 1 && fac2.count == 0) ||
        (fac2.count == requeues + 1 && fac1.count == 0)
    )
  }

  test("StackBasedClient.configured is a StackClient") {
    // compilation test
    val client: StackBasedClient[String, String] = baseClient
    val client2: StackBasedClient[String, String] =
      client.configured(param.Label("foo"))
    val client3: StackBasedClient[String, String] =
      client.configured[param.Label]((param.Label("foo"), param.Label.param))
  }

  test("StackClient.configured is a StackClient") {
    // compilation test
    val client: StackClient[String, String] = baseClient
    val client2: StackClient[String, String] =
      client.configured(param.Label("foo"))
    val client3: StackClient[String, String] =
      client.configured[param.Label]((param.Label("foo"), param.Label.param))
  }

  test("StackClient binds to a local service via exp.Address.ServiceFactory") {
    val reverser = Service.mk[String, String] { in =>
      Future.value(in.reverse)
    }
    val sf = ServiceFactory(() => Future.value(reverser))
    val addr = exp.Address(sf)
    val name = Name.bound(addr)
    val service = baseClient.newService(name, "sfsa-test")
    val forward = "a man a plan a canal: panama"
    val reversed = await(service(forward))
    assert(reversed == forward.reverse)
  }

  test("filtered composes filters atop the stack") {
    val echoServer = Service.mk[String, String] { in =>
      Future.value(in)
    }
    val sf = ServiceFactory(() => Future.value(echoServer))
    val addr = exp.Address(sf)
    val name = Name.bound(addr)

    val reverseFilter = new SimpleFilter[String, String] {
      def apply(str: String, svc: Service[String, String]) =
        svc(str.reverse)
    }

    val svc = baseClient.filtered(reverseFilter).newService(name, "test_client")
    assert(await(svc("ping")) == "ping".reverse)
  }

  test("endpointer clears Contexts") {
    import StackClientTest._

    val key = new Contexts.local.Key[String]
    Contexts.local.let(key, "SomeCoolContext") {
      val echoSvc = Service.mk[String, String] { Future.value }
      val server =
        StringServer.server.serve(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), echoSvc)
      val ia = server.boundAddress.asInstanceOf[InetSocketAddress]

      val client = new LocalCheckingStringClient(key)
        .newService(Name.bound(Address(ia)), "a-label")

      val result = await(client("abc"))
      assert("abc" == result)
    }
  }

  test("pending request limit is per connection") {
    class CountingService(p: Promise[Unit]) extends Service[Unit, Unit] {
      var pending = new AtomicInteger()
      val satisfied = new AtomicInteger()
      def apply(req: Unit): Future[Unit] = {
        pending.incrementAndGet()
        p.ensure(satisfied.incrementAndGet())
      }
    }

    val (p1, p2) = (new Promise[Unit], new Promise[Unit])

    val (endpoint1, endpoint2) = (new CountingService(p1), new CountingService(p2))
    var first = true

    val stack = StackClient
      .newStack[Unit, Unit]
      .concat(
        Stack.leaf(
          Stack.Role("role"),
          new ServiceFactory[Unit, Unit] {
            def apply(conn: ClientConnection): Future[Service[Unit, Unit]] =
              if (first) {
                first = false
                Future.value(endpoint1)
              } else {
                Future.value(endpoint2)
              }

            def close(deadline: Time): Future[Unit] = Future.Done
          }
        )
      )
      .remove(DefaultPool.Role)

    val sr = new InMemoryStatsReceiver
    val params =
      Stack.Params.empty +
        param.Stats(sr) +
        DefaultPool.Param(
          low = 0,
          high = 2,
          bufferSize = 0,
          idleTime = Duration.Zero,
          maxWaiters = 0
        ) +
        FactoryToService.Enabled(false) +
        PendingRequestFilter.Param(Some(2)) +
        BindingFactory.Dest(Name.Path(Path.read("/$/inet/localhost/0")))

    val svcFac = stack.make(params)
    val session1 = await(svcFac())

    // pending
    val e1r1 = session1(())
    // pending
    val e1r2 = session1(())
    // rejected
    val e1r3 = session1(())

    val e1rejected = intercept[Failure] { await(e1r3) }

    val session2 = await(svcFac())
    // pending
    val e2r1 = session2(())
    // pending
    val e2r2 = session2(())
    // rejected
    val e2r3 = session2(())

    val e2rejected = intercept[Failure] { await(e2r3) }

    // endpoint1 and endpoint2 both only see the first two requests,
    // meaning they get distinct pending request limits
    assert(endpoint1.pending.get() == 2)
    assert(endpoint2.pending.get() == 2)
    assert(endpoint1.satisfied.get() == 0)
    assert(endpoint2.satisfied.get() == 0)
    assert(!e1r1.isDefined)
    assert(!e1r2.isDefined)
    intercept[RejectedExecutionException] { throw e1rejected.cause.get }
    intercept[RejectedExecutionException] { throw e2rejected.cause.get }

    // pending requests are satisfied
    p1.setDone()
    p2.setDone()
    assert(endpoint1.satisfied.get() == 2)
    assert(endpoint2.satisfied.get() == 2)

    // subsequent requests aren't filtered
    val e2r4 = session2(())
    val e2r5 = session2(())
    val e2r6 = session2(())

    await(e2r4)
    await(e2r5)
    await(e2r6)

    assert(endpoint2.satisfied.get() == 5)
  }

  test("exports transporter type to registry") {
    val listeningServer = StringServer.server
      .serve(":*", Service.mk[String, String](Future.value(_)))
    val boundAddress = listeningServer.boundAddress.asInstanceOf[InetSocketAddress]

    val label = "stringClient"
    val svc = baseClient.newService(Name.bound(Address(boundAddress)), label)

    val registry = new SimpleRegistry
    await(GlobalRegistry.withRegistry(registry) {
      svc("hello world")
    })

    val expectedEntry = Entry(
      key = Seq("client", StringClient.protocolLibrary, label, "Transporter"),
      value = transporterName
    )

    assert(registry.iterator.contains(expectedEntry))

    await(listeningServer.close())
    await(svc.close())
  }

  test("Sources exceptions") {
    val listeningServer = StringServer.server
      .serve(":*", Service.mk[String, String](Future.value(_)))
    val boundAddress = listeningServer.boundAddress.asInstanceOf[InetSocketAddress]
    val label = "stringClient"

    val throwsModule = new Stack.Module0[ServiceFactory[String, String]] {
      val role = Stack.Role("Throws")
      val description = "Throws Exception"

      def make(
        next: ServiceFactory[String, String]
      ): ServiceFactory[String, String] =
        (new SimpleFilter[String, String] {
          def apply(
            request: String,
            service: Service[String, String]
          ): Future[String] = Future.exception(new Failure("boom!"))
        }).andThen(next)
    }

    // Insert a module that throws near before [[ExceptionSourceFilter]].
    // We could insert using [[ExceptionSourceFilter]] as the relative insertion point, but we use
    // another module instead so that if the [[ExceptionSourceFilter]] were moved earlier in the
    // stack, this test would fail.
    val svc = baseClient.withStack(baseClient.stack.insertBefore(
      ClearContextValueFilter.role, throwsModule))
      .newService(Name.bound(Address(boundAddress)), label)

    val failure = intercept[Failure] {
      await(svc("hello"))
    }

   assert(failure.toString == "Failure(boom!, flags=0x00) with Service -> stringClient")
  }
}
