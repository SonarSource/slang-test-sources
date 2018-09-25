/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package controllers

import play.api._
import play.api.mvc._
import scala.collection.JavaConverters._

import javax.inject.Inject

class Application @Inject() (env: Environment, configuration: Configuration, c: ControllerComponents) extends AbstractController(c) {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def config = Action {
    Ok(configuration.underlying.getString("some.config"))
  }

  def count = Action {
    val num = env.resource("application.conf").toSeq.size
    Ok(num.toString)
  }
}
