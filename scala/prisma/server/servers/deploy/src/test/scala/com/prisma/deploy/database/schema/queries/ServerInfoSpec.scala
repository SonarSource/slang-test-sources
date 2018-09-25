package com.prisma.deploy.database.schema.queries

import com.prisma.deploy.specutils.ActiveDeploySpecBase
import org.scalatest.{FlatSpec, Matchers}

class ServerInfoSpec extends FlatSpec with Matchers with ActiveDeploySpecBase {

  "ServerInfo query" should "return server version" in {
    val (project, _) = setupProject(basicTypesGql)
    val result       = server.query(s"""
                                       |query {
                                       |  serverInfo {
                                       |    version
                                       |  }
                                       |}
      """.stripMargin)

    result.pathAsString("data.serverInfo.version") shouldEqual sys.env("CLUSTER_VERSION")
  }
}
