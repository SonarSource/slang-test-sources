package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.shared.models.Project
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class UpsertDesignSpec extends FlatSpec with Matchers with ApiSpecBase {

  //region top level upserts

  "An upsert on the top level" should "execute a nested connect in the create branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation{createTodo(data:{uTodo: "B"}){uTodo}}""", project)

    server
      .query(
        """mutation {upsertList(
           |                     where:{uList: "Does not Exist"}
           |                     create:{uList:"A" todo: {connect: {uTodo: "B"}}}
           |                     update:{todo: {connect: {uTodo: "Should not matter"}}}
           |){id}}""",
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":{"uTodo":"B"}}]}}""")

    server.query(s"""query{todoes {uTodo}}""", project).toString should be("""{"data":{"todoes":[{"uTodo":"B"}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "An upsert on the top level" should "execute a nested connect in the update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation{createTodo(data:{uTodo: "B"}){uTodo}}""", project)
    server.query("""mutation{createList(data:{uList:"A"}){uList}}""", project)
    server
      .query(
        """mutation {upsertList(
          |                     where:{uList: "A"}
          |                     create:{uList:"A" todo: {connect: {uTodo: "Should not Matter"}}}
          |                     update:{todo: {connect: {uTodo: "B"}}}
          |){id}}""",
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":{"uTodo":"B"}}]}}""")

    server.query(s"""query{todoes {uTodo}}""", project).toString should be("""{"data":{"todoes":[{"uTodo":"B"}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "An upsert on the top level" should "execute a nested disconnect in the update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation{createTodo(data:{uTodo: "B", list: {create: {uList:"A"}}}){uTodo}}""", project)

    server
      .query(
        """mutation {upsertList(
          |                     where:{uList: "A"}
          |                     create:{uList:"A" todo: {connect: {uTodo: "Should not Matter"}}}
          |                     update:{todo: {disconnect: true}}
          |){id}}""",
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":null}]}}""")

    server.query(s"""query{todoes {uTodo}}""", project).toString should be("""{"data":{"todoes":[{"uTodo":"B"}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "An upsert on the top level" should "execute a nested delete in the update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation{createTodo(data:{uTodo: "B", list: {create: {uList:"A"}}}){uTodo}}""", project)

    server
      .query(
        """mutation {upsertList(
          |                     where:{uList: "A"}
          |                     create:{uList:"A" todo: {connect: {uTodo: "Should not Matter"}}}
          |                     update:{todo: {delete: true}}
          |){id}}""",
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":null}]}}""")

    server.query(s"""query{todoes {uTodo}}""", project).toString should be("""{"data":{"todoes":[]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(0)
  }

  "An upsert on the top level" should "only execute the nested create mutations of the correct update branch" ignore {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A"}){id}}""", project)

    server
      .query(
        s"""mutation upsertListValues {upsertList(
           |                             where:{uList: "A"}
           |                             create:{uList:"B"  todo: {create: {uTodo: "B"}}}
           |                             update:{uList:"C"  todo: {create: {uTodo: "C"}}}
           |){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"C","todo":{"uTodo":"C"}}]}}""")

    server.query(s"""query{todoes {uTodo}}""", project).toString should be("""{"data":{"todoes":[{"uTodo":"C"}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "An upsert on the top level" should "only execute the scalar lists of the correct create branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server
      .query(
        s"""mutation upsertListValues {upsertList(
           |                             where:{uList: "Does not Exist"}
           |                             create:{uList:"A" listInts:{set: [70, 80]}}
           |                             update:{listInts:{set: [75, 85]}}
           |){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, listInts}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","listInts":[70,80]}]}}""")
    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(0)
  }

  "An upsert on the top level" should "be able to reset lists to empty" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server
      .query(
        s"""mutation upsertListValues {upsertList(
           |                             where:{uList: "Does not Exist"}
           |                             create:{uList:"A" listInts:{set: [70, 80]}}
           |                             update:{listInts:{set: [75, 85]}}
           |){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, listInts}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","listInts":[70,80]}]}}""")
    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(0)

    server
      .query(
        s"""mutation upsertListValues {upsertList(
           |                             where:{uList: "A"}
           |                             create:{uList:"A" listInts:{set: [70, 80]}}
           |                             update:{listInts:{set: []}}
           |){id}}""".stripMargin,
        project
      )

    val result2 = server.query(s"""query{lists {uList, listInts}}""", project)
    result2.toString should equal("""{"data":{"lists":[{"uList":"A","listInts":[]}]}}""")
    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(0)
  }

  "An upsert on the top level" should "only execute the scalar lists of the correct update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.oneToOneRelation("list", "todo", list)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A" listInts: {set: [1, 2]}}){id}}""", project)

    server
      .query(
        s"""mutation upsertListValues {upsertList(
           |                             where:{uList: "A"}
           |                             create:{uList:"A" listInts:{set: [70, 80]}}
           |                             update:{listInts:{set: [75, 85]}}
           |){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, listInts}}""", project)

    result.toString should equal("""{"data":{"lists":[{"uList":"A","listInts":[75,85]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(0)
  }

  //endregion

  //region nested upserts

  "A nested upsert" should "only execute the nested scalarlists of the correct update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.manyToManyRelation("lists", "todoes", list)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A" todoes: {create: {uTodo: "B", todoInts: {set: [3, 4]}}}}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
        |                       data:{todoes: { upsert:{
        |                               where:{uTodo: "B"}
        |		                            create:{uTodo:"Should Not Matter", todoInts:{set: [300, 400]}}
        |		                            update:{uTodo: "C", todoInts:{set: [700, 800]}}
        |}}
        |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todoes {uTodo, todoInts}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todoes":[{"uTodo":"C","todoInts":[700,800]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "A nested upsert" should "only execute the nested scalarlists of the correct create branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      todo.manyToManyRelation("lists", "todoes", list)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A" todoes: {create: {uTodo: "B", todoInts: {set: [3, 4]}}}}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
           |                    data:{todoes: { upsert:{
           |                               where:{uTodo: "Does not Matter"}
           |		                           create:{uTodo:"C", todoInts:{set: [100, 200]}}
           |		                           update:{uTodo:"D", todoInts:{set: [700, 800]}}
           |}}
           |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todoes {uTodo, todoInts}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todoes":[{"uTodo":"B","todoInts":[3,4]},{"uTodo":"C","todoInts":[100,200]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(2)
  }

  "A nested upsert" should "only execute the nested scalarlists of the correct create branch for to many relations" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      val list = schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true)
      list.oneToManyRelation("todoes", "list", todo)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A" todoes: {create: {uTodo: "B", todoInts: {set: [3, 4]}}}}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
           |                    data:{todoes: { upsert:{
           |                               where:{uTodo: "Does not Matter"}
           |		                           create:{uTodo:"C", todoInts:{set: [100, 200]}}
           |		                           update:{uTodo:"D", todoInts:{set: [700, 800]}}
           |}}
           |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todoes {uTodo, todoInts}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todoes":[{"uTodo":"B","todoInts":[3,4]},{"uTodo":"C","todoInts":[100,200]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(2)
  }

  "A nested upsert" should "be able to reset lists to empty" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val todo = schema.model("Todo").field("todoInts", _.Int, isList = true).field("uTodo", _.String, isUnique = true)
      val list =
        schema.model("List").field("listInts", _.Int, isList = true).field("uList", _.String, isUnique = true).oneToManyRelation("todoes", "list", todo)
    }

    database.setup(project)

    server.query("""mutation {createList(data: {uList: "A" todoes: {create: {uTodo: "B", todoInts: {set: [3, 4]}}}}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
           |                    data:{todoes: { upsert:{
           |                               where:{uTodo: "B"}
           |		                           create:{uTodo:"C", todoInts:{set: [100, 200]}}
           |		                           update:{ todoInts:{set: []}}
           |}}
           |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todoes {uTodo, todoInts}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todoes":[{"uTodo":"B","todoInts":[]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
  }

  "A nested upsert" should "execute the nested connect mutations of the correct create branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("uTodo", _.String, isUnique = true).manyToManyRelation("list", "todo", list)
      val tag  = schema.model("Tag").field("uTag", _.String, isUnique = true).manyToManyRelation("todo", "tag", todo)
    }

    database.setup(project)

    server.query("""mutation { createTag(data:{uTag: "D"}){uTag}}""", project)
    server.query("""mutation {createList(data: {uList: "A"}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
           |                    data:{todo: {
           |                        upsert:{
           |                               where:{uTodo: "B"}
           |		                           create:{uTodo:"C" tag: {connect: {uTag: "D"}}}
           |		                           update:{uTodo:"Should Not Matter" tag: {create: {uTag: "D"}}}
           |}}
           |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo, tag {uTag }}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":[{"uTodo":"C","tag":[{"uTag":"D"}]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
    countItems(project, "tags") should be(1)

  }

  "A nested upsert" should "execute the nested connect mutations of the correct update branch" in {

    val project = SchemaDsl.fromBuilder { schema =>
      val list = schema.model("List").field("uList", _.String, isUnique = true)
      val todo = schema.model("Todo").field("uTodo", _.String, isUnique = true).manyToManyRelation("list", "todo", list)
      val tag  = schema.model("Tag").field("uTag", _.String, isUnique = true).manyToManyRelation("todo", "tag", todo)
    }

    database.setup(project)

    server.query("""mutation { createTag(data:{uTag: "D"}){uTag}}""", project)
    server.query("""mutation {createList(data: {uList: "A" todo: {create: {uTodo: "B"}}}){id}}""", project)

    server
      .query(
        s"""mutation{updateList(where:{uList: "A"}
           |                    data:{todo: {
           |                        upsert:{
           |                               where:{uTodo: "B"}
           |		                           create:{uTodo:"Should Not Matter" tag: {connect: {uTag: "D"}}}
           |		                           update:{uTodo:"C" tag: {connect: {uTag: "D"}}}
           |}}
           |}){id}}""".stripMargin,
        project
      )

    val result = server.query(s"""query{lists {uList, todo {uTodo, tag {uTag }}}}""", project)
    result.toString should equal("""{"data":{"lists":[{"uList":"A","todo":[{"uTodo":"C","tag":[{"uTag":"D"}]}]}]}}""")

    countItems(project, "lists") should be(1)
    countItems(project, "todoes") should be(1)
    countItems(project, "tags") should be(1)

  }

  //endregion

  def countItems(project: Project, name: String): Int = {
    server.query(s"""query{$name{id}}""", project).pathAsSeq(s"data.$name").length
  }
}
