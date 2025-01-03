/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.core.j

import javax.inject.Inject

import play.mvc.Http.RequestHeader
import play.routing.Router.RouteDocumentation

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

/**
 * Adapts the Scala router to the Java Router API
 */
class JavaRouterAdapter @Inject() (underlying: play.api.routing.Router) extends play.routing.Router {
  def route(requestHeader: RequestHeader) = underlying.handlerFor(requestHeader.asScala()).asJava
  def withPrefix(prefix: String) = new JavaRouterAdapter(asScala.withPrefix(prefix))
  def documentation() = asScala.documentation.map {
    case (httpMethod, pathPattern, controllerMethodInvocation) =>
      new RouteDocumentation(httpMethod, pathPattern, controllerMethodInvocation)
  }.asJava
  override def asScala = underlying
}
