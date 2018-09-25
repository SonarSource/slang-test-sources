package com.prisma.deploy.migration.inference

import com.prisma.deploy.connector.InferredTables
import com.prisma.deploy.migration.validation.SchemaSyntaxValidator
import com.prisma.shared.models._
import org.scalatest.{FlatSpec, Matchers}
import sangria.parser.QueryParser

class InfererIntegrationSpec extends FlatSpec with Matchers {

  "they" should "should propose no UpdateRelation when ambiguous relations are involved" in {
    val schema =
      """
        |type Todo {
        |  comments1: [Comment!]! @relation(name: "TodoToComments1")
        |  comments2: [Comment!]! @relation(name: "TodoToComments2")
        |}
        |type Comment {
        |  text: String
        |  todo1: Todo @relation(name: "TodoToComments1")
        |  todo2: Todo @relation(name: "TodoToComments2")
        |}
      """.stripMargin
    val project = inferSchema(schema)
    val steps   = inferSteps(previousSchema = project, next = schema)

    steps should be(empty)
  }

  "they" should "only propose an UpdateRelation step when relation directives get removed" in {
    val previousSchema =
      """
        |type Todo {
        |  comments: [Comment!]! @relation(name: "ManualRelationName")
        |}
        |type Comment {
        |  text: String
        |  todo: Todo @relation(name: "ManualRelationName")
        |}
      """.stripMargin
    val project = inferSchema(previousSchema)

    val nextSchema =
      """
        |type Todo {
        |  comments: [Comment!]!
        |}
        |type Comment {
        |  text: String
        |  todo: Todo
        |}
      """.stripMargin
    val steps = inferSteps(previousSchema = project, next = nextSchema)

    steps should have(size(3))
    steps should contain allOf (
      UpdateField(
        model = "Todo",
        newModel = "Todo",
        name = "comments",
        newName = None,
        typeName = None,
        isRequired = None,
        isList = None,
        isHidden = None,
        isUnique = None,
        relation = Some(Some("_CommentToTodo")),
        defaultValue = None,
        enum = None
      ),
      UpdateField(
        model = "Comment",
        newModel = "Comment",
        name = "todo",
        newName = None,
        typeName = None,
        isRequired = None,
        isList = None,
        isHidden = None,
        isUnique = None,
        relation = Some(Some("_CommentToTodo")),
        defaultValue = None,
        enum = None
      ),
      UpdateRelation(
        name = "ManualRelationName",
        newName = Some("CommentToTodo"),
        modelAId = None,
        modelBId = None,
        modelAOnDelete = None,
        modelBOnDelete = None
      )
    )

  }

  "they" should "not propose a DeleteRelation step when relation directives gets added" in {
    val previousSchema =
      """
        |type Todo {
        |  comments: [Comment!]!
        |}
        |type Comment {
        |  text: String
        |  todo: Todo
        |}
      """.stripMargin
    val project = inferSchema(previousSchema)

    val nextSchema =
      """
        |type Todo {
        |  comments: [Comment!]! @relation(name: "ManualRelationName")
        |}
        |type Comment {
        |  text: String
        |  todo: Todo @relation(name: "ManualRelationName")
        |}
      """.stripMargin
    val steps = inferSteps(previousSchema = project, next = nextSchema)

    steps should have(size(3))
    steps should contain allOf (
      UpdateField(
        model = "Todo",
        newModel = "Todo",
        name = "comments",
        newName = None,
        typeName = None,
        isRequired = None,
        isList = None,
        isHidden = None,
        isUnique = None,
        relation = Some(Some("_ManualRelationName")),
        defaultValue = None,
        enum = None
      ),
      UpdateField(
        model = "Comment",
        newModel = "Comment",
        name = "todo",
        newName = None,
        typeName = None,
        isRequired = None,
        isList = None,
        isHidden = None,
        isUnique = None,
        relation = Some(Some("_ManualRelationName")),
        defaultValue = None,
        enum = None
      ),
      UpdateRelation(
        name = "CommentToTodo",
        newName = Some("ManualRelationName"),
        modelAId = None,
        modelBId = None,
        modelAOnDelete = None,
        modelBOnDelete = None
      )
    )
  }

  "they" should "handle ambiguous relations correctly" in {
    val previousSchema =
      """
        |type Todo {
        |  title: String
        |}
        |type Comment {
        |  text: String
        |}
      """.stripMargin
    val project = inferSchema(previousSchema)

    val nextSchema =
      """
        |type Todo {
        |  title: String
        |  comment1: Comment @relation(name: "TodoToComment1")
        |  comment2: Comment @relation(name: "TodoToComment2")
        |}
        |type Comment {
        |  text: String
        |  todo1: Todo @relation(name: "TodoToComment1")
        |  todo2: Todo @relation(name: "TodoToComment2")
        |}
      """.stripMargin
    val steps = inferSteps(previousSchema = project, next = nextSchema)
    steps should have(size(6))
    steps should contain allOf (
      CreateField(
        model = "Todo",
        name = "comment1",
        typeName = "Relation",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = Some("TodoToComment1"),
        defaultValue = None,
        enum = None
      ),
      CreateField(
        model = "Todo",
        name = "comment2",
        typeName = "Relation",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = Some("TodoToComment2"),
        defaultValue = None,
        enum = None
      ),
      CreateField(
        model = "Comment",
        name = "todo1",
        typeName = "Relation",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = Some("TodoToComment1"),
        defaultValue = None,
        enum = None
      ),
      CreateField(
        model = "Comment",
        name = "todo2",
        typeName = "Relation",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = Some("TodoToComment2"),
        defaultValue = None,
        enum = None
      ),
      CreateRelation(
        name = "TodoToComment1",
        modelAName = "Comment",
        modelBName = "Todo",
        modelAOnDelete = OnDelete.SetNull,
        modelBOnDelete = OnDelete.SetNull
      ),
      CreateRelation(
        name = "TodoToComment2",
        modelAName = "Comment",
        modelBName = "Todo",
        modelAOnDelete = OnDelete.SetNull,
        modelBOnDelete = OnDelete.SetNull
      )
    )
  }

  "they" should "detect a change in the onDelete relation argument" in {
    val previousSchema =
      """
        |type Course {
        |  id: ID! @unique
        |	sections: [CourseSection!]! @relation(name: "CourseSections" onDelete: CASCADE)
        |}
        |
        |type CourseSection {
        |  id: ID! @unique
        |  course: Course! @relation(name: "CourseSections")
        |}
      """.stripMargin
    val project = inferSchema(previousSchema)

    val nextSchema =
      """
        |type Course {
        |  id: ID! @unique
        |	sections: [CourseSection!]! @relation(name: "CourseSections")
        |}
        |
        |type CourseSection {
        |  id: ID! @unique
        |  course: Course! @relation(name: "CourseSections")
        |}
      """.stripMargin
    val steps = inferSteps(previousSchema = project, next = nextSchema)
    steps should have(size(1))
    steps should contain(
      UpdateRelation(
        name = "CourseSections",
        newName = None,
        modelAId = None,
        modelBId = None,
        modelAOnDelete = Some(OnDelete.SetNull),
        modelBOnDelete = None
      )
    )
  }

  def inferSchema(schema: String): Schema = {
    inferSchema(Schema(), schema)
  }

  def inferSchema(previous: Schema, schema: String): Schema = {
    val validator = SchemaSyntaxValidator(
      schema,
      SchemaSyntaxValidator.directiveRequirements,
      SchemaSyntaxValidator.reservedFieldsRequirementsForAllConnectors,
      SchemaSyntaxValidator.requiredReservedFields,
      true
    )

    val prismaSdl = validator.generateSDL

    val nextSchema = SchemaInferrer().infer(previous, SchemaMapping.empty, prismaSdl, InferredTables.empty)

    println(s"Relations of infered schema:\n  " + nextSchema.relations)
    nextSchema
  }

  def inferSteps(previousSchema: Schema, next: String): Vector[MigrationStep] = {
    val nextSchema = inferSchema(previousSchema, next)
    println(s"fields of next project:")
    nextSchema.allFields.foreach(println)
    MigrationStepsInferrer().infer(
      previousSchema = previousSchema,
      nextSchema = nextSchema,
      renames = SchemaMapping.empty
    )
  }
}
