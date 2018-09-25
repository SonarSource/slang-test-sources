package com.prisma.api.schema

import akka.actor.ActorSystem
import com.prisma.api.connector._
import com.prisma.api.mutations._
import com.prisma.api.resolver.{ConnectionParentElement, DefaultIdBasedConnection}
import com.prisma.api.resolver.DeferredTypes.{IdBasedConnectionDeferred, ManyModelDeferred, OneDeferred}
import com.prisma.api.{ApiDependencies, ApiMetrics}
import com.prisma.gc_values.CuidGCValue
import com.prisma.shared.models.{Model, Project, ScalarField}
import com.prisma.util.coolArgs.CoolArgs
import com.prisma.utils.boolean.BooleanUtils._
import com.prisma.utils.future.FutureUtils.FutureOpt
import org.atteo.evo.inflector.English
import sangria.ast
import sangria.ast.Selection
import sangria.relay._
import sangria.schema._

import scala.collection.{immutable, mutable}
import scala.concurrent.Future

case class ApiUserContext(clientId: String)

trait SchemaBuilder {
  def apply(project: Project): Schema[ApiUserContext, Unit]
}

object SchemaBuilder {
  def apply()(implicit system: ActorSystem, apiDependencies: ApiDependencies): SchemaBuilder = { (project: Project) =>
    SchemaBuilderImpl(project, apiDependencies.capabilities).build()
  }
}

case class SchemaBuilderImpl(
    project: Project,
    capabilities: Vector[ApiConnectorCapability]
)(implicit apiDependencies: ApiDependencies, system: ActorSystem)
    extends SangriaExtensions {
  import system.dispatcher

  val argumentsBuilder                     = ArgumentsBuilder(project = project)
  val dataResolver                         = apiDependencies.dataResolver(project)
  val masterDataResolver                   = apiDependencies.masterDataResolver(project)
  val objectTypeBuilder: ObjectTypeBuilder = new ObjectTypeBuilder(project = project, nodeInterface = Some(nodeInterface))
  val objectTypes                          = objectTypeBuilder.modelObjectTypes
  val connectionTypes                      = objectTypeBuilder.modelConnectionTypes
  val outputTypesBuilder                   = OutputTypesBuilder(project, objectTypes, dataResolver)
  val pluralsCache                         = new PluralsCache
  val databaseMutactionExecutor            = apiDependencies.databaseMutactionExecutor
  val sideEffectMutactionExecutor          = apiDependencies.sideEffectMutactionExecutor
  val mutactionVerifier                    = apiDependencies.mutactionVerifier

  def build(): Schema[ApiUserContext, Unit] = ApiMetrics.schemaBuilderTimer.time(project.id) {
    val query        = buildQuery()
    val mutation     = buildMutation()
    val subscription = buildSubscription()

    FilterOutEmptyInputTypes(
      Schema(
        query = query,
        mutation = mutation,
        subscription = subscription,
        validationRules = SchemaValidationRule.empty
      )
    ).applyFilter()
  }

  def buildQuery(): ObjectType[ApiUserContext, Unit] = {
    val fields = project.models.map(getAllItemsField) ++
      project.models.flatMap(getSingleItemField) ++
      project.models.map(getAllItemsConnectionField) ++
      capabilities.contains(NodeQueryCapability).toOption(nodeField)

    ObjectType("Query", fields)
  }

  def buildMutation(): Option[ObjectType[ApiUserContext, Unit]] = {
    val fields = project.models.map(createItemField) ++
      project.models.flatMap(updateItemField) ++
      project.models.flatMap(deleteItemField) ++
      project.models.flatMap(upsertItemField) ++
      project.models.flatMap(updateManyField) ++
      project.models.map(deleteManyField)
    Some(ObjectType("Mutation", fields))
  }

  def buildSubscription(): Option[ObjectType[ApiUserContext, Unit]] = {
    val subscriptionFields = project.models.map(getSubscriptionField)

    if (subscriptionFields.isEmpty) None
    else Some(ObjectType("Subscription", subscriptionFields))
  }

  def getAllItemsField(model: Model): Field[ApiUserContext, Unit] = {
    Field(
      camelCase(pluralsCache.pluralName(model)),
      fieldType = ListType(OptionType(objectTypes(model.name))),
      arguments = objectTypeBuilder.mapToListConnectionArguments(model),
      resolve = (ctx) => {
        val arguments = objectTypeBuilder.extractQueryArgumentsFromContext(model, ctx)
        DeferredValue(ManyModelDeferred(model, arguments, ctx.getSelectedFields(model))).map(_.toNodes.map(Some(_)))
      }
    )
  }

  def getAllItemsConnectionField(model: Model): Field[ApiUserContext, Unit] = {
    Field(
      s"${camelCase(pluralsCache.pluralName(model))}Connection",
      fieldType = connectionTypes(model.name),
      arguments = objectTypeBuilder.mapToListConnectionArguments(model),
      resolve = (ctx) => {
        val arguments = objectTypeBuilder.extractQueryArgumentsFromContext(model, ctx)
        def getSelectedFields(field: ast.Field): Vector[ast.Field] = {
          val fields = field.selections.collect {
            case f: ast.Field => f
          }
          fields ++ fields.flatMap(getSelectedFields)
        }
        val selectedFields = ctx.astFields.flatMap(getSelectedFields).map(_.name)
        if (selectedFields == Vector("aggregate", "count")) {
          val connection = DefaultIdBasedConnection[PrismaNode](
            com.prisma.api.resolver.PageInfo.empty,
            Vector.empty,
            ConnectionParentElement(None, None, arguments)
          )
          DeferredValue(IdBasedConnectionDeferred(connection))
        } else {
          DeferredValue(ManyModelDeferred(model, arguments, ctx.getSelectedFields(model)))
        }
      }
    )
  }

  def getSingleItemField(model: Model): Option[Field[ApiUserContext, Unit]] = {
    argumentsBuilder
      .whereUniqueArgument(model)
      .map { whereArg =>
        Field(
          name = camelCase(model.name),
          fieldType = OptionType(objectTypes(model.name)),
          arguments = List(whereArg),
          resolve = { ctx =>
            val coolArgs = CoolArgs(ctx.args.raw)
            val where    = coolArgs.extractNodeSelectorFromWhereField(model)
            masterDataResolver.getNodeByWhere(where, ctx.getSelectedFields(model))
          }
        )
      }
  }

  def createItemField(model: Model): Field[ApiUserContext, Unit] = {
    Field(
      s"create${model.name}",
      fieldType = outputTypesBuilder.mapCreateOutputType(model, objectTypes(model.name)),
      arguments = argumentsBuilder.getSangriaArgumentsForCreate(model).getOrElse(List.empty),
      resolve = (ctx) => {
        val mutation = Create(
          model = model,
          project = project,
          args = ctx.args,
          selectedFields = ctx.getSelectedFields(model),
          dataResolver = masterDataResolver
        )
        val mutationResult = ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
        mapReturnValueResult(mutationResult, ctx.args)
      }
    )
  }

  def updateItemField(model: Model): Option[Field[ApiUserContext, Unit]] = {
    argumentsBuilder.getSangriaArgumentsForUpdate(model).map { args =>
      Field(
        s"update${model.name}",
        fieldType = OptionType(outputTypesBuilder.mapUpdateOutputType(model, objectTypes(model.name))),
        arguments = args,
        resolve = (ctx) => {
          val mutation = Update(
            model = model,
            project = project,
            args = ctx.args,
            selectedFields = ctx.getSelectedFields(model),
            dataResolver = masterDataResolver
          )

          val mutationResult = ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
          mapReturnValueResult(mutationResult, ctx.args)
        }
      )
    }
  }

  def updateManyField(model: Model): Option[Field[ApiUserContext, Unit]] = {
    argumentsBuilder.getSangriaArgumentsForUpdateMany(model).map { args =>
      Field(
        s"updateMany${pluralsCache.pluralName(model)}",
        fieldType = objectTypeBuilder.batchPayloadType,
        arguments = args,
        resolve = (ctx) => {
          val arguments = objectTypeBuilder.extractQueryArgumentsFromContext(model, ctx).flatMap(_.filter)
          val mutation  = UpdateMany(project, model, ctx.args, arguments, dataResolver = masterDataResolver)
          ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
        }
      )
    }
  }

  def upsertItemField(model: Model): Option[Field[ApiUserContext, Unit]] = {
    argumentsBuilder.getSangriaArgumentsForUpsert(model).map { args =>
      Field(
        s"upsert${model.name}",
        fieldType = outputTypesBuilder.mapUpsertOutputType(model, objectTypes(model.name)),
        arguments = args,
        resolve = (ctx) => {
          val mutation = Upsert(
            model = model,
            project = project,
            args = ctx.args,
            selectedFields = ctx.getSelectedFields(model),
            dataResolver = masterDataResolver
          )
          val mutationResult = ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
          mapReturnValueResult(mutationResult, ctx.args)
        }
      )
    }
  }

  def deleteItemField(model: Model): Option[Field[ApiUserContext, Unit]] = {
    argumentsBuilder.getSangriaArgumentsForDelete(model).map { args =>
      Field(
        s"delete${model.name}",
        fieldType = OptionType(outputTypesBuilder.mapDeleteOutputType(model, objectTypes(model.name), onlyId = false)),
        arguments = args,
        resolve = (ctx) => {
          val mutation = Delete(
            model = model,
            modelObjectTypes = objectTypeBuilder,
            project = project,
            args = ctx.args,
            selectedFields = ctx.getSelectedFields(model),
            dataResolver = masterDataResolver
          )
          val mutationResult = ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
          mapReturnValueResult(mutationResult, ctx.args)
        }
      )
    }
  }

  def deleteManyField(model: Model): Field[ApiUserContext, Unit] = {
    Field(
      s"deleteMany${pluralsCache.pluralName(model)}",
      fieldType = objectTypeBuilder.batchPayloadType,
      arguments = argumentsBuilder.getSangriaArgumentsForDeleteMany(model),
      resolve = (ctx) => {
        val arguments = objectTypeBuilder.extractQueryArgumentsFromContext(model, ctx).flatMap(_.filter)
        val mutation  = DeleteMany(project, model, arguments, dataResolver = masterDataResolver)
        ClientMutationRunner.run(mutation, databaseMutactionExecutor, sideEffectMutactionExecutor, mutactionVerifier)
      }
    )
  }

  def getSubscriptionField(model: Model): Field[ApiUserContext, Unit] = {
    val objectType = objectTypes(model.name)

    Field(
      camelCase(model.name),
      fieldType = OptionType(outputTypesBuilder.mapSubscriptionOutputType(model, objectType)),
      arguments = List(SangriaQueryArguments.whereSubscriptionArgument(model = model, project = project)),
      resolve = _ => None
    )
  }

  implicit val nodeEvidence = SangriaEvidences.DataItemNodeEvidence

  lazy val NodeDefinition(nodeInterface: InterfaceType[ApiUserContext, PrismaNode], nodeField, nodeRes) = Node.definitionById(
    resolve = (id: String, ctx: Context[ApiUserContext, Unit]) => {
      for {
        _         <- Future.unit
        idGcValue = CuidGCValue(id)
        modelOpt  <- dataResolver.getModelForGlobalId(idGcValue)
        resultOpt <- modelOpt match {
                      case Some(model) => dataResolver.getNodeByWhere(NodeSelector.forIdGCValue(model, idGcValue), ctx.getSelectedFields(model))
                      case None        => Future.successful(None)
                    }
      } yield resultOpt
    },
    possibleTypes = {
      objectTypes.values.flatMap { o =>
        if (o.allInterfaces.exists(_.name == "Node")) {
          Some(PossibleNodeObject[ApiUserContext, Node, PrismaNode](o))
        } else {
          None
        }
      }.toList
    }
  )

  def camelCase(string: String): String = Character.toLowerCase(string.charAt(0)) + string.substring(1)

  private def mapReturnValueResult(result: Future[ReturnValueResult], args: Args): Future[SimpleResolveOutput] = {
    result.map {
      case ReturnValue(prismaNode) => outputTypesBuilder.mapResolve(prismaNode, args)
      case NoReturnValue(where)    => throw APIErrors.NodeNotFoundForWhereError(where)
    }
  }
}

object SangriaEvidences {
  implicit object DataItemNodeEvidence extends IdentifiableNode[ApiUserContext, PrismaNode] {
    override def id(ctx: Context[ApiUserContext, PrismaNode]) = ctx.value.id.value.toString // fixme: is this the right approach for numeric ids?
  }
}

class PluralsCache {
  private val cache = mutable.Map.empty[Model, String]

  def pluralName(model: Model): String = cache.getOrElseUpdate(
    key = model,
    op = English.plural(model.name).capitalize
  )
}
