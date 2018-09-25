package com.prisma.subscriptions.schema

import com.prisma.shared.models.ModelMutationType
import com.prisma.shared.models.ModelMutationType.ModelMutationType
import sangria.ast.OperationType.Subscription
import sangria.ast._
import sangria.visitor.VisitorCommand

object QueryTransformer {

  def replaceExternalFieldsWithBooleanFieldsForInternalSchema(query: Document, mutation: ModelMutationType, updatedFields: Option[List[String]]) = {
    val replaceMutationIn = replaceMutationInFilter(query, mutation).asInstanceOf[Document]

    mutation == ModelMutationType.Updated match {
      case true  => replaceUpdatedFieldsInFilter(replaceMutationIn, updatedFields.get.toSet).asInstanceOf[Document]
      case false => replaceMutationIn
    }
  }

  def replaceMutationInFilter(query: Document, mutation: ModelMutationType): AstNode = {
    val mutationName = mutation.toString
    MyAstVisitor.visitAst(
      query,
      onEnter = {
        case ObjectField("mutation_in", EnumValue(value, _, _), _, _) =>
          val exists = mutationName == value
          Some(ObjectField("boolean", BooleanValue(exists)))

        case ObjectField("mutation_in", ListValue(values, _, _), _, _) =>
          values.isEmpty match {
            case true  => None
            case false => Some(ObjectField("boolean", BooleanValue(values.asInstanceOf[Vector[EnumValue]].exists(_.value == mutationName))))
          }

        case _ =>
          None
      },
      onLeave = (node) => {
        None
      }
    )
  }

  def replaceUpdatedFieldsInFilter(query: Document, updatedFields: Set[String]) = {
    MyAstVisitor.visitAst(
      query,
      onEnter = {
        case ObjectField(fieldName @ ("updatedFields_contains_every" | "updatedFields_contains_some"), ListValue(values, _, _), _, _) =>
          values match {
            case (x: StringValue) +: _ =>
              val list      = values.asInstanceOf[Vector[StringValue]]
              val valuesSet = list.map(_.value).toSet

              fieldName match {
                case "updatedFields_contains_every" =>
                  val containsEvery = valuesSet.subsetOf(updatedFields)
                  Some(ObjectField("boolean", BooleanValue(containsEvery)))

                case "updatedFields_contains_some" =>
                  // is one of the fields in the list included in the updated fields?
                  val containsSome = valuesSet.exists(updatedFields.contains)
                  Some(ObjectField("boolean", BooleanValue(containsSome)))

                case _ =>
                  None
              }

            case _ =>
              None
          }

        case ObjectField("updatedFields_contains", StringValue(value, _, _, _, _), _, _) =>
          val contains = updatedFields.contains(value)
          Some(ObjectField("boolean", BooleanValue(contains)))

        case _ =>
          None
      },
      onLeave = (node) => {
        None
      }
    )
  }

  def getModelNameFromSubscription(query: Document): Option[String] = {
    var modelName: Option[String] = None

    AstVisitor.visit(
      query,
      onEnter = (node: AstNode) => {
        node match {
          case OperationDefinition(Subscription, _, _, _, selections, _, _, _) =>
            selections match {
              case (x: Field) +: _ => modelName = Some(x.name.capitalize)
              case _               =>
            }

          case _ =>
        }
        VisitorCommand.Continue
      },
      onLeave = _ => {
        VisitorCommand.Continue
      }
    )
    modelName
  }

  def getMutationTypesFromSubscription(query: Document): Set[ModelMutationType] = {

    var mutations: Set[ModelMutationType] = Set.empty

    AstVisitor.visit(
      query,
      onEnter = (node: AstNode) => {
        node match {
          case ObjectField("mutation_in", ListValue(values, _, _), _, _) =>
            values match {
              case (x: EnumValue) +: xs =>
                val list = values.asInstanceOf[Vector[EnumValue]]
                list.foreach(mutation => {
                  mutation.value match {
                    case "CREATED" => mutations += ModelMutationType.Created
                    case "DELETED" => mutations += ModelMutationType.Deleted
                    case "UPDATED" => mutations += ModelMutationType.Updated
                  }
                })

              case _ =>
            }

          case _ =>
        }
        VisitorCommand.Continue
      },
      onLeave = (node) => {
        VisitorCommand.Continue
      }
    )

    if (mutations.isEmpty) mutations ++= Set(ModelMutationType.Created, ModelMutationType.Deleted, ModelMutationType.Updated)

    mutations
  }
}
