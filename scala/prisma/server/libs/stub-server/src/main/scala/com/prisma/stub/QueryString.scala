package com.prisma.stub

import io.lemonlabs.uri.{Uri, Url}

import scala.collection.SortedMap

object QueryString {
  def queryStringToMap(asNullableString: String): Map[String, String] = {
    Option(asNullableString) match {
      case Some(string) =>
        Map(Url.parse(s"?$string").query.params: _*).filterKeys(_.trim != "").mapValues(_.getOrElse(""))
      case None =>
        Map.empty
    }

  }

  def queryMapToString(queryMap: Map[String, Any]): String = {
    queryMap.isEmpty match {
      case false => "?" + queryMap.map { case (k, v) => s"$k=$v" }.mkString("&")
      case true  => ""
    }
  }

  def mapToSortedMap(map: Map[String, Any]): SortedMap[String, Any] = {
    SortedMap(map.toSeq: _*)
  }
}
