package com.twitter.finagle.integration

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle._
import com.twitter.finagle.pushsession.utils.MockChannelHandle
import com.twitter.finagle.pushsession.{PipeliningClientPushSession, PushChannelHandle}
import com.twitter.finagle.http
import com.twitter.finagle.memcached.{protocol => memcached}
import com.twitter.finagle.http.codec.HttpClientDispatcher
import com.twitter.finagle.http.exp.IdentityStreamTransport
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.mux.pushsession.{MessageWriter, MuxMessageDecoder}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.{QueueTransport, Transport}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.{Buf, ByteReader}
import com.twitter.util._
import com.twitter.util.TimeConversions._
import org.mockito.Mockito.when
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

/**
 * We want client session statuses to reflect the status of their underlying transports/handles
 */
class ClientSessionTest extends FunSuite with MockitoSugar {

  def testSessionStatus[Req, Rep](
    name: String,
    sessionFac: (Transport[Req, Rep]) => () => Status
  ): Unit = {

    test(s"$name: session status reflects underlying transport") {
      val clientToServer = new AsyncQueue[Req]
      val serverToClient = new AsyncQueue[Rep]

      val transport: Transport[Req, Rep] =
        new QueueTransport(writeq = clientToServer, readq = serverToClient)

      val sessionStatus = sessionFac(transport)
      assert(sessionStatus() == Status.Open)

      Await.ready(transport.close(), 5.seconds)
      assert(sessionStatus() == Status.Closed)
    }
  }

  def testPushSessionStatus[In, Out](
    name: String,
    pushSessionFac: (PushChannelHandle[In, Out]) => () => Status
  ): Unit = {

    test(s"$name: session status reflects underlying handle") {
      val handle = new MockChannelHandle[In, Out]

      val sessionStatus = pushSessionFac(handle)
      assert(sessionStatus() == Status.Open)

      handle.status = Status.Closed
      assert(sessionStatus() == Status.Closed)
    }
  }

  testPushSessionStatus(
    "mux-dispatcher", { handle: PushChannelHandle[ByteReader, Buf] =>
      val session = new mux.pushsession.MuxClientSession(
        handle = handle,
        h_decoder = mock[MuxMessageDecoder],
        h_messageWriter = mock[MessageWriter],
        detectorConfig = FailureDetector.NullConfig,
        name = "test-dispatcher",
        statsReceiver = new InMemoryStatsReceiver,
        timer = mock[Timer]
      )


      () =>
        session.status
    }
  )

  testSessionStatus(
    "http-transport", { tr: Transport[Any, Any] =>
      val manager = mock[http.codec.ConnectionManager]
      val closeP = new Promise[Unit]
      when(manager.shouldClose).thenReturn(false)
      when(manager.onClose).thenReturn(closeP)
      val wrappedT = new http.HttpTransport(
        new IdentityStreamTransport(Transport.cast[http.Request, http.Response](tr)),
        manager
      )
      () =>
        wrappedT.status
    }
  )

  testSessionStatus(
    "http-dispatcher", { tr: Transport[Any, Any] =>
      val dispatcher = new HttpClientDispatcher(
        new IdentityStreamTransport(Transport.cast[http.Request, http.Response](tr)),
        NullStatsReceiver
      )
      () =>
        dispatcher.status
    }
  )

  class MyPushClient extends com.twitter.finagle.Memcached.Client {
    def toSvc(
      handle: PushChannelHandle[memcached.Response, memcached.Command]
    ): Service[memcached.Command, memcached.Response] = {
      val session = new PipeliningClientPushSession[memcached.Response, memcached.Command](
        handle,
        NullStatsReceiver,
        Duration.Top,
        DefaultTimer
      )
      Await.result(toService(session), 5.seconds)
    }
  }

  testPushSessionStatus(
    "memcached-session", { handle: PushChannelHandle[memcached.Response, memcached.Command] =>
      val cl: MyPushClient = new MyPushClient
      val svc = cl.toSvc(handle)
      () =>
        svc.status
    }
  )

  testSessionStatus(
    "mysql-dispatcher", { tr: Transport[mysql.transport.Packet, mysql.transport.Packet] =>
      val handshake = mysql.Handshake(Some("username"), Some("password"))
      val dispatcher = new mysql.ClientDispatcher(tr, handshake, false)
      () =>
        dispatcher.status
    }
  )
}
