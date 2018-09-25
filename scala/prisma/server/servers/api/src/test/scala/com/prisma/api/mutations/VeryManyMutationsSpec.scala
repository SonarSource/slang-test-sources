package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.shared.models.Project
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class VeryManyMutationsSpec extends FlatSpec with Matchers with ApiSpecBase {

  val project: Project = SchemaDsl.fromString() {
    """
      |type Top {
      |   id: ID! @unique
      |   int: Int!
      |   middles:[Middle!]!
      |}
      |
      |type Middle {
      |   id: ID! @unique
      |   int: Int!
      |}
    """
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    database.setup(project)
    createVeryManyTops
  }

  "The delete many Mutation" should "delete the items matching the where clause" in {

    val result = server.query("""mutation {deleteManyMiddles(where: { int_gt: 100 }){count}}""", project)

    result.pathAsLong("data.deleteManyMiddles.count") should equal(36291)
  }

  def createVeryManyTops = for (int <- 1 to 1000) {
    createTop(int)
  }

  def createTop(int: Int): Unit = {
    val query =
      s"""mutation a {createTop(data: {
         |  int: $int
         |  middles: {create: [
         |  {int: ${int}1},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: ${int}20},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: $int},
         |  {int: ${int}40}
         |  ]}
         |}) {int}}"""

    server.query(query, project)
  }

  def topCountShouldBe(int: Int) = server.query("{ tops { id } }", project).pathAsSeq("data.tops").size should be(int)
  def middleCount(int: Int)      = server.query("{ middles { id } }", project).pathAsSeq("data.middles").size should be(int)

}
