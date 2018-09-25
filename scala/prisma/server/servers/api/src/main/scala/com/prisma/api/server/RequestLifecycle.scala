package com.prisma.api.server

import com.prisma.api.schema.APIErrors.VariablesParsingError
import com.prisma.api.schema.ApiUserContext
import com.prisma.api.schema.CommonErrors.InputCompletelyMalformed
import com.prisma.cache.Cache
import com.prisma.shared.models.Project
import com.prisma.utils.`try`.TryUtil
import play.api.libs.json._
import sangria.ast.Document
import sangria.parser.QueryParser
import sangria.schema.Schema

import scala.util.{Failure, Success, Try}

trait RawRequestAttributes {
  val id: String
  val json: JsValue
  val ip: String
}

case class RawRequest(
    id: String,
    json: JsValue,
    ip: String,
    authorizationHeader: Option[String]
) extends RawRequestAttributes {

  def toGraphQlRequest(
      project: Project,
      schema: Schema[ApiUserContext, Unit]
  ): Try[GraphQlRequest] = {
    val queries: Try[Vector[GraphQlQuery]] = TryUtil.sequence {
      json match {
        case JsArray(requests) => requests.map(GraphQlQuery.tryFromJson).toVector
        case request: JsObject => Vector(GraphQlQuery.tryFromJson(request))
        case malformed         => Vector(Failure(InputCompletelyMalformed(malformed.toString)))
      }
    }
    val isBatch = json match {
      case JsArray(_) => true
      case _          => false
    }
    queries
      .map { queries =>
        GraphQlRequest(
          id = id,
          ip = ip,
          json = json,
          project = project,
          schema = schema,
          queries = queries,
          isBatch = isBatch
        )
      }
      .recoverWith {
        case exception => Failure(InvalidGraphQlRequest(exception))
      }
  }
}
case class InvalidGraphQlRequest(underlying: Throwable) extends Exception
// To support Apollos transport-level query batching we treat input and output as a list
// If multiple queries are supplied they are all executed individually and in parallel
// See
// https://dev-blog.apollodata.com/query-batching-in-apollo-63acfd859862#.g733sm6bj
// https://github.com/apollostack/graphql-server/blob/master/packages/graphql-server-core/src/runHttpQuery.ts#L69

case class GraphQlRequest(
    id: String,
    json: JsValue,
    ip: String,
    project: Project,
    schema: Schema[ApiUserContext, Unit],
    queries: Vector[GraphQlQuery],
    isBatch: Boolean
) extends RawRequestAttributes

case class GraphQlQuery(
    query: sangria.ast.Document,
    operationName: Option[String],
    variables: JsValue,
    queryString: String
)

object GraphQlQuery {

  val queryParsingCache = Cache.lfu[String, Try[Document]](sangriaMinimumCacheSize, sangriaMaximumCacheSize)

  def tryFromJson(requestJson: JsValue): Try[GraphQlQuery] = {
    val JsObject(fields) = requestJson
    val query = fields.get("query") match {
      case Some(JsString(query)) => query
      case _                     => ""
    }

    val operationName = fields.get("operationName") collect {
      case JsString(op) if !op.isEmpty ⇒ op
    }

    val variables = fields.get("variables") match {
      case Some(obj: JsObject) =>
        obj
      case Some(JsString(s)) if s.trim.nonEmpty =>
        Try { Json.parse(s) } match {
          case Success(json: JsObject) => json
          case Success(_)              => JsObject.empty
          case Failure(_)              => throw VariablesParsingError(s)
        }
      case _ =>
        JsObject.empty
    }

    val parseResult = queryParsingCache.getOrUpdate(query, () => QueryParser.parse(query))

    parseResult.map { queryAst =>
      GraphQlQuery(
        query = queryAst,
        queryString = query,
        operationName = operationName,
        variables = variables
      )
    }
  }
}
