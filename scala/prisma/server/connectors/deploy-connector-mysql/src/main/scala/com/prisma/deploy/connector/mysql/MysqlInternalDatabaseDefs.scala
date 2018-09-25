package com.prisma.deploy.connector.mysql

import com.prisma.config.DatabaseConfig
import com.typesafe.config.{Config, ConfigFactory}

case class MysqlInternalDatabaseDefs(dbConfig: DatabaseConfig) {
  import slick.jdbc.MySQLProfile.api._

  val managementSchemaName = dbConfig.managementSchema.getOrElse("prisma")

  lazy val setupDatabase      = database(root = true)
  lazy val managementDatabase = database(root = false)

  private lazy val dbDriver = new org.mariadb.jdbc.Driver

  def database(root: Boolean) = {
    val config = typeSafeConfigFromDatabaseConfig(dbConfig, root)
    Database.forConfig("database", config, driver = dbDriver)
  }

  def typeSafeConfigFromDatabaseConfig(dbConfig: DatabaseConfig, root: Boolean): Config = {
    val pooled = if (dbConfig.pooled) "" else "connectionPool = disabled"
    val schema = if (root) "" else managementSchemaName

    ConfigFactory
      .parseString(s"""
        |database {
        |  connectionInitSql="set names utf8mb4"
        |  dataSourceClass = "slick.jdbc.DriverDataSource"
        |  properties {
        |    url = "jdbc:mysql://${dbConfig.host}:${dbConfig.port}/$schema?autoReconnect=true&useSSL=false&serverTimeZone=UTC&useUnicode=true&characterEncoding=UTF-8&socketTimeout=60000&usePipelineAuth=false"
        |    user = "${dbConfig.user}"
        |    password = "${dbConfig.password.getOrElse("")}"
        |  }
        |  numThreads = 1
        |  connectionTimeout = 5000
        |  $pooled
        |}
      """.stripMargin)
      .resolve
  }
}
