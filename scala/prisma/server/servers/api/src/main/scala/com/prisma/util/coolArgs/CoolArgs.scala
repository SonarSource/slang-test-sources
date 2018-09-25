package com.prisma.util.coolArgs

import com.prisma.api.connector._
import com.prisma.api.schema.APIErrors
import com.prisma.gc_values.{ListGCValue, NullGCValue, RootGCValue}
import com.prisma.shared.models._

import scala.collection.immutable.Seq

/**
  * It's called CoolArgs to easily differentiate from Sangria's Args class.
  *
  * - implement subclasses
  *   - nonlistscalarCoolArgs
  *   - listscalarCoolArgs
  *   - relationCoolArgs
  *   - Upsert Create/Delete CoolArgs
  */
object CoolArgs {
  def apply(raw: Map[String, Any]): CoolArgs = new CoolArgs(raw)

  def fromSchemaArgs(raw: Map[String, Any]): CoolArgs = {
    val argsPointer: Map[String, Any] = raw.get("data") match {
      case Some(value) => value.asInstanceOf[Map[String, Any]]
      case None        => raw
    }
    CoolArgs(argsPointer)
  }
}

case class CoolArgs(raw: Map[String, Any]) {
  def isEmpty: Boolean    = raw.isEmpty
  def isNonEmpty: Boolean = raw.nonEmpty

  def subNestedMutation(relationField: RelationField, subModel: Model): NestedMutations = {
    subArgsOption(relationField.name) match {
      case None             => NestedMutations.empty
      case Some(None)       => NestedMutations.empty
      case Some(Some(args)) => args.asNestedMutation(relationField, subModel)
    }
  }

  def getScalarListArgs(model: Model): Vector[(String, ListGCValue)] = {
    val x = for {
      field       <- model.scalarListFields
      listGCValue <- this.subScalarList(field)
    } yield {
      (field.name, listGCValue)
    }
    x.toVector
  }

  private def asNestedMutation(relationField: RelationField, subModel: Model): NestedMutations = {
    if (relationField.isList) {
      NestedMutations(
        creates = subArgsVector("create").getOrElse(Vector.empty).map(CreateOne),
        updates = subArgsVector("update").getOrElse(Vector.empty).map { args =>
          UpdateByWhere(args.extractNodeSelectorFromWhereField(subModel), args.subArgsOption("data").get.get)
        },
        upserts = subArgsVector("upsert").getOrElse(Vector.empty).map { args =>
          UpsertByWhere(
            where = args.extractNodeSelectorFromWhereField(subModel),
            update = args.subArgsOption("update").get.get,
            create = args.subArgsOption("create").get.get
          )
        },
        deletes = subArgsVector("delete").getOrElse(Vector.empty).map(args => DeleteByWhere(args.extractNodeSelector(subModel))),
        connects = subArgsVector("connect").getOrElse(Vector.empty).map(args => ConnectByWhere(args.extractNodeSelector(subModel))),
        disconnects = subArgsVector("disconnect").getOrElse(Vector.empty).map(args => DisconnectByWhere(args.extractNodeSelector(subModel)))
      )
    } else {
      NestedMutations(
        creates = subArgsOption("create").flatten.map(CreateOne).toVector,
        updates = subArgsOption("update").flatten.map(UpdateByRelation).toVector,
        upserts = subArgsOption("upsert").flatten
          .map(args => UpsertByRelation(update = args.subArgsOption("update").get.get, create = args.subArgsOption("create").get.get))
          .toVector,
        deletes = getFieldValueAs[Boolean]("delete").flatten.collect { case x if x => DeleteByRelation(x) }.toVector,
        connects = subArgsOption("connect").flatten.map(args => ConnectByWhere(args.extractNodeSelector(subModel))).toVector,
        disconnects = getFieldValueAs[Boolean]("disconnect").flatten.collect { case x if x => DisconnectByRelation(x) }.toVector
      )
    }
  }

  def subScalarList(scalarListField: ScalarField): Option[ListGCValue] = {
    subArgsOption(scalarListField.name).flatten.flatMap { args =>
      args.getFieldValuesAs[Any]("set") match {
        case None =>
          None

        case Some(values) =>
          val converter = GCAnyConverter(scalarListField.typeIdentifier, false)
          Some(ListGCValue(values.map(converter.toGCValue(_).get).toVector))
      }
    }
  }

  private def subArgsVector(field: String): Option[Vector[CoolArgs]] = getFieldValuesAs[Map[String, Any]](field) match {
    case None    => None
    case Some(x) => Some(x.map(CoolArgs(_)).toVector)
  }

  private def subArgsOption(name: String): Option[Option[CoolArgs]] = {
    val fieldValue: Option[Option[Map[String, Any]]] = getFieldValueAs[Map[String, Any]](name)
    fieldValue match {
      case None          => None
      case Some(None)    => Some(None)
      case Some(Some(x)) => Some(Some(CoolArgs(x)))
    }
  }

  /**
    * The outer option is defined if the field key was specified in the arguments at all.
    * The inner option is empty if a null value was sent for this field. If the option is defined it contains a non null value
    * for this field.
    */
  private def getFieldValueAs[T](name: String): Option[Option[T]] = {
    raw.get(name).map { fieldValue =>
      try {
        fieldValue.asInstanceOf[Option[T]]
      } catch {
        case _: ClassCastException => Option(fieldValue.asInstanceOf[T])
      }
    }
  }

  private def getFieldValuesAs[T](field: String): Option[Seq[T]] = {
    raw.get(field).map { fieldValue =>
      try {
        fieldValue.asInstanceOf[Option[Seq[T]]].getOrElse(Seq.empty)
      } catch {
        case _: ClassCastException => fieldValue.asInstanceOf[Seq[T]]
      }
    }
  }

  def extractNodeSelectorFromWhereField(model: Model): NodeSelector = {
    val whereArgs = raw("where").asInstanceOf[Map[String, Option[Any]]]
    CoolArgs(whereArgs).extractNodeSelector(model)
  }

  def extractNodeSelector(model: Model): NodeSelector = {
    raw.asInstanceOf[Map[String, Option[Any]]].collectFirst {
      case (fieldName, Some(value)) =>
        NodeSelector(model,
                     model.getScalarFieldByName_!(fieldName),
                     GCAnyConverter(model.getFieldByName_!(fieldName).typeIdentifier, isList = false).toGCValue(value).get)
    } getOrElse {
      throw APIErrors.NullProvidedForWhereError(model.name)
    }
  }

  def createArgumentsAsCoolArgs: CoolArgs = CoolArgs(raw("create").asInstanceOf[Map[String, Any]])
  def updateArgumentsAsCoolArgs: CoolArgs = CoolArgs(raw("update").asInstanceOf[Map[String, Any]])

  def generateNonListCreateArgs(model: Model): CoolArgs = {
    CoolArgs(
      model.scalarNonListFields
        .filter(_.name != "id")
        .flatMap { field =>
          raw.get(field.name) match {
            case Some(None) if field.defaultValue.isDefined && field.isRequired => throw APIErrors.InputInvalid("null", field.name, model.name)
            case Some(value)                                                    => Some((field.name, value))
            case None if field.defaultValue.isDefined                           => Some((field.name, field.defaultValue.get.value))
            case None                                                           => None
          }
        }
        .toMap)
  }

  def generateNonListUpdateGCValues(model: Model): PrismaArgs = {
    val values = for {
      field      <- model.scalarNonListFields.toVector
      fieldValue <- getFieldValueAs[Any](field.name)
    } yield {
      val converter = GCAnyConverter(field.typeIdentifier, false)
      fieldValue match {
        case Some(value) => field.name -> converter.toGCValue(value).get
        case None        => field.name -> NullGCValue
      }
    }
    PrismaArgs(RootGCValue(values: _*))
  }

  def getCreateArgs(model: Model) = {
    val nonListCreateArgs = generateNonListCreateArgs(model)
    val converter         = GCCreateReallyCoolArgsConverter(model)
    val nonListArgs       = converter.toReallyCoolArgs(nonListCreateArgs.raw)
    val listArgs          = getScalarListArgs(model)

    (nonListArgs, listArgs)
  }

  def getUpdateArgs(model: Model) = {
    val nonListArgs = generateNonListUpdateGCValues(model)
    val listArgs    = getScalarListArgs(model)

    (nonListArgs, listArgs)
  }
}
