package com.prisma.deploy.connector.mysql.impls.mutactions

import com.prisma.deploy.connector.mysql.database.MySqlDeployDatabaseMutationBuilder
import com.prisma.deploy.connector.{CreateModelTable, DeleteModelTable, RenameTable}
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.MySQLProfile.api._

object CreateModelInterpreter extends SqlMutactionInterpreter[CreateModelTable] {
  override def execute(mutaction: CreateModelTable) = {
    MySqlDeployDatabaseMutationBuilder.createTable(projectId = mutaction.projectId, name = mutaction.model.dbName)
  }

  override def rollback(mutaction: CreateModelTable) = {
    MySqlDeployDatabaseMutationBuilder.dropTable(projectId = mutaction.projectId, tableName = mutaction.model.dbName)
  }
}

object DeleteModelInterpreter extends SqlMutactionInterpreter[DeleteModelTable] {
  // TODO: this is not symmetric

  override def execute(mutaction: DeleteModelTable) = {
    val dropTable = MySqlDeployDatabaseMutationBuilder.dropTable(projectId = mutaction.projectId, tableName = mutaction.model.dbName)
    val dropScalarListFields =
      mutaction.scalarListFields.map(field => MySqlDeployDatabaseMutationBuilder.dropScalarListTable(mutaction.projectId, mutaction.model.dbName, field))

    DBIO.seq(dropScalarListFields :+ dropTable: _*)
  }

  override def rollback(mutaction: DeleteModelTable) = {
    MySqlDeployDatabaseMutationBuilder.createTable(projectId = mutaction.projectId, name = mutaction.model.dbName)
  }
}

object RenameModelInterpreter extends SqlMutactionInterpreter[RenameTable] {
  override def execute(mutaction: RenameTable) = setName(mutaction, mutaction.previousName, mutaction.nextName)

  override def rollback(mutaction: RenameTable) = setName(mutaction, mutaction.nextName, mutaction.previousName)

  private def setName(mutaction: RenameTable, previousName: String, nextName: String): DBIOAction[Any, NoStream, Effect.All] = {
    val changeModelTableName = MySqlDeployDatabaseMutationBuilder.renameTable(projectId = mutaction.projectId, name = previousName, newName = nextName)
    val changeScalarListFieldTableNames = mutaction.scalarListFieldsNames.map { fieldName =>
      MySqlDeployDatabaseMutationBuilder.renameScalarListTable(mutaction.projectId, previousName, fieldName, nextName, fieldName)
    }

    DBIO.seq(changeScalarListFieldTableNames :+ changeModelTableName: _*)
  }
}
