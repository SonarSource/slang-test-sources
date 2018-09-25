package com.prisma.deploy.connector.postgres.impls.mutactions

import com.prisma.deploy.connector.postgres.database.PostgresDeployDatabaseMutationBuilder
import com.prisma.deploy.connector.{CreateScalarListTable, DeleteScalarListTable, UpdateScalarListTable}
import slick.jdbc.PostgresProfile.api._

object CreateScalarListInterpreter extends SqlMutactionInterpreter[CreateScalarListTable] {
  override def execute(mutaction: CreateScalarListTable) = {
    PostgresDeployDatabaseMutationBuilder.createScalarListTable(
      projectId = mutaction.projectId,
      model = mutaction.model,
      fieldName = mutaction.field.dbName,
      typeIdentifier = mutaction.field.typeIdentifier
    )
  }

  override def rollback(mutaction: CreateScalarListTable) = {
    DBIO.seq(
      PostgresDeployDatabaseMutationBuilder
        .dropScalarListTable(projectId = mutaction.projectId, modelName = mutaction.model.dbName, fieldName = mutaction.field.dbName))
  }
}

object DeleteScalarListInterpreter extends SqlMutactionInterpreter[DeleteScalarListTable] {
  override def execute(mutaction: DeleteScalarListTable) = {
    DBIO.seq(
      PostgresDeployDatabaseMutationBuilder
        .dropScalarListTable(projectId = mutaction.projectId, modelName = mutaction.model.dbName, fieldName = mutaction.field.dbName))
  }

  override def rollback(mutaction: DeleteScalarListTable) = {
    PostgresDeployDatabaseMutationBuilder.createScalarListTable(
      projectId = mutaction.projectId,
      model = mutaction.model,
      fieldName = mutaction.field.dbName,
      typeIdentifier = mutaction.field.typeIdentifier
    )
  }
}

object UpdateScalarListInterpreter extends SqlMutactionInterpreter[UpdateScalarListTable] {
  override def execute(mutaction: UpdateScalarListTable) = {
    val oldField  = mutaction.oldField
    val newField  = mutaction.newField
    val projectId = mutaction.projectId
    val oldModel  = mutaction.oldModel
    val newModel  = mutaction.newModel

    val updateType = if (oldField.typeIdentifier != newField.typeIdentifier) {
      List(PostgresDeployDatabaseMutationBuilder.updateScalarListType(projectId, oldModel.dbName, oldField.dbName, newField.typeIdentifier))
    } else {
      List.empty
    }

    val renameTable = if (oldField.dbName != newField.dbName || oldModel.dbName != newModel.dbName) {
      List(PostgresDeployDatabaseMutationBuilder.renameScalarListTable(projectId, oldModel.dbName, oldField.dbName, newModel.dbName, newField.dbName))
    } else {
      List.empty
    }

    val changes = updateType ++ renameTable

    if (changes.isEmpty) {
      DBIO.successful(())
    } else {
      DBIO.seq(changes: _*)
    }
  }

  override def rollback(mutaction: UpdateScalarListTable) = {
    val oppositeMutaction = UpdateScalarListTable(
      projectId = mutaction.projectId,
      oldModel = mutaction.newModel,
      newModel = mutaction.oldModel,
      oldField = mutaction.newField,
      newField = mutaction.oldField
    )
    execute(oppositeMutaction)
  }
}
