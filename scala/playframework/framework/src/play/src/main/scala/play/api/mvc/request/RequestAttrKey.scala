/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.mvc.request

import play.api.libs.typedmap.TypedKey
import play.api.mvc.{ Cookies, Flash, Session }

/**
 * Keys to request attributes.
 */
object RequestAttrKey {

  /**
   * The key for the request attribute storing a request id.
   */
  val Id = TypedKey[Long]("Id")

  /**
   * The key for the request attribute storing a [[Cell]] with
   * [[play.api.mvc.Cookies]] in it.
   */
  val Cookies = TypedKey[Cell[Cookies]]("Cookies")

  /**
   * The key for the request attribute storing a [[Cell]] with
   * the [[play.api.mvc.Session]] cookie in it.
   */
  val Session = TypedKey[Cell[Session]]("Session")

  /**
   * The key for the request attribute storing a [[Cell]] with
   * the [[play.api.mvc.Flash]] cookie in it.
   */
  val Flash = TypedKey[Cell[Flash]]("Flash")

  /**
   * The key for the request attribute storing the request's
   * tags.
   */
  @deprecated("Use attributes instead of tags", "2.6.0")
  val Tags = TypedKey[Map[String, String]]("Tags")

  /**
   * The key for the request attribute storing the server name.
   */
  val Server = TypedKey[String]("Server-Name")
}
