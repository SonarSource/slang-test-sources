/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package controllers.b

import play.api.mvc._
import javax.inject.Inject

class B @Inject()(c: ControllerComponents) extends AbstractController(c) {

  def index = Action {
    controllers.a.routes.A.index
    controllers.b.routes.B.index
    controllers.c.routes.C.index
    Ok
  }

}
