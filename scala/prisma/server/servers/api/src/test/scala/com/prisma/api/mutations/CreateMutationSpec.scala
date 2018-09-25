package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.api.util.TroubleCharacters
import com.prisma.messagebus.pubsub.Message
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json._

class CreateMutationSpec extends FlatSpec with Matchers with ApiSpecBase {

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
      .field("optString", _.String)
      .field("optInt", _.Int)
      .field("optFloat", _.Float)
      .field("optBoolean", _.Boolean)
      .field("optEnum", _.Enum, enum = Some(enum))
      .field("optDateTime", _.DateTime)
      .field("optJson", _.Json)
      .field("optUnique", _.String, isUnique = true)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    database.setup(project)
  }

  override def beforeEach(): Unit = database.truncateProjectTables(project)

  "A Create Mutation" should "create and return item" in {

    val res = server.query(
      s"""mutation {
         |  createScalarModel(data: {
         |    optString: "lala${TroubleCharacters.value}", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[1,2,3]"
         |  }){id, optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}
         |}""".stripMargin,
      project = project
    )
    val id = res.pathAsString("data.createScalarModel.id")

    res should be(
      s"""{"data":{"createScalarModel":{"id":"$id","optJson":[1,2,3],"optInt":1337,"optBoolean":true,"optDateTime":"2016-07-31T23:59:01.000Z","optString":"lala${TroubleCharacters.value}","optEnum":"A","optFloat":1.234}}}""".parseJson)

    testDependencies.sssEventsPubSub.expectPublishedMsg(
      Message(s"subscription:event:${project.id}:createScalarModel", s"""{"nodeId":"$id","modelId":"ScalarModel","mutationType":"CreateNode"}"""))

    val queryRes = server.query("""{ scalarModels{optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""", project = project)

    queryRes.toString should be(
      s"""{"data":{"scalarModels":[{"optJson":[1,2,3],"optInt":1337,"optBoolean":true,"optDateTime":"2016-07-31T23:59:01.000Z","optString":"lala${TroubleCharacters.value}","optEnum":"A","optFloat":1.234}]}}""")
  }

  "A Create Mutation" should "create and return item with empty string" in {
    val res = server.query(
      """mutation {
        |  createScalarModel(data: {
        |    optString: ""
        |  }){optString, optInt, optFloat, optBoolean, optEnum, optJson}}""".stripMargin,
      project = project
    )

    res.toString should be("""{"data":{"createScalarModel":{"optJson":null,"optInt":null,"optBoolean":null,"optString":"","optEnum":null,"optFloat":null}}}""")
  }

  "A Create Mutation" should "create and return item with explicit null attributes" in {

    val res = server.query(
      """mutation {
        |  createScalarModel(data: {
        |    optString: null, optInt: null, optBoolean: null, optJson: null, optEnum: null, optFloat: null
        |  }){optString, optInt, optFloat, optBoolean, optEnum, optJson}}""".stripMargin,
      project
    )

    res.toString should be(
      """{"data":{"createScalarModel":{"optJson":null,"optInt":null,"optBoolean":null,"optString":null,"optEnum":null,"optFloat":null}}}""")
  }

  "A Create Mutation" should "create and return item with explicit null attributes when other mutation has explicit non-null values" in {

    val res = server.query(
      """mutation {
        | a: createScalarModel(data: {optString: "lala", optInt: 123, optBoolean: true, optJson: "[1,2,3]", optEnum: A, optFloat: 1.23}){optString, optInt, optFloat, optBoolean, optEnum, optJson}
        | b: createScalarModel(data: {optString: null, optInt: null, optBoolean: null, optJson: null, optEnum: null, optFloat: null}){optString, optInt, optFloat, optBoolean, optEnum, optJson}
        |}""".stripMargin,
      project = project
    )

    res.pathAs[JsValue]("data.a").toString should be("""{"optJson":[1,2,3],"optInt":123,"optBoolean":true,"optString":"lala","optEnum":"A","optFloat":1.23}""")
    res.pathAs[JsValue]("data.b").toString should be("""{"optJson":null,"optInt":null,"optBoolean":null,"optString":null,"optEnum":null,"optFloat":null}""")
  }

  "A Create Mutation" should "create and return item with implicit null attributes" in {
    val res = server.query("""mutation {createScalarModel(data:{}){optString, optInt, optFloat, optBoolean, optEnum, optJson}}""", project)

    res.toString should be(
      """{"data":{"createScalarModel":{"optJson":null,"optInt":null,"optBoolean":null,"optString":null,"optEnum":null,"optFloat":null}}}""")
  }

  "A Create Mutation" should "fail when text is over 256k long" in {
    val reallyLongString = "1234567890" * 40000

    server.queryThatMustFail(
      s"""mutation {createScalarModel(data: {optString: "$reallyLongString", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[1,2,3]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project,
      errorCode = 3007
    )
  }

  "A Create Mutation" should "fail when a Json is over 256k long" in {
    val reallyLongString = "1234567890" * 40000

    server.queryThatMustFail(
      s"""mutation {createScalarModel(data: {optString: "test", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[\\\"$reallyLongString\\\",\\\"is\\\",\\\"json\\\"]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project,
      errorCode = 3007
    )
  }

  "A Create Mutation" should "fail when a Json is invalid" in {
    val result = server.queryThatMustFail(
      s"""mutation {createScalarModel(data: {optString: "test", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[{'a':2}]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project,
      errorCode = 0
    )
    result.toString should include("Not valid JSON")
  }

  "A Create Mutation" should "fail when a DateTime is invalid" in {
    val result = server.queryThatMustFail(
      s"""mutation { createScalarModel(data:
         |  { optString: "test", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-0B-31T23:59:01.000Z", optJson: "[]"}
         |  ){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""".stripMargin,
      project = project,
      0
    )
    result.toString should include("Reason: 'optDateTime' Date value expected")
  }

  "A Create Mutation" should "support simplified DateTime" in {
    val result = server.query(
      s"""mutation {createScalarModel(data: {optString: "test", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016", optJson: "[]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project
    )
    result.toString should be(
      """{"data":{"createScalarModel":{"optJson":[],"optInt":1337,"optBoolean":true,"optDateTime":"2016-01-01T00:00:00.000Z","optString":"test","optEnum":"A","optFloat":1.234}}}""")
  }

  "A Create Mutation" should "fail when an Int is invalid" in {
    val result = server.queryThatMustFail(
      s"""mutation {createScalarModel(data: {optString: "test", optInt: B, optFloat: 1.234, optBoolean: true, optEnum: A, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project,
      0
    )
    result.toString should include("Int value expected")
  }

  "A Create Mutation" should "gracefully fail when an Enum is over 191 chars long long" in {
    server.queryThatMustFail(
      s"""mutation {createScalarModel(data: {optString: "test", optInt: 1337, optFloat: 1.234, optBoolean: true, optEnum: ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ, optDateTime: "2016-07-31T23:59:01.000Z", optJson: "[\\\"test\\\",\\\"is\\\",\\\"json\\\"]"}){optString, optInt, optFloat, optBoolean, optEnum, optDateTime, optJson}}""",
      project = project,
      errorCode = 3007
    )
  }

  "A Create Mutation" should "gracefully fail when a unique violation occurs" in {
    server.query(s"""mutation {createScalarModel(data: {optUnique: "test"}){optUnique}}""", project)
    server.queryThatMustFail(s"""mutation {createScalarModel(data: {optUnique: "test"}){optUnique}}""", project, errorCode = 3010)
  }
}
