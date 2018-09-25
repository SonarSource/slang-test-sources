package com.prisma.deploy.connector.postgres.impls.mutactions

import com.prisma.deploy.connector.postgres.database.PostgresDeployDatabaseMutationBuilder
import com.prisma.deploy.connector.{CreateInlineRelation, CreateRelationTable, DeleteRelationTable}

object CreateRelationInterpreter extends SqlMutactionInterpreter[CreateRelationTable] {
  override def execute(mutaction: CreateRelationTable) = {
    val modelA = mutaction.relation.modelA
    val modelB = mutaction.relation.modelB

    PostgresDeployDatabaseMutationBuilder.createRelationTable(
      projectId = mutaction.projectId,
      relationTableName = mutaction.relation.relationTableName,
      modelA = modelA,
      modelB = modelB
    )
  }

  override def rollback(mutaction: CreateRelationTable) = {
    PostgresDeployDatabaseMutationBuilder.dropTable(projectId = mutaction.projectId, tableName = mutaction.relation.relationTableName)
  }
}

object DeleteRelationInterpreter extends SqlMutactionInterpreter[DeleteRelationTable] {
  override def execute(mutaction: DeleteRelationTable) = {
    PostgresDeployDatabaseMutationBuilder.dropTable(projectId = mutaction.projectId, tableName = mutaction.relation.relationTableName)
  }

  override def rollback(mutaction: DeleteRelationTable) = {
    val createRelation = CreateRelationTable(mutaction.projectId, mutaction.schema, mutaction.relation)
    CreateRelationInterpreter.execute(createRelation)
  }
}

object CreateInlineRelationInterpreter extends SqlMutactionInterpreter[CreateInlineRelation] {
  override def execute(mutaction: CreateInlineRelation) = {
    PostgresDeployDatabaseMutationBuilder.createRelationColumn(mutaction.projectId, mutaction.model, mutaction.references, mutaction.column)
  }

  override def rollback(mutaction: CreateInlineRelation) = ???
}
