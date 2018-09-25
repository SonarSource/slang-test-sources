package com.prisma.config

import java.io.File
import java.net.URI
import io.lemonlabs.uri.{Uri, Url}
import io.lemonlabs.uri.config.UriConfig
import io.lemonlabs.uri.decoding.NoopDecoder
import org.yaml.snakeyaml.Yaml

import scala.util.{Failure, Success, Try}

object ConfigLoader {
  import scala.collection.JavaConverters.mapAsScalaMap

  private val yaml                = new Yaml()
  private val configFile          = "prisma"
  private val commonYmlExtensions = Seq("yml", "yaml")

  def emptyJavaMap = new java.util.LinkedHashMap[String, Any]()

  private def findPrismaConfigFilePath(): Option[String] = sys.env.get("PRISMA_CONFIG_PATH").orElse {
    val searchPath = System.getProperty("user.dir") + File.pathSeparator
    val sep        = File.pathSeparator

    commonYmlExtensions
      .map { ext =>
        val candidate = new File(s"$searchPath$sep$configFile.$ext")
        if (candidate.isFile) {
          candidate.getAbsolutePath
        } else {
          ""
        }
      }
      .find(_.nonEmpty)
  }

  def load(): PrismaConfig = {
    tryLoad() match {
      case Success(c)   => c
      case Failure(err) => sys.error(s"Unable to load Prisma config: $err")
    }
  }

  // Todo error propagation concept with meaningful user messages
  def tryLoad(): Try[PrismaConfig] =
    Try {
      sys.env
        .get("PRISMA_CONFIG")
        .orElse(findPrismaConfigFilePath().map(scala.io.Source.fromFile(_).mkString))
        .orElse(legacyConfig())
        .getOrElse(sys.error("No valid Prisma config could be loaded."))
    }.flatMap(tryLoadString)

  def tryLoadString(config: String): Try[PrismaConfig] = Try {
    convertToConfig(extractScalaMap(yaml.load(config).asInstanceOf[java.util.Map[String, Any]], path = "root"))
  }

  // Only for initial backwards compatibility, will be removed in the future.
  private def legacyConfig(): Option[String] =
    Try {
      val port           = sys.env.getOrElse("PORT", "4466").toInt
      val secret         = sys.env.getOrElse("PRISMA_MANAGEMENT_API_JWT_SECRET", "")
      val legacySecret   = sys.env.getOrElse("CLUSTER_PUBLIC_KEY", "")
      val s2sSecret      = sys.env.getOrElse("SCHEMA_MANAGER_SECRET", "")
      val clusterAddress = sys.env.getOrElse("CLUSTER_ADDRESS", "")
      val rabbitUri      = sys.env.getOrElse("RABBITMQ_URI", "")
      val dbHost         = sys.env.getOrElse("SQL_CLIENT_HOST", sys.error("Env var SQL_CLIENT_HOST required but not found"))
      val dbPort         = sys.env.getOrElse("SQL_CLIENT_PORT", "3306").toInt
      val dbUser         = sys.env.getOrElse("SQL_CLIENT_USER", sys.error("Env var SQL_CLIENT_USER required but not found"))
      val dbPass         = sys.env.getOrElse("SQL_CLIENT_PASSWORD", sys.error("Env var SQL_CLIENT_PASSWORD required but not found"))
      val dbConn         = sys.env.getOrElse("SQL_INTERNAL_CONNECTION_LIMIT", "1")
      val database       = sys.env.getOrElse("SQL_INTERNAL_DATABASE", "graphcool") // Legacy always ran on 'graphcool'
      val mgmtApiEnabled = sys.env.getOrElse("CLUSTER_API_ENABLED", "1") match {
        case "1" => "true"
        case "0" => "false"
      }

      s"""
        |port: $port
        |managementApiSecret: $secret
        |legacySecret: $legacySecret
        |server2serverSecret: $s2sSecret
        |clusterAddress: $clusterAddress
        |rabbitUri: $rabbitUri
        |enableManagementApi: $mgmtApiEnabled
        |databases:
        |  default:
        |    connector: mysql
        |    migrations: true
        |    host: $dbHost
        |    port: $dbPort
        |    user: $dbUser
        |    password: $dbPass
        |    connectionLimit: $dbConn
        |    managementSchema: $database
      """.stripMargin
    }.toOption

  def convertToConfig(map: Map[String, Any]): PrismaConfig = {
    val port           = extractIntOpt("port", map)
    val secret         = extractStringOpt("managementApiSecret", map)
    val legacySecret   = extractStringOpt("legacySecret", map)
    val s2sSecret      = extractStringOpt("server2serverSecret", map)
    val clusterAddress = extractStringOpt("clusterAddress", map)
    val rabbitUri      = extractStringOpt("rabbitUri", map)
    val mgmtApiEnabled = extractBooleanOpt("enableManagementApi", map)
    val databases = extractScalaMap(map.getOrElse("databases", emptyJavaMap), path = "databases").map {
      case (dbName, dbMap) =>
        val x = readDbWithConnectionString(dbName, dbMap)
        x.getOrElse(readExplicitDb(dbName, dbMap))
    }.toSeq

    if (databases.isEmpty) {
      throw InvalidConfiguration("No databases defined")
    }

    PrismaConfig(
      port = port,
      managementApiSecret = secret,
      legacySecret = legacySecret,
      server2serverSecret = s2sSecret,
      clusterAddress = clusterAddress,
      rabbitUri = rabbitUri,
      managmentApiEnabled = mgmtApiEnabled,
      databases = databases
    )
  }

  private def readDbWithConnectionString(dbName: String, dbJavaMap: Any): Try[DatabaseConfig] = Try {
    val db          = extractScalaMap(dbJavaMap, path = dbName)
    val dbConnector = extractString("connector", db)
    val dbActive    = extractBooleanOpt("migrations", db).orElse(extractBooleanOpt("active", db))
    val uriString   = extractString("uri", db)
    val connLimit   = extractIntOpt("connectionLimit", db)
    val pooled      = extractBooleanOpt("pooled", db)
    val schema      = extractStringOpt("schema", db)
    val mgmtSchema  = extractStringOpt("managementSchema", db)
    val uri         = Url.parse(uriString)(UriConfig(decoder = NoopDecoder))
    val dbHost      = uri.hostOption.get.value
    val dbUser      = uri.user.get
    val dbPass      = uri.password
    val dbPort      = uri.port.getOrElse(5432) // FIXME: how could we not hardcode the postgres port
    val database    = uri.path.toAbsolute.parts.headOption
    val ssl         = uri.query.paramMap.get("ssl").flatMap(_.headOption).map(_ == "1")

    databaseConfig(
      name = dbName,
      connector = dbConnector,
      active = dbActive,
      host = dbHost,
      port = dbPort,
      user = dbUser,
      password = dbPass,
      connectionLimit = connLimit,
      pooled = pooled,
      database = database,
      schema = schema,
      managementSchema = mgmtSchema,
      ssl = ssl
    )
  }

  private def readExplicitDb(dbName: String, dbJavaMap: Any) = {
    val db          = extractScalaMap(dbJavaMap, path = dbName)
    val dbConnector = extractString("connector", db)
    val dbActive    = extractBooleanOpt("migrations", db).orElse(extractBooleanOpt("active", db))
    val dbHost      = extractString("host", db)
    val dbPort      = extractInt("port", db)
    val dbUser      = extractString("user", db)
    val dbPass      = extractStringOpt("password", db)
    val connLimit   = extractIntOpt("connectionLimit", db)
    val mgmtSchema  = extractStringOpt("managementSchema", db)
    val pooled      = extractBooleanOpt("pooled", db)
    val database    = extractStringOpt("database", db)
    val schema      = extractStringOpt("schema", db)
    val ssl         = extractBooleanOpt("ssl", db)

    databaseConfig(
      name = dbName,
      connector = dbConnector,
      active = dbActive,
      host = dbHost,
      port = dbPort,
      user = dbUser,
      password = dbPass,
      connectionLimit = connLimit,
      pooled = pooled,
      database = database,
      schema = schema,
      managementSchema = mgmtSchema,
      ssl = ssl
    )
  }

  def databaseConfig(
      name: String,
      connector: String,
      active: Option[Boolean],
      host: String,
      port: Int,
      user: String,
      password: Option[String],
      connectionLimit: Option[Int],
      pooled: Option[Boolean],
      database: Option[String],
      schema: Option[String],
      managementSchema: Option[String],
      ssl: Option[Boolean]
  ): DatabaseConfig = {
    val config = DatabaseConfig(
      name = name,
      connector = connector,
      active = active.getOrElse(true),
      host = host,
      port = port,
      user = user,
      password = password,
      connectionLimit = connectionLimit,
      pooled = pooled.getOrElse(true),
      database = database,
      schema = schema,
      managementSchema = managementSchema,
      ssl = ssl.getOrElse(false)
    )
    validateDatabaseConfig(config)
  }

  private def validateDatabaseConfig(config: DatabaseConfig): DatabaseConfig = {
    config.connectionLimit.foreach { connectionLimit =>
      if (connectionLimit < 2) {
        throw InvalidConfiguration("The parameter connectionLimit must be set to at least 2.")
      }
    }

    config
  }

  private def extractScalaMap(in: Any, required: Boolean = true, path: String = ""): Map[String, Any] = {
    val out = mapAsScalaMap(in.asInstanceOf[java.util.Map[String, Any]]).toMap.filter(kv => kv._2 != null)
    if (required && out.isEmpty) {
      throw InvalidConfiguration(s"Expected hash under '$path' to be non-empty")
    }

    out
  }

  private def extractString(key: String, map: Map[String, Any]): String = {
    extractStringOpt(key, map) match {
      case Some(x) => x
      case None    => throw InvalidConfiguration(s"Expected $key to be non-empty")
    }
  }

  private def extractStringOpt(key: String, map: Map[String, Any]): Option[String] = {
    map.get(key) match {
      case x @ Some(v) if v.toString.nonEmpty => x.map(_.toString)
      case _                                  => None
    }
  }

  private def extractBoolean(key: String, map: Map[String, Any]): Boolean = {
    extractBooleanOpt(key, map) match {
      case Some(x) => x
      case None    => throw InvalidConfiguration(s"Expected Boolean for field $key, got ${map.getOrElse(key, "<unset>").toString}")
    }
  }

  private def extractBooleanOpt(key: String, map: Map[String, Any]): Option[Boolean] = {
    map.get(key).map(_.toString.toLowerCase()) match {
      case Some("true")  => Some(true)
      case Some("false") => Some(false)
      case _             => None
    }
  }

  private def extractInt(key: String, map: Map[String, Any]): Int = {
    extractIntOpt(key, map) match {
      case Some(x) => x
      case None    => throw InvalidConfiguration(s"Expected Int for field $key, got ${map.getOrElse(key, "<unset>").toString}")
    }
  }

  private def extractIntOpt(key: String, map: Map[String, Any]): Option[Int] = {
    try { map.get(key).map(_.toString.toInt) } catch {
      case _: Throwable => None
    }
  }
}

case class PrismaConfig(
    port: Option[Int],
    managementApiSecret: Option[String],
    legacySecret: Option[String],
    server2serverSecret: Option[String],
    clusterAddress: Option[String],
    rabbitUri: Option[String],
    managmentApiEnabled: Option[Boolean],
    databases: Seq[DatabaseConfig]
)

case class DatabaseConfig(
    name: String,
    connector: String,
    active: Boolean,
    host: String,
    port: Int,
    user: String,
    password: Option[String],
    managementSchema: Option[String],
    connectionLimit: Option[Int],
    pooled: Boolean,
    database: Option[String],
    schema: Option[String],
    ssl: Boolean
)

abstract class ConfigError(reason: String)       extends Exception(reason)
case class InvalidConfiguration(message: String) extends ConfigError(message)
