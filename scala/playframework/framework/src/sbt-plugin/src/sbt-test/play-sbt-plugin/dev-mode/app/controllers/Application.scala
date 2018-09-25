/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package controllers

import play.api.mvc._
import javax.inject.Inject

class Application @Inject() (c: ControllerComponents) extends AbstractController(c) {
  def index = Action {
    Ok("original")
  }
}
