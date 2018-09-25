package org.http4s.client.blaze

import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.{Disconnect, EOF, Error, OutboundCommand}
import org.http4s.blaze.util.{Cancellable, TickWheelExecutor}
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

final private[blaze] class ClientTimeoutStage(
    responseHeaderTimeout: Duration,
    idleTimeout: Duration,
    requestTimeout: Duration,
    exec: TickWheelExecutor)
    extends MidStage[ByteBuffer, ByteBuffer] { stage =>

  import ClientTimeoutStage._

  private implicit val ec = org.http4s.blaze.util.Execution.directec

  // The timeout between request body completion and response header
  // completion.
  private val activeResponseHeaderTimeout = new AtomicReference[Cancellable](null)

  // The total timeout for the request. Lasts the lifetime of the stage.
  private val activeReqTimeout = new AtomicReference[Cancellable](null)

  // The timeoutState contains a Cancellable, null, or a TimeoutException
  // It will also act as the point of synchronization
  private val timeoutState = new AtomicReference[AnyRef](null)

  override def name: String =
    s"ClientTimeoutStage: Response Header: $responseHeaderTimeout, Idle: $idleTimeout, Request: $requestTimeout"

  /////////// Private impl bits //////////////////////////////////////////
  private def killswitch(name: String, timeout: Duration) = new Runnable {
    override def run(): Unit = {
      logger.debug(s"Client stage is disconnecting due to $name timeout after $timeout.")

      // check the idle timeout conditions
      timeoutState.getAndSet(new TimeoutException(s"Client $name timeout after $timeout.")) match {
        case null => // noop
        case c: Cancellable => c.cancel() // this should be the registration of us
        case _: TimeoutException => // Interesting that we got here.
      }

      cancelResponseHeaderTimeout()

      // Cancel the active request timeout if it exists
      activeReqTimeout.getAndSet(Closed) match {
        case null =>
          /* We beat the startup. Maybe timeout is 0? */
          sendOutboundCommand(Disconnect)

        case Closed => /* Already closed, no need to disconnect */

        case timeout =>
          timeout.cancel()
          sendOutboundCommand(Disconnect)
      }
    }
  }

  private val responseHeaderTimeoutKillswitch = killswitch("response header", responseHeaderTimeout)
  private val idleTimeoutKillswitch = killswitch("idle", idleTimeout)
  private val requestTimeoutKillswitch = killswitch("request", requestTimeout)

  // Startup on creation

  /////////// Pass through implementations ////////////////////////////////

  def initialize(): Unit = stageStartup()

  override def readRequest(size: Int): Future[ByteBuffer] =
    checkTimeout(channelRead(size))

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    checkTimeout(channelWrite(data))

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] =
    checkTimeout(channelWrite(data))

  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    // We want to swallow `TimeoutException`'s we have created
    case Error(t: TimeoutException) if t eq timeoutState.get() =>
      sendOutboundCommand(Disconnect)

    case RequestSendComplete =>
      activateResponseHeaderTimeout()

    case ResponseHeaderComplete =>
      cancelResponseHeaderTimeout()

    case cmd => super.outboundCommand(cmd)
  }

  /////////// Protected impl bits //////////////////////////////////////////

  override protected def stageShutdown(): Unit = {
    cancelTimeout()
    activeReqTimeout.getAndSet(Closed) match {
      case null => logger.error("Shouldn't get here.")
      case timeout => timeout.cancel()
    }
    super.stageShutdown()
  }

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    val timeout = exec.schedule(requestTimeoutKillswitch, ec, requestTimeout)
    if (!activeReqTimeout.compareAndSet(null, timeout)) {
      activeReqTimeout.get() match {
        case Closed => // NOOP: the timeout already triggered
        case _ => logger.error("Shouldn't get here.")
      }
    } else resetTimeout()
  }

  /////////// Private stuff ////////////////////////////////////////////////

  def checkTimeout[T](f: Future[T]): Future[T] = {
    val p = Promise[T]

    f.onComplete {
      case s @ Success(_) =>
        resetTimeout()
        p.tryComplete(s)

      case eof @ Failure(EOF) =>
        timeoutState.get() match {
          case t: TimeoutException => p.tryFailure(t)
          case c: Cancellable =>
            c.cancel()
            p.tryComplete(eof)

          case null => p.tryComplete(eof)
        }

      case v @ Failure(_) => p.complete(v)
    }

    p.future
  }

  private def setAndCancel(next: Cancellable): Unit = {
    @tailrec
    def go(): Unit = timeoutState.get() match {
      case null =>
        if (!timeoutState.compareAndSet(null, next)) go()

      case c: Cancellable =>
        if (!timeoutState.compareAndSet(c, next)) go()
        else c.cancel()

      case _: TimeoutException =>
        if (next != null) next.cancel()
    }; go()
  }

  private def resetTimeout(): Unit =
    setAndCancel(exec.schedule(idleTimeoutKillswitch, idleTimeout))

  private def cancelTimeout(): Unit = setAndCancel(null)

  private def activateResponseHeaderTimeout(): Unit = {
    val timeout = exec.schedule(responseHeaderTimeoutKillswitch, ec, responseHeaderTimeout)
    if (!activeResponseHeaderTimeout.compareAndSet(null, timeout))
      timeout.cancel()
  }

  private def cancelResponseHeaderTimeout(): Unit =
    activeResponseHeaderTimeout.getAndSet(Closed) match {
      case null => // no-op
      case timeout => timeout.cancel()
    }
}

private[blaze] object ClientTimeoutStage {
  // Sent when we have sent the complete request
  private[blaze] object RequestSendComplete extends OutboundCommand

  // Sent when we have received the complete response
  private[blaze] object ResponseHeaderComplete extends OutboundCommand

  // Make sure we have our own _stable_ copy for synchronization purposes
  private val Closed = new Cancellable {
    def cancel() = ()
    override def toString = "Closed"
  }
}
