package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.api.util.TroubleCharacters
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class CreateMutationListSpec extends FlatSpec with Matchers with ApiSpecBase {

  override def runSuiteOnlyForActiveConnectors = true

  val project = SchemaDsl.fromBuilder { schema =>
    val enum = schema.enum(
      name = "MyEnum",
      values = Vector(
        "A",
        "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ"
      )
    )
    schema
      .model("ScalarModel")
      .field("optStrings", _.String, isList = true)
      .field("optInts", _.Int, isList = true)
      .field("optFloats", _.Float, isList = true)
      .field("optBooleans", _.Boolean, isList = true)
      .field("optEnums", _.Enum, enum = Some(enum), isList = true)
      .field("optDateTimes", _.DateTime, isList = true)
      .field("optJsons", _.Json, isList = true)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    database.setup(project)
  }

  override def beforeEach(): Unit = database.truncateProjectTables(project)

  "A Create Mutation" should "create and return items with listvalues" in {

    val res = server.query(
      s"""mutation {
         |  createScalarModel(data: {
         |    optStrings: {set:["lala${TroubleCharacters.value}"]},
         |    optInts:{set: [1337, 12]},
         |    optFloats: {set:[1.234, 1.45]},
         |    optBooleans: {set:[true,false]},
         |    optEnums: {set:[A,A]},
         |    optDateTimes: {set:["2016-07-31T23:59:01.000Z","2017-07-31T23:59:01.000Z"]},
         |    optJsons: {set:["[1,2,3]"]},
         |  }){optStrings, optInts, optFloats, optBooleans, optEnums, optDateTimes, optJsons}
         |}""",
      project = project
    )

    res.toString should be(
      s"""{"data":{"createScalarModel":{"optEnums":["A","A"],"optBooleans":[true,false],"optDateTimes":["2016-07-31T23:59:01.000Z","2017-07-31T23:59:01.000Z"],"optStrings":["lala${TroubleCharacters.value}"],"optInts":[1337,12],"optJsons":[[1,2,3]],"optFloats":[1.234,1.45]}}}""")

    val queryRes = server.query("""{ scalarModels{optStrings, optInts, optFloats, optBooleans, optEnums, optDateTimes, optJsons}}""", project = project)

    queryRes.toString should be(
      s"""{"data":{"scalarModels":[{"optEnums":["A","A"],"optBooleans":[true,false],"optDateTimes":["2016-07-31T23:59:01.000Z","2017-07-31T23:59:01.000Z"],"optStrings":["lala${TroubleCharacters.value}"],"optInts":[1337,12],"optJsons":[[1,2,3]],"optFloats":[1.234,1.45]}]}}""")
  }

  "A Create Mutation" should "create and return items with empty listvalues" in {

    val res = server.query(
      s"""mutation {
         |  createScalarModel(data: {
         |    optStrings: {set:[]},
         |    optInts:{set: []},
         |    optFloats: {set:[]},
         |    optBooleans: {set:[]},
         |    optEnums: {set:[]},
         |    optDateTimes: {set:[]},
         |    optJsons: {set:[]},
         |  }){optStrings, optInts, optFloats, optBooleans, optEnums, optDateTimes, optJsons}
         |}""",
      project = project
    )

    res.toString should be(
      s"""{"data":{"createScalarModel":{"optEnums":[],"optBooleans":[],"optDateTimes":[],"optStrings":[],"optInts":[],"optJsons":[],"optFloats":[]}}}""")
  }

  "An empty json as jsonlistvalue" should "work" in {

    server
      .query(
        s"""mutation {
         |  createScalarModel(data: {
         |    optJsons: {set:["{}"]},
         |  }){optJsons}
         |}""",
        project = project
      )
      .toString should be("""{"data":{"createScalarModel":{"optJsons":[{}]}}}""")

  }
}
