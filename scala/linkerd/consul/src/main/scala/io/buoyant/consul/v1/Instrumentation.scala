package io.buoyant.consul.v1

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http
import com.twitter.util.{Return, Throw, Time, Try, Future}
import scala.collection.mutable

/**
 * PollState holds metadata about the calls on consul api.
 * This class is intended to be serialized.
 */
class PollState[Req, Rep] {

  @JsonIgnore
  def recordApiCall(req: Req): Unit = synchronized {
    request = Some(req)
    lastRequestAt = Some(Time.now.toString)
  }

  @JsonIgnore
  def recordResponse(rep: Try[Rep]): Unit = synchronized {
    lastResponseAt = Some(Time.now.toString)
    rep match {
      case Return(r) =>
        response = Some(r)
        error = None
      case Throw(e) =>
        error = Some(e.getMessage)
        response = None
    }
  }

  // These fields exist to be serialized.
  protected var request: Option[Req] = None
  protected var lastRequestAt: Option[String] = None
  protected var response: Option[Rep] = None
  protected var lastResponseAt: Option[String] = None
  protected var error: Option[String] = None
}

object InstrumentedApiCall {

  //specific for handling requests recorded as string
  def mkPollState[Rep]: PollState[String, Rep] = new PollState

  def execute[Rep](call: ApiCall[Rep], pollWatch: PollState[String, Rep]): Future[Rep] = {
    pollWatch.recordApiCall(capture(call.req))
    val f = call()
    f.respond(pollWatch.recordResponse)
    f
  }

  private[this] def capture(req: http.Request): String =
    s"${req.method} ${req.uri}"

}