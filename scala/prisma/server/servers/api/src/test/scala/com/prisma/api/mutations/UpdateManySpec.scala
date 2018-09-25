package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.shared.models.Project
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class UpdateManySpec extends FlatSpec with Matchers with ApiSpecBase {

  val project: Project = SchemaDsl.fromBuilder { schema =>
    schema.model("Todo").field_!("title", _.String).field("opt", _.String)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    database.setup(project)
  }
  override def beforeEach(): Unit = database.truncateProjectTables(project)

  "The update items Mutation" should "update the items matching the where clause" in {
    createTodo("title1")
    createTodo("title2")

    val result = server.query(
      """mutation {
        |  updateManyTodoes(
        |    where: { title: "title1" }
        |    data: { title: "updated title", opt: "test" }
        |  ){
        |    count
        |  }
        |}
      """.stripMargin,
      project
    )
    result.pathAsLong("data.updateManyTodoes.count") should equal(1)

    val todoes = server.query(
      """{
        |  todoes {
        |    title
        |    opt
        |  }
        |}
      """.stripMargin,
      project
    )
    mustBeEqual(
      todoes.pathAsJsValue("data.todoes").toString,
      """[{"title":"updated title","opt":"test"},{"title":"title2","opt":null}]"""
    )
  }

  "The update items Mutation" should "update all items if the where clause is empty" in {
    createTodo("title1")
    createTodo("title2")
    createTodo("title3")

    val result = server.query(
      """mutation {
        |  updateManyTodoes(
        |    where: { }
        |    data: { title: "updated title" }
        |  ){
        |    count
        |  }
        |}
      """.stripMargin,
      project
    )
    result.pathAsLong("data.updateManyTodoes.count") should equal(3)

    val todoes = server.query(
      """{
        |  todoes {
        |    title
        |  }
        |}
      """.stripMargin,
      project
    )
    mustBeEqual(
      todoes.pathAsJsValue("data.todoes").toString,
      """[{"title":"updated title"},{"title":"updated title"},{"title":"updated title"}]"""
    )

  }

  def createTodo(title: String): Unit = {
    server.query(
      s"""mutation {
        |  createTodo(
        |    data: {
        |      title: "$title"
        |    }
        |  ) {
        |    id
        |  }
        |}
      """.stripMargin,
      project
    )
  }
}
