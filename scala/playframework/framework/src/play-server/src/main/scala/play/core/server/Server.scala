/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.core.server

import java.util.function.{ Function => JFunction }

import com.typesafe.config.ConfigFactory
import play.api.ApplicationLoader.Context
import play.api.http.{ DefaultHttpErrorHandler, HttpErrorHandler, Port }
import play.api.routing.Router
import play.api._
import play.api.mvc._
import play.core.{ ApplicationProvider, DefaultWebCommands, SourceMapper, WebCommands }
import play.api.inject.{ ApplicationLifecycle, DefaultApplicationLifecycle }
import play.routing.{ Router => JRouter }
import play.{ ApplicationLoader => JApplicationLoader }
import play.{ BuiltInComponents => JBuiltInComponents }
import play.{ BuiltInComponentsFromContext => JBuiltInComponentsFromContext }

import scala.util.{ Failure, Success }
import scala.concurrent.Future
import scala.language.postfixOps

trait WebSocketable {
  def getHeader(header: String): String
  def check: Boolean
}

/**
 * Provides generic server behaviour for Play applications.
 */
trait Server extends ReloadableServer {

  def mode: Mode

  /**
   * Try to get the handler for a request and return it as a `Right`. If we
   * can't get the handler for some reason then return a result immediately
   * as a `Left`. Reasons to return a `Left` value:
   *
   * - If there's a "web command" installed that intercepts the request.
   * - If we fail to get the `Application` from the `applicationProvider`,
   *   i.e. if there's an error loading the application.
   * - If an exception is thrown.
   *
   * NOTE: This will use the ApplicationProvider of the server to get the application instance.
   *       Use {@code Server.getHandlerFor(request, provider)} to pass a specific application instance
   */
  @deprecated("Use Server.getHandlerFor instead", "2.6.13")
  def getHandlerFor(request: RequestHeader): Either[Future[Result], (RequestHeader, Handler, Application)] = {
    val appProvider = applicationProvider
    val result = Server.getHandlerFor(request, appProvider)
    result.right.map { case (rh, h) => (rh, h, appProvider.get.get) }
  }

  def applicationProvider: ApplicationProvider

  def reload(): Unit = applicationProvider.get

  def stop(): Unit = {
    applicationProvider.current.foreach { app =>
      LoggerConfigurator(app.classloader).foreach(_.shutdown())
    }
  }

  /**
   * Returns the HTTP port of the server.
   *
   * This is useful when the port number has been automatically selected (by setting a port number of 0).
   *
   * @return The HTTP port the server is bound to, if the HTTP connector is enabled.
   */
  def httpPort: Option[Int]

  /**
   * Returns the HTTPS port of the server.
   *
   * This is useful when the port number has been automatically selected (by setting a port number of 0).
   *
   * @return The HTTPS port the server is bound to, if the HTTPS connector is enabled.
   */
  def httpsPort: Option[Int]

}

/**
 * Utilities for creating a server that runs around a block of code.
 */
object Server {

  /**
   * Try to get the handler for a request and return it as a `Right`. If we
   * can't get the handler for some reason then return a result immediately
   * as a `Left`. Reasons to return a `Left` value:
   *
   * - If there's a "web command" installed that intercepts the request.
   * - If we fail to get the `Application` from the `applicationProvider`,
   *   i.e. if there's an error loading the application.
   * - If an exception is thrown.
   */
  private[server] def getHandlerFor(
    request: RequestHeader,
    applicationProvider: ApplicationProvider
  ): Either[Future[Result], (RequestHeader, Handler)] = {

    def handleErrors(errorHandler: HttpErrorHandler): Throwable => Left[Future[Result], Nothing] = {
      case e: ThreadDeath => throw e
      case e: VirtualMachineError => throw e
      case e: Throwable =>
        Left(errorHandler.onServerError(request, e))
    }

    try {
      applicationProvider.handleWebCommand(request) match {
        case Some(result) =>
          // The ApplicationProvider handled the result
          Left(Future.successful(result))
        case None =>
          // The ApplicationProvider didn't handle the result, so try
          // handling it with the Application
          applicationProvider.get match {
            case Success(application) =>
              // We managed to get an Application, now make a fresh request
              // using the Application's RequestFactory, then use the Application's
              // logic to handle that request.
              try {
                val factoryMadeHeader: RequestHeader = application.requestFactory.copyRequestHeader(request)
                val (handlerHeader, handler) = application.requestHandler.handlerForRequest(factoryMadeHeader)
                Right((handlerHeader, handler))
              } catch {
                case e: Throwable => handleErrors(application.errorHandler)(e)
              }
            case Failure(e) =>
              // The ApplicationProvider couldn't give us an application.
              // This usually means there was a compile error or a problem
              // starting the application.
              handleErrors(DefaultHttpErrorHandler)(e)
          }
      }
    } catch {
      case e: Throwable => handleErrors(DefaultHttpErrorHandler)(e)
    }
  }

  /**
   * Run a block of code with a server for the given application.
   *
   * The passed in block takes the port that the application is running on. By default, this will be a random ephemeral
   * port. This can be changed by passing in an explicit port with the config parameter.
   *
   * @param application The application for the server to server.
   * @param config The configuration for the server. Defaults to test config with the http port bound to a random
   *               ephemeral port.
   * @param block The block of code to run.
   * @param provider The server provider.
   * @return The result of the block of code.
   */
  def withApplication[T](application: Application, config: ServerConfig = ServerConfig(port = Some(0), mode = Mode.Test))(block: Port => T)(implicit provider: ServerProvider): T = {
    Play.start(application)
    val server = provider.createServer(config, application)
    try {
      block(new Port((server.httpPort orElse server.httpsPort).get))
    } finally {
      server.stop()
    }
  }

  /**
   * Run a block of code with a server for the given routes.
   *
   * The passed in block takes the port that the application is running on. By default, this will be a random ephemeral
   * port. This can be changed by passing in an explicit port with the config parameter.
   *
   * @param routes The routes for the server to server.
   * @param config The configuration for the server. Defaults to test config with the http port bound to a random
   *               ephemeral port.
   * @param block The block of code to run.
   * @param provider The server provider.
   * @return The result of the block of code.
   */
  def withRouter[T](config: ServerConfig = ServerConfig(port = Some(0), mode = Mode.Test))(routes: PartialFunction[RequestHeader, Handler])(block: Port => T)(implicit provider: ServerProvider): T = {
    val context = ApplicationLoader.Context(
      Environment.simple(path = config.rootDir, mode = config.mode),
      None, new DefaultWebCommands(), Configuration(ConfigFactory.load()),
      new DefaultApplicationLifecycle
    )
    val application = new BuiltInComponentsFromContext(context) with NoHttpFiltersComponents {
      def router = Router.from(routes)
    }.application
    withApplication(application, config)(block)
  }

  /**
   * Run a block of code with a server for the given routes, obtained from the application components
   *
   * The passed in block takes the port that the application is running on. By default, this will be a random ephemeral
   * port. This can be changed by passing in an explicit port with the config parameter.
   *
   * @param routes A function that obtains the routes from the server from the application components.
   * @param config The configuration for the server. Defaults to test config with the http port bound to a random
   *               ephemeral port.
   * @param block The block of code to run.
   * @param provider The server provider.
   * @return The result of the block of code.
   */
  def withRouterFromComponents[T](config: ServerConfig = ServerConfig(port = Some(0), mode = Mode.Test))(routes: BuiltInComponents => PartialFunction[RequestHeader, Handler])(block: Port => T)(implicit provider: ServerProvider): T = {
    val application = new BuiltInComponentsFromContext(ApplicationLoader.Context(
      Environment.simple(path = config.rootDir, mode = config.mode),
      None, new DefaultWebCommands(), Configuration(ConfigFactory.load()),
      new DefaultApplicationLifecycle
    )) with NoHttpFiltersComponents { self: BuiltInComponents =>
      def router = Router.from(routes(self))
    }.application
    withApplication(application, config)(block)
  }

  /**
   * Run a block of code with a server for the application containing routes.
   *
   * The passed in block takes the port that the application is running on. By default, this will be a random ephemeral
   * port. This can be changed by passing in an explicit port with the config parameter.
   *
   * An easy way to set up an application with given routes is to use [[play.api.BuiltInComponentsFromContext]] with
   * any extra components needed:
   *
   * {{{
   *   Server.withApplicationFromContext(ServerConfig(mode = Mode.Prod, port = Some(0))) { context =>
   *     new BuiltInComponentsFromContext(context) with AssetsComponents with play.filters.HttpFiltersComponents {
   *      override def router: Router = Router.from {
   *        case req => assets.versioned("/testassets", req.path)
   *      }
   *    }.application
   *  } { withClient(block)(_) }
   * }}}
   *
   * @param appProducer A function that takes an ApplicationLoader.Context and produces [[play.api.Application]]
   * @param config The configuration for the server. Defaults to test config with the http port bound to a random
   *               ephemeral port.
   * @param block The block of code to run.
   * @param provider The server provider.
   * @return The result of the block of code.
   */
  def withApplicationFromContext[T](config: ServerConfig = ServerConfig(port = Some(0), mode = Mode.Test))(appProducer: ApplicationLoader.Context => Application)(block: Port => T)(implicit provider: ServerProvider): T = {
    val context: Context = ApplicationLoader.Context(
      Environment.simple(path = config.rootDir, mode = config.mode),
      None, new DefaultWebCommands(), Configuration(ConfigFactory.load()),
      new DefaultApplicationLifecycle
    )
    withApplication(appProducer(context), config)(block)
  }

}

/**
 * Components to create a Server instance.
 */
trait ServerComponents {

  def server: Server

  lazy val serverConfig: ServerConfig = ServerConfig()

  lazy val environment: Environment = Environment.simple(mode = serverConfig.mode)
  lazy val sourceMapper: Option[SourceMapper] = None
  lazy val webCommands: WebCommands = new DefaultWebCommands
  lazy val configuration: Configuration = Configuration(ConfigFactory.load())
  lazy val applicationLifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle

  def serverStopHook: () => Future[Unit] = () => Future.successful(())
}

/**
 * Define how to create a Server from a Router.
 */
private[server] trait ServerFromRouter {

  protected def createServerFromRouter(serverConfig: ServerConfig = ServerConfig())(routes: ServerComponents with BuiltInComponents => Router): Server

  /**
   * Creates a [[Server]] from the given router.
   *
   * @param config the server configuration
   * @param routes the routes definitions
   * @return an AkkaHttpServer instance
   */
  def fromRouter(config: ServerConfig = ServerConfig())(routes: PartialFunction[RequestHeader, Handler]): Server = {
    createServerFromRouter(config) { _ => Router.from(routes) }
  }

  /**
   * Creates a [[Server]] from the given router, using [[ServerComponents]].
   *
   * @param config the server configuration
   * @param routes the routes definitions
   * @return an AkkaHttpServer instance
   */
  def fromRouterWithComponents(config: ServerConfig = ServerConfig())(routes: BuiltInComponents => PartialFunction[RequestHeader, Handler]): Server = {
    createServerFromRouter(config)(components => Router.from(routes(components)))
  }
}

private[play] object JavaServerHelper {
  def forRouter(router: JRouter, mode: Mode, httpPort: Option[Integer], sslPort: Option[Integer]): Server = {
    forRouter(mode, httpPort, sslPort)(new JFunction[JBuiltInComponents, JRouter] {
      override def apply(components: JBuiltInComponents): JRouter = router
    })
  }

  def forRouter(mode: Mode, httpPort: Option[Integer], sslPort: Option[Integer])(block: JFunction[JBuiltInComponents, JRouter]): Server = {
    val context = JApplicationLoader.create(Environment.simple(mode = mode).asJava)
    val application = new JBuiltInComponentsFromContext(context) {
      override def router: JRouter = block.apply(this)
      override def httpFilters(): java.util.List[play.mvc.EssentialFilter] = java.util.Collections.emptyList()
    }.application.asScala()
    Play.start(application)
    val serverConfig = ServerConfig(mode = mode, port = httpPort.map(_.intValue), sslPort = sslPort.map(_.intValue))
    implicitly[ServerProvider].createServer(serverConfig, application)
  }
}
