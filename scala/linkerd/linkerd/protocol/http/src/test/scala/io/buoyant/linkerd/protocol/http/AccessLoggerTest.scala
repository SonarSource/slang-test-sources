package io.buoyant.linkerd.protocol.http

import java.util.{TimeZone, logging => javalog}
import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.logging._
import com.twitter.util.{Future, Promise, Time, TimeFormat}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class AccessLoggerTest extends FunSuite with Awaits {
  object StringLogger extends Logger("string", javalog.Logger.getAnonymousLogger()) {
    val handler = new StringHandler(new Formatter {
      override def format(record: javalog.LogRecord): String = formatText(record)
    }, None)

    clearHandlers()
    addHandler(handler)
    setLevel(Level.INFO)

    def getLoggedLines(): String = handler.get
  }

  test("access logger filter") {
    val done = new Promise[Unit]
    // This timestamp is: Wed, 06 Jan 2016 21:21:26 GMT
    Time.withTimeAt(Time.fromSeconds(1452115286)) { tc =>
      val timeFormat = new TimeFormat("dd/MM/yyyy:HH:mm:ss z", TimeZone.getDefault)
      val service = AccessLogger(StringLogger, timeFormat) andThen Service.mk[Request, Response] { req =>
        val rsp = Response()
        rsp.status = Status.PaymentRequired
        rsp.contentType = "application/json"
        rsp.contentLength = 304374
        Future.value(rsp)
      }

      val req = Request()
      req.method = Method.Head
      req.uri = "/foo?bar=bah"
      req.host = "monkeys"
      req.contentType = "text/plain"

      val f = service(req)
      assert(
        StringLogger.getLoggedLines() ==
          """monkeys 0.0.0.0 - - [06/01/2016:21:21:26 GMT] "HEAD /foo?bar=bah HTTP/1.1" 402 304374 "-" "-""""
      )
    }
  }

  override protected def withFixture(test: NoArgTest) = {
    val currentTimezone = TimeZone.getDefault
    TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
    try {
      super.withFixture(test)
    } finally {
      TimeZone.setDefault(currentTimezone)
    }
  }
}
