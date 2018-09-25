package com.prisma.deploy.schema.fields

import com.prisma.shared.models.ProjectId
import sangria.schema.{Argument, InputField, StringType}

object ManualMarshallerHelpers {
  val projectIdInputFields = List(InputField("name", StringType), InputField("stage", StringType))
  val projectIdArguments   = List(Argument("name", StringType), Argument("stage", StringType))

  implicit class ManualMarshallerHelper(args: Any) {
    val asMap: Map[String, Any] = args.asInstanceOf[Map[String, Any]]

    def clientMutationId: Option[String] = optionalArgAsString("clientMutationId")

    def name: String  = requiredArgAsString("name")
    def stage: String = requiredArgAsString("stage")

    def requiredArgAsString(name: String): String         = requiredArgAs[String](name)
    def optionalArgAsString(name: String): Option[String] = optionalArgAs[String](name)

    def requiredArgAsBoolean(name: String): Boolean         = requiredArgAs[Boolean](name)
    def optionalArgAsBoolean(name: String): Option[Boolean] = optionalArgAs[Boolean](name)

    def requiredArgAs[T](name: String): T         = asMap(name).asInstanceOf[T]
    def optionalArgAs[T](name: String): Option[T] = asMap.get(name).flatMap(x => x.asInstanceOf[Option[T]])

    def optionalOptionalArgAsString(name: String): Option[Option[String]] = {
      asMap.get(name) match {
        case None                  => None
        case Some(None)            => Some(None)
        case Some(x: String)       => Some(Some(x))
        case Some(Some(x: String)) => Some(Some(x))
        case x                     => sys.error("OptionalOptionalArgsAsStringFailed" + x.toString)
      }
    }
  }
}
