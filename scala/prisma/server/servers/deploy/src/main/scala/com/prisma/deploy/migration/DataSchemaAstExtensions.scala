package com.prisma.deploy.migration

import com.prisma.deploy.migration.DirectiveTypes.{InlineRelationDirective, RelationTableDirective}
import com.prisma.shared.models.TypeIdentifier.ScalarTypeIdentifier
import com.prisma.shared.models.{OnDelete, TypeIdentifier}
import sangria.ast._

import scala.collection.Seq

object DataSchemaAstExtensions {
  implicit class CoolDocument(val doc: Document) extends AnyVal {
    def typeNames: Vector[String]         = objectTypes.map(_.name)
    def previousTypeNames: Vector[String] = objectTypes.map(_.previousName)

    def enumNames: Vector[String]         = enumTypes.map(_.name)
    def previousEnumNames: Vector[String] = enumTypes.map(_.previousName)

    def isObjectOrEnumType(name: String): Boolean = objectType(name).isDefined || enumType(name).isDefined

    def objectType_!(name: String): ObjectTypeDefinition       = objectType(name).getOrElse(sys.error(s"Could not find the object type $name!"))
    def objectType(name: String): Option[ObjectTypeDefinition] = objectTypes.find(_.name == name)
    def objectTypes: Vector[ObjectTypeDefinition]              = doc.definitions.collect { case x: ObjectTypeDefinition => x }

    def enumType(name: String): Option[EnumTypeDefinition] = enumTypes.find(_.name == name)
    def enumTypes: Vector[EnumTypeDefinition]              = doc.definitions collect { case x: EnumTypeDefinition => x }

    def relatedFieldOf(objectType: ObjectTypeDefinition, fieldDef: FieldDefinition): Option[FieldDefinition] = {
      val otherFieldsOnOppositeModel = objectType_!(fieldDef.typeName) match {
        case otherModel if otherModel.name == objectType.name => otherModel.fields.filter(_.typeName == objectType.name).filter(_.name != fieldDef.name)
        case otherModel                                       => otherModel.fields.filter(_.typeName == objectType.name)
      }
      getOppositeField(fieldDef, otherFieldsOnOppositeModel)
    }

    private def getOppositeField(relationField: FieldDefinition, otherFieldsOnModelBRelatedToModelA: Vector[FieldDefinition]) = {
      relationField.directive("relation") match {
        case Some(directive) =>
          otherFieldsOnModelBRelatedToModelA.find(field =>
            field.directive("relation") match {
              case Some(otherDirective) => directive.argument_!("name").value.renderCompact == otherDirective.argument_!("name").value.renderCompact
              case None                 => false
          })

        case None =>
          otherFieldsOnModelBRelatedToModelA.headOption
      }
    }
  }

  implicit class CoolObjectType(val objectType: ObjectTypeDefinition) extends AnyVal {
    def hasNoIdField: Boolean = field("id").isEmpty

    def previousName: String = {
      val nameBeforeRename = for {
        directive <- objectType.directive("rename")
        argument  <- directive.arguments.headOption
      } yield argument.value.asInstanceOf[StringValue].value

      nameBeforeRename.getOrElse(objectType.name)
    }

    def field_!(name: String): FieldDefinition       = field(name).getOrElse(sys.error(s"Could not find the field $name on the type ${objectType.name}"))
    def field(name: String): Option[FieldDefinition] = objectType.fields.find(_.name == name)

    def description: Option[String] = objectType.directiveArgumentAsString("description", "text")

    def tableName: String                  = tableNameDirective.getOrElse(objectType.name)
    def tableNameDirective: Option[String] = objectType.directiveArgumentAsString("pgTable", "name")
  }

  implicit class CoolField(val fieldDefinition: FieldDefinition) extends AnyVal {

    def hasScalarType: Boolean = TypeIdentifier.withNameOpt(typeName) match {
      case Some(_: ScalarTypeIdentifier) => true
      case _                             => false
    }

    def previousName: String = {
      val nameBeforeRename = fieldDefinition.directiveArgumentAsString("rename", "oldName")
      nameBeforeRename.getOrElse(fieldDefinition.name)
    }

    def typeString: String = fieldDefinition.fieldType.renderPretty

    def typeName: String = fieldDefinition.fieldType.namedType.name

    def columnName: Option[String] = fieldDefinition.directiveArgumentAsString("pgColumn", "name")

    def isUnique: Boolean = fieldDefinition.directive("unique").isDefined || fieldDefinition.directive("pqUnique").isDefined

    def isRequired: Boolean = fieldDefinition.fieldType.isRequired

    def isList: Boolean = fieldDefinition.fieldType match {
      case ListType(_, _)                  => true
      case NotNullType(ListType(__, _), _) => true
      case _                               => false
    }

    def isValidRelationType: Boolean = fieldDefinition.fieldType match {
      case NamedType(_, _)                                              => true
      case NotNullType(NamedType(_, _), _)                              => true
      case NotNullType(ListType(NotNullType(NamedType(_, _), _), _), _) => true
      case _                                                            => false
    }

    def isValidScalarListOrNonListType: Boolean = isValidScalarListType || isValidScalarNonListType

    def isValidScalarListType: Boolean = fieldDefinition.fieldType match {
      case ListType(NotNullType(NamedType(_, _), _), _)                 => true
      case NotNullType(ListType(NotNullType(NamedType(_, _), _), _), _) => true
      case _                                                            => false
    }

    def isValidScalarNonListType: Boolean = fieldDefinition.fieldType match {
      case NamedType(_, _)                 => true
      case NotNullType(NamedType(_, _), _) => true
      case _                               => false
    }

    def hasRelationDirective: Boolean        = relationName.isDefined
    def hasDefaultValueDirective: Boolean    = defaultValue.isDefined
    def hasOldDefaultValueDirective: Boolean = oldDefaultValue.isDefined
    def description: Option[String]          = fieldDefinition.directiveArgumentAsString("description", "text")
    def defaultValue: Option[String] =
      fieldDefinition.directiveArgumentAsString("default", "value").orElse(fieldDefinition.directiveArgumentAsString("pgDefault", "value"))
    def oldDefaultValue: Option[String]      = fieldDefinition.directiveArgumentAsString("defaultValue", "value")
    def relationName: Option[String]         = fieldDefinition.directiveArgumentAsString("relation", "name")
    def previousRelationName: Option[String] = fieldDefinition.directiveArgumentAsString("relation", "oldName").orElse(relationName)

    def relationDBDirective = relationTableDirective.orElse(inlineRelationDirective)

    def relationTableDirective: Option[RelationTableDirective] = {
      for {
        tableName   <- fieldDefinition.directiveArgumentAsString("pgRelationTable", "table")
        thisColumn  = fieldDefinition.fieldDefinition.directiveArgumentAsString("pgRelationTable", "relationColumn")
        otherColumn = fieldDefinition.directiveArgumentAsString("pgRelationTable", "targetColumn")
      } yield RelationTableDirective(table = tableName, thisColumn = thisColumn, otherColumn = otherColumn)
    }

    def inlineRelationDirective: Option[InlineRelationDirective] =
      fieldDefinition.directiveArgumentAsString("pgRelation", "column").map(value => InlineRelationDirective(value))
  }

  implicit class CoolEnumType(val enumType: EnumTypeDefinition) extends AnyVal {
    def previousName: String = {
      val nameBeforeRename = enumType.directiveArgumentAsString("rename", "oldName")
      nameBeforeRename.getOrElse(enumType.name)
    }
    def valuesAsStrings: Seq[String] = enumType.values.map(_.name)
  }

  implicit class CoolWithDirectives(val withDirectives: WithDirectives) extends AnyVal {

    def relationName = directiveArgumentAsString("relation", "name")
    def onDelete = directiveArgumentAsString("relation", "onDelete") match {
      case Some("SET_NULL") => OnDelete.SetNull
      case Some("CASCADE")  => OnDelete.Cascade
      case Some(_)          => sys.error("The SchemaSyntaxvalidator should catch this")
      case None             => OnDelete.SetNull
    }

    def directiveArgumentAsString(directiveName: String, argumentName: String): Option[String] = {
      for {
        directive <- directive(directiveName)
        argument <- directive.arguments.find { x =>
                     val isScalarOrEnum = x.value.isInstanceOf[ScalarValue] || x.value.isInstanceOf[EnumValue]
                     x.name == argumentName && isScalarOrEnum
                   }
      } yield {
        argument.value match {
          case value: EnumValue       => value.value
          case value: StringValue     => value.value
          case value: BigIntValue     => value.value.toString
          case value: BigDecimalValue => value.value.toString
          case value: IntValue        => value.value.toString
          case value: FloatValue      => value.value.toString
          case value: BooleanValue    => value.value.toString
          case _                      => sys.error("This clause is unreachable because of the instance checks above, but i did not know how to prove it to the compiler.")
        }
      }
    }

    def directive(name: String): Option[Directive] = withDirectives.directives.find(_.name == name)
    def directive_!(name: String): Directive       = directive(name).getOrElse(sys.error(s"Could not find the directive with name: $name!"))

  }

  implicit class CoolDirective(val directive: Directive) extends AnyVal {
    import shapeless._
    import syntax.typeable._

    def containsArgument(name: String, mustBeAString: Boolean): Boolean = {
      if (mustBeAString) {
        directive.arguments.find(_.name == name).flatMap(_.value.cast[StringValue]).isDefined
      } else {
        directive.arguments.exists(_.name == name)
      }
    }

    def argument(name: String): Option[Argument] = directive.arguments.find(_.name == name)
    def argument_!(name: String): Argument       = argument(name).getOrElse(sys.error(s"Could not find the argument with name: $name!"))
  }

  implicit class CoolType(val `type`: Type) extends AnyVal {

    /** Example
      * type Todo {
      *   tag: Tag!     <- we treat this as required; this is the only one we treat as required
      *   tags: [Tag!]! <- this is explicitly not required, because we don't allow many relation fields to be required
      * }
      */
    def isRequired = `type` match {
      case NotNullType(NamedType(_, _), _) => true
      case _                               => false
    }
  }

}

object DirectiveTypes {

  sealed trait RelationDBDirective
  case class RelationTableDirective(table: String, thisColumn: Option[String], otherColumn: Option[String]) extends RelationDBDirective
  case class InlineRelationDirective(column: String)                                                        extends RelationDBDirective
}
