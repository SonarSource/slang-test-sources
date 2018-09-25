package com.prisma.deploy.migration.inference

import com.prisma.deploy.connector.{InferredTables, MissingBackRelations}
import com.prisma.deploy.migration.DirectiveTypes.{InlineRelationDirective, RelationTableDirective}
import com.prisma.deploy.migration.validation._
import com.prisma.deploy.schema.InvalidRelationName
import com.prisma.deploy.validation.NameConstraints
import com.prisma.shared.models.Manifestations._
import com.prisma.shared.models.{OnDelete, RelationSide, ReservedFields, _}
import com.prisma.utils.await.AwaitUtils
import cool.graph.cuid.Cuid
import sangria.ast.{Field => _}

trait SchemaInferrer {
  def infer(baseSchema: Schema, schemaMapping: SchemaMapping, graphQlSdl: PrismaSdl, inferredTables: InferredTables): Schema
}

object SchemaInferrer {
  def apply(isActive: Boolean = true, shouldCheckAgainstInferredTables: Boolean = true) = new SchemaInferrer {
    override def infer(baseSchema: Schema, schemaMapping: SchemaMapping, graphQlSdl: PrismaSdl, inferredTables: InferredTables) =
      SchemaInferrerImpl(
        baseSchema = baseSchema,
        schemaMapping = schemaMapping,
        prismaSdl = graphQlSdl,
        isActive = isActive,
        shouldCheckAgainstInferredTables = shouldCheckAgainstInferredTables,
        inferredTables = inferredTables
      ).infer()
  }
}

case class SchemaInferrerImpl(
    baseSchema: Schema,
    schemaMapping: SchemaMapping,
    prismaSdl: PrismaSdl,
    isActive: Boolean,
    shouldCheckAgainstInferredTables: Boolean,
    inferredTables: InferredTables
) extends AwaitUtils {

  val isPassive = !isActive

  def infer(): Schema = Schema(modelTemplates = nextModels.toList, relationTemplates = nextRelations.toList, enums = nextEnums.toList)

  lazy val nextModels: Vector[ModelTemplate] = {
    prismaSdl.types.map { prismaType =>
      val fieldNames = prismaType.fields.map(_.name)
      val hiddenReservedFields = if (isActive) {
        val missingReservedFields = ReservedFields.reservedFieldNames.filterNot(fieldNames.contains)
        missingReservedFields.map(ReservedFields.reservedFieldFor)
      } else {
        Vector.empty
      }
      val manifestation = prismaType.tableName.map(ModelManifestation)

      val stableIdentifier = baseSchema.getModelByName(schemaMapping.getPreviousModelName(prismaType.name)) match {
        case Some(existingModel) => existingModel.stableIdentifier
        case None                => Cuid.createCuid()
      }

      ModelTemplate(
        name = prismaType.name,
        fieldTemplates = fieldsForType(prismaType).toList ++ hiddenReservedFields,
        stableIdentifier = stableIdentifier,
        manifestation = manifestation
      )
    }
  }

  def fieldsForType(prismaType: PrismaType): Vector[FieldTemplate] = {

    val fields: Vector[FieldTemplate] = prismaType.fields.flatMap { prismaField =>
      def relationFromRelationField(x: RelationalPrismaField) = {
        x.relationName match {
          case Some(name) =>
            nextRelations.find(_.name == name)

          case None =>
            val relationsThatConnectBothModels = nextRelations.filter(relation => relation.connectsTheModels(prismaType.name, x.referencesType))
            if (relationsThatConnectBothModels.size > 1) {
              None
            } else {
              relationsThatConnectBothModels.headOption
            }
        }
      }

      //For self relations we were inferring the relationSide A for both sides, this now assigns A to the lexicographically lower field name and B to the other
      //If in the previous schema both relationSides are A we reassign the relationsides otherwise we keep the one from the previous schema.
      def inferRelationSide(relation: Option[RelationTemplate]) = {
        def oldRelationSidesNotBothEqual(oldField: RelationField) = oldField.relationSide != oldField.relatedField.relationSide

        relation.map { relation =>
          if (relation.isSameModelRelation) {
            val oldFieldName = schemaMapping.getPreviousFieldName(prismaType.name, prismaField.name)
            val oldModelName = schemaMapping.getPreviousModelName(prismaType.name)
            val oldField     = baseSchema.getFieldByName(oldModelName, oldFieldName)

            oldField match {
              case Some(field: RelationField) if oldRelationSidesNotBothEqual(field) =>
                field.relationSide

              case _ =>
                val relationFieldNames = prismaType.relationalPrismaFields.filter(f => f.relationName.contains(relation.name)).map(_.name)
                if (relationFieldNames.exists(name => name < prismaField.name)) RelationSide.B else RelationSide.A
            }
          } else {
            if (relation.modelAName == prismaType.name) RelationSide.A else RelationSide.B
          }
        }
      }

      prismaField match {
        case scalarField: ScalarPrismaField =>
          Some(
            FieldTemplate(
              name = scalarField.name,
              typeIdentifier = scalarField.typeIdentifier,
              isRequired = scalarField.isRequired,
              isList = scalarField.isList,
              isUnique = scalarField.isUnique,
              isAutoGenerated = !isActive && scalarField.typeIdentifier == TypeIdentifier.Int && scalarField.name == "id",
              enum = None,
              defaultValue = scalarField.defaultValue,
              relationName = None,
              relationSide = None,
              manifestation = scalarField.columnName.map(FieldManifestation)
            ))

        case enumField: EnumPrismaField =>
          Some(
            FieldTemplate(
              name = enumField.name,
              typeIdentifier = enumField.typeIdentifier,
              isRequired = enumField.isRequired,
              isList = enumField.isList,
              isUnique = enumField.isUnique,
              enum = nextEnums.find(_.name == enumField.enumName),
              defaultValue = enumField.defaultValue,
              relationName = None,
              relationSide = None,
              manifestation = enumField.columnName.map(FieldManifestation)
            ))
        case relationField: RelationalPrismaField =>
          val relation = relationFromRelationField(relationField)

          Some(
            FieldTemplate(
              name = relationField.name,
              typeIdentifier = relationField.typeIdentifier,
              isRequired = relationField.isRequired,
              isList = relationField.isList,
              isUnique = false,
              enum = None,
              defaultValue = None,
              relationName = relation.map(_.name),
              relationSide = inferRelationSide(relation),
              manifestation = None
            ))
      }
    }

    fields
  }

  lazy val nextRelations: Set[RelationTemplate] = {
    val tmp = for {
      prismaType    <- prismaSdl.types
      relationField <- prismaType.relationalPrismaFields
    } yield {
      val model1       = prismaType.name
      val model2       = relationField.referencesType
      val relatedField = relationField.relatedField

      val model1OnDelete: OnDelete.Value = relationField.cascade
      val model2OnDelete: OnDelete.Value = relatedField.map(_.cascade).getOrElse(OnDelete.SetNull)

      val (modelA, modelAOnDelete, modelB, modelBOnDelete) =
        if (model1 < model2) (model1, model1OnDelete, model2, model2OnDelete) else (model2, model2OnDelete, model1, model1OnDelete)

      /**
        * 1: has relation directive. use that one.
        * 2: has no relation directive but there's a related field with directive. Use name of the related field.
        * 3: use auto generated name else
        */
      def generateRelationName: String = {
        def concat(modelName: String, otherModelName: String): String = {
          val concatenatedString = s"${modelName}To${otherModelName}"

          !NameConstraints.isValidRelationName(concatenatedString) match {
            case true if otherModelName.length > modelName.length => concat(modelName, otherModelName.substring(0, otherModelName.length - 1))
            case true                                             => concat(modelName.substring(0, modelName.length - 1), otherModelName)
            case false                                            => concatenatedString
          }
        }
        concat(modelA, modelB)
      }

      val relationNameOnRelatedField: Option[String] = relatedField.flatMap(_.relationName)
      val relationName = (relationField.relationName, relationNameOnRelatedField) match {
        case (Some(name), _) if !NameConstraints.isValidRelationName(name)    => throw InvalidRelationName(name)
        case (None, Some(name)) if !NameConstraints.isValidRelationName(name) => throw InvalidRelationName(name)
        case (Some(name), _)                                                  => name
        case (None, Some(name))                                               => name
        case (None, None)                                                     => generateRelationName
      }
      val previousModelAName = schemaMapping.getPreviousModelName(modelA)
      val previousModelBName = schemaMapping.getPreviousModelName(modelB)

      val oldEquivalentRelationByName =
        relationField.relationName.flatMap(baseSchema.getRelationByName).filter(rel => rel.connectsTheModels(previousModelAName, previousModelBName))

      val oldEquivalentRelation = oldEquivalentRelationByName.orElse {
        UnambiguousRelation.unambiguousRelationThatConnectsModels(baseSchema, previousModelAName, previousModelBName)
      }
      val relationManifestation = relationManifestationOnFieldOrRelatedField(prismaType, relationField)

      val nextRelation = RelationTemplate(
        name = relationName,
        modelAName = modelA,
        modelBName = modelB,
        modelAOnDelete = modelAOnDelete,
        modelBOnDelete = modelBOnDelete,
        manifestation = relationManifestation
      )

      oldEquivalentRelation match {
        case Some(relation) =>
          val nextModelAId = if (previousModelAName == relation.modelAName) modelA else modelB
          val nextModelBId = if (previousModelBName == relation.modelBName) modelB else modelA
          nextRelation.copy(modelAName = nextModelAId, modelBName = nextModelBId)

        case None => nextRelation
      }
    }
    tmp.groupBy(_.name).values.flatMap(_.headOption).toSet
  }

  def relationManifestationOnFieldOrRelatedField(prismaType: PrismaType, relationField: RelationalPrismaField): Option[RelationManifestation] = {
    if (isPassive && shouldCheckAgainstInferredTables) { // todo try to get rid of this
      val manifestationOnThisField = relationManifestationOnField(prismaType, relationField)
      val manifestationOnRelatedField = relationField.relatedField.flatMap { relatedField =>
        val relatedType = prismaSdl.types.find(_.name == relationField.referencesType).get
        relationManifestationOnField(relatedType, relatedField)
      }
      manifestationOnThisField.orElse(manifestationOnRelatedField)
    } else {
      None
    }
  }

  def relationManifestationOnField(prismaType: PrismaType, relationField: RelationalPrismaField): Option[RelationManifestation] = {
    val relatedType         = relationField.relatedType
    val tableForThisType    = prismaType.finalTableName
    val tableForRelatedType = relatedType.finalTableName
    val isThisModelA        = isModelA(prismaType.name, relationField.referencesType)

    relationField.relationDbDirective match {
      case Some(inlineDirective: InlineRelationDirective) =>
        Some(InlineRelationManifestation(inTableOfModelId = prismaType.name, referencingColumn = inlineDirective.column))

      case Some(tableDirective: RelationTableDirective) =>
        val inferredTable        = inferredTables.relationTables.find(_.name == tableDirective.table)
        def columnForThisType    = tableDirective.thisColumn.orElse(inferredTable.flatMap(table => table.columnForTable(tableForThisType)))
        def columnForRelatedType = tableDirective.otherColumn.orElse(inferredTable.flatMap(table => table.columnForTable(tableForRelatedType)))

        for {
          modelAColumn <- if (isThisModelA) columnForThisType else columnForRelatedType
          modelBColumn <- if (isThisModelA) columnForRelatedType else columnForThisType
        } yield {
          RelationTableManifestation(
            table = tableDirective.table,
            modelAColumn = modelAColumn,
            modelBColumn = modelBColumn
          )
        }

      case None =>
        inferredTables.relationTables
          .find { relationTable =>
            relationTable.referencesTheTables(tableForThisType, tableForRelatedType)
          }
          .flatMap { inferredTable =>
            val columnForThisType    = inferredTable.columnForTable(tableForThisType)
            val columnForRelatedType = inferredTable.columnForTable(tableForRelatedType)

            for {
              modelAColumn <- if (isThisModelA) columnForThisType else columnForRelatedType
              modelBColumn <- if (isThisModelA) columnForRelatedType else columnForThisType
            } yield {
              RelationTableManifestation(
                table = inferredTable.name,
                modelAColumn = modelAColumn,
                modelBColumn = modelBColumn
              )
            }
          }
          .orElse {
            for {
              referencedType <- prismaSdl.types.find(_.name == relationField.referencesType)
              modelTable     <- inferredTables.modelTables.find(_.name == prismaType.finalTableName)
              column         <- modelTable.columnNameForReferencedTable(referencedType.tableName.getOrElse(referencedType.name))
            } yield {
              InlineRelationManifestation(prismaType.name, column)
            }
          }
    }
  }

  lazy val nextEnums: Vector[Enum] = prismaSdl.enums.map(enumType => Enum(name = enumType.name, values = enumType.values))

  def isModelA(model1: String, model2: String): Boolean = model1 < model2
}
