package com.twitter.finagle.pushsession

import com.twitter.conversions.time._
import com.twitter.finagle.pushsession.utils.MockChannelHandle
import com.twitter.finagle.server.StackServer
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{ClientConnection, ListeningServer, Service, ServiceFactory, Stack, Status}
import com.twitter.util.registry.{Entry, GlobalRegistry}
import com.twitter.util.{Await, Awaitable, Duration, Future, Promise, Time}
import java.net.{InetSocketAddress, SocketAddress}
import java.security.cert.Certificate
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

class PushStackServerTest extends FunSuite with MockitoSugar {

  private def await[T](t: Awaitable[T]): T = Await.result(t, 5.seconds)

  class MockSession(handle: PushChannelHandle[Unit, Unit]) extends PushSession[Unit, Unit](handle) {

    @volatile
    var closeCalled: Boolean = false
    val closePromise: Promise[Unit] = Promise[Unit]()

    def receive(message: Unit): Unit = ???

    def status: Status = ???

    def close(deadline: Time): Future[Unit] = {
      if (closeCalled) sys.error("Multiple close calls")
      else {
        closeCalled = true
        closePromise
      }
    }
  }

  // Note: only intended to handle one connection at a time due to storing
  //       per-listen call data in the parent instance.
  class MockPushListener extends PushListener[Unit, Unit] {

    override def toString: String = "MockPushListener"

    type SessionBuilder = PushChannelHandle[Unit, Unit] => Future[PushSession[Unit, Unit]]

    @volatile var closeCalled: Boolean = false
    @volatile var builder: SessionBuilder = null

    def listen(addr: SocketAddress)(sessionBuilder: SessionBuilder): ListeningServer =
      new ListeningServer {
        builder = sessionBuilder

        protected def closeServer(deadline: Time): Future[Unit] = {
          if (closeCalled) sys.error("unexpected double close")
          else {
            closeCalled = true
            Future.Unit
          }
        }

        def boundAddress: SocketAddress = addr

        def isReady(implicit permit: Awaitable.CanAwait): Boolean = true

        def result(timeout: Duration)
          (implicit permit: Awaitable.CanAwait): Unit = ()

        def ready(timeout: Duration)
          (implicit permit: Awaitable.CanAwait): this.type = this
      }
  }

  // Note: only intended to listen to one address at a time due to mutating copy1 method
  case class TestStackServer(
    var stack: Stack[ServiceFactory[Unit, Unit]] = StackServer.newStack,
    var params: Stack.Params = StackServer.defaultParams
  ) extends PushStackServer[Unit, Unit, TestStackServer] {
    protected type PipelineRep = Unit
    protected type PipelineReq = Unit

    lazy val mockListener: MockPushListener = new MockPushListener

    protected def newListener(): PushListener[PipelineReq, PipelineRep] = mockListener

    override protected def newSession(
      handle: PushChannelHandle[PipelineReq, PipelineRep],
      service: Service[Unit, Unit]
    ): PushSession[PipelineReq, PipelineRep] = new MockSession(handle)

    protected def copy1(
      stack: Stack[ServiceFactory[Unit, Unit]],
      params: Stack.Params
    ): TestStackServer = {
      // we mutate instead of copy or we lose our handle on the inner mocks
      this.stack = stack
      this.params = params
      this
    }
  }

  test("Close notifies the channel handle if the server is closing when it becomes ready") {
    val server = new TestStackServer()
    val service = Promise[Service[Unit, Unit]]
    val listeningServer = server.serve(
      new InetSocketAddress(0), ServiceFactory.apply(() => service))
    val handle = new MockChannelHandle[Unit, Unit](null)
    val sessionF = server.mockListener.builder(handle)
    listeningServer.close(10.seconds)

    assert(!sessionF.isDefined)
    assert(server.mockListener.closeCalled)

    service.setValue(Service.const(Future.Unit))
    val session = await(sessionF)

    session match {
      case s: MockSession => assert(s.closeCalled)
      case other => fail(s"Unexpected type: $other")
    }
  }

  test("Close notifies the channel handle if the server is closing") {
    val server = new TestStackServer()
    val listeningServer = server.serve(new InetSocketAddress(0), Service.const(Future.Unit))
    val handle = new MockChannelHandle[Unit, Unit](null)
    val session = await(server.mockListener.builder(handle))
    val closeF = listeningServer.close(10.seconds)

    assert(server.mockListener.closeCalled)
    assert(!closeF.isDefined)

    session match {
      case s: MockSession =>
        assert(s.closeCalled)
        s.closePromise.setDone()

      case other =>
        fail(s"Unexpected type: $other")
    }

    await(closeF) // should resolve now that the session has closed
  }

  test("automatically closes handle on ServiceFactory failure") {
    val server = new TestStackServer()
    server.serve(new InetSocketAddress(0),
      ServiceFactory(() => Future.exception(new Exception("Sad face"))))

    val handle = new MockChannelHandle[Unit, Unit](null)
    val session = await(server.mockListener.builder(handle))

    session match {
      case s: MockSession => assert(s.closeCalled)
      case other => fail(s"Unexpected type: $other")
    }
  }

  test("peer certificate is available to service factory") {
    val server = new TestStackServer()
    object TestServiceFactory extends ServiceFactory[Unit, Unit] {
      @volatile var cert: Option[Certificate] = null
      def apply(conn: ClientConnection): Future[Service[Unit, Unit]] = {
        cert = Transport.peerCertificate
        Future.value(Service.const(Future.Unit))
      }

      def close(deadline: Time): Future[Unit] = Future.Unit
    }

    server.serve(new InetSocketAddress(0), TestServiceFactory)
    val mockCert = mock[Certificate]
    val handle = new MockChannelHandle[Unit, Unit](null) {
      override def peerCertificate: Option[Certificate] = Some(mockCert)
    }

    await(server.mockListener.builder(handle))

    assert(TestServiceFactory.cert == Some(mockCert))
  }

  test("inserts an entry into the registry") {
    val server = new TestStackServer()

    // Should have registered itself
    server.serve(new InetSocketAddress(0),
      ServiceFactory(() => Future.exception(new Exception("Sad face"))))

    val entries = GlobalRegistry.get.iterator.toList
    val foundEntry = entries.exists {
      case Entry(key, "MockPushListener") => key.lastOption == Some("Listener")
      case _ => false
    }

    assert(foundEntry)
  }
}
