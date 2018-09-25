package com.prisma.deploy.migration.inference

import com.prisma.deploy.specutils.DeploySpecBase
import com.prisma.shared.models._
import com.prisma.shared.schema_dsl.SchemaDsl
import com.prisma.shared.schema_dsl.SchemaDsl.SchemaBuilder
import org.scalatest.{FlatSpec, Matchers}

class MigrationStepsInferrerSpec extends FlatSpec with Matchers with DeploySpecBase {

  /**
    * Basic tests
    */
  "No changes" should "create no migration steps" in {
    val renames = SchemaMapping.empty

    val previousProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }
    val nextProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }

    val inferrer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = inferrer.evaluate()

    steps shouldBe empty
  }

  "Creating models" should "create CreateModel and CreateField migration steps" in {
    val renames = SchemaMapping.empty

    val previousProject = SchemaDsl.fromBuilder { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }
    val nextProject = SchemaDsl.fromBuilder { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
      schema.model("Test2").field("c", _.String).field("d", _.Int)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 4
    steps should contain allOf (
      CreateModel("Test2"),
      CreateField("Test2", "id", "GraphQLID", isRequired = true, isList = false, isUnique = true, None, None, None),
      CreateField("Test2", "c", "String", isRequired = false, isList = false, isUnique = false, None, None, None),
      CreateField("Test2", "d", "Int", isRequired = false, isList = false, isUnique = false, None, None, None)
    )
  }

  "Deleting models" should "create DeleteModel migration steps" in {
    val renames = SchemaMapping.empty

    val previousProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
      schema.model("Test2").field("c", _.String).field("d", _.Int)
    }

    val nextProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 1
    steps.last shouldBe DeleteModel("Test2")
  }

  "Updating models" should "create UpdateModel migration steps" in {
    val renames = SchemaMapping(
      models = Vector(Mapping(previous = "Test", next = "Test2"))
    )

    val previousProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }
    val nextProject = SchemaBuilder() { schema =>
      schema.model("Test2").field("a", _.String).field("b", _.Int)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 2
    steps.last shouldBe UpdateModel("__Test2", "Test2")
  }

  "Creating fields" should "create CreateField migration steps" in {
    val renames = SchemaMapping.empty

    val previousProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String)
    }
    val nextProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 1
    steps.last shouldBe CreateField("Test", "b", "Int", isRequired = false, isList = false, isUnique = false, None, None, None)
  }

  "Deleting fields" should "create DeleteField migration steps" in {
    val renames = SchemaMapping.empty

    val previousProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String).field("b", _.Int)
    }
    val nextProject = SchemaBuilder() { schema =>
      schema.model("Test").field("a", _.String)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 1
    steps.last shouldBe DeleteField("Test", "b")
  }

  "Updating fields" should "create UpdateField migration steps" in {
    val renames = SchemaMapping(
      fields = Vector(
        FieldMapping("Test", "a", "Test", "a2")
      )
    )

    val previousProject = SchemaBuilder() { schema =>
      schema
        .model("Test")
        .field_!("id", _.Cuid, isUnique = true)
        .field("a", _.String)
        .field("b", _.String)
        .field("c", _.String)
        .field("d", _.String)
        .field("e", _.String)
    }

    val nextProject = SchemaBuilder() { schema =>
      schema
        .model("Test")
        .field_!("id", _.Cuid, isUnique = true, isHidden = true) // Id field hidden
        .field("a2", _.String) // Rename
        .field("b", _.Int) // Type change
        .field_!("c", _.String) // Now required
        .field("d", _.String, isList = true) // Now a list
        .field("e", _.String, isUnique = true) // Now unique
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames)
    val steps    = proposer.evaluate()

    steps.length shouldBe 6
    steps should contain allOf (
      UpdateField("Test", "Test", "a", Some("a2"), None, None, None, None, None, None, None, None),
      UpdateField("Test", "Test", "b", None, Some("Int"), None, None, None, None, None, None, None),
      UpdateField("Test", "Test", "c", None, None, Some(true), None, None, None, None, None, None),
      UpdateField("Test", "Test", "d", None, None, None, Some(true), None, None, None, None, None),
      UpdateField("Test", "Test", "e", None, None, None, None, Some(true), None, None, None, None),
      UpdateField("Test", "Test", "id", None, None, None, None, None, Some(true), None, None, None)
    )
  }

  "Creating Relations" should "create CreateRelation and CreateField migration steps" in {
    val previousProject = SchemaBuilder() { schema =>
      schema.model("Comment").field("text", _.String)
      schema
        .model("Todo")
        .field("title", _.String)
    }

    val nextProject = SchemaBuilder() { schema =>
      val comment = schema.model("Comment").field("text", _.String)
      schema
        .model("Todo")
        .field("title", _.String)
        .oneToManyRelation_!("comments", "todo", comment)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, SchemaMapping.empty)
    val steps    = proposer.evaluate()

    steps.length shouldBe 3
    val relationName = nextProject.relations.head.name
    steps should contain allOf (
      CreateField(
        model = "Todo",
        name = "comments",
        typeName = "Relation",
        isRequired = false,
        isList = true,
        isUnique = false,
        relation = Some(relationName),
        defaultValue = None,
        enum = None
      ),
      CreateField(
        model = "Comment",
        name = "todo",
        typeName = "Relation",
        isRequired = true,
        isList = false,
        isUnique = false,
        relation = Some(relationName),
        defaultValue = None,
        enum = None
      ),
      CreateRelation(
        name = relationName,
        modelAName = "Todo",
        modelBName = "Comment",
        modelAOnDelete = OnDelete.SetNull,
        modelBOnDelete = OnDelete.SetNull
      )
    )
  }

  "Deleting Relations" should "create DeleteRelation and DeleteField migration steps" in {
    val previousProject = SchemaBuilder() { schema =>
      val comment = schema.model("Comment").field("text", _.String)
      schema
        .model("Todo")
        .field("title", _.String)
        .oneToManyRelation_!("comments", "todo", comment)
    }

    val nextProject = SchemaBuilder() { schema =>
      schema.model("Comment").field("text", _.String)
      schema
        .model("Todo")
        .field("title", _.String)
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, SchemaMapping.empty)
    val steps    = proposer.evaluate()

    steps should have(size(3))
    steps should contain allOf (
      DeleteField("Todo", "comments"),
      DeleteField("Comment", "todo"),
      DeleteRelation(previousProject.relations.head.name)
    )
  }

  "Updating Relations" should "create UpdateRelation steps (even when there are lots of renames)" in {
    val previousProject = SchemaDsl() { schema =>
      val comment = schema.model("Comment")
      schema.model("Todo").oneToManyRelation("comments", "todo", comment, relationName = Some("CommentToTodo"))
    }

    val nextProject = SchemaBuilder() { schema =>
      val comment = schema.model("CommentNew")
      schema.model("TodoNew").oneToManyRelation("commentsNew", "todoNew", comment, relationName = Some("CommentNewToTodoNew"))
    }

    val mappings = SchemaMapping(
      models = Vector(
        Mapping(previous = "Todo", next = "TodoNew"),
        Mapping(previous = "Comment", next = "CommentNew")
      ),
      fields = Vector(
        FieldMapping(previousModel = "Todo", previousField = "comments", nextModel = "TodoNew", nextField = "commentsNew"),
        FieldMapping(previousModel = "Comment", previousField = "todo", nextModel = "CommentNew", nextField = "todoNew")
      )
    )

    val inferrer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, mappings)
    val steps    = inferrer.evaluate()

    steps should have(size(7))
    steps should contain(
      UpdateRelation("CommentToTodo", newName = Some("CommentNewToTodoNew"), modelAId = Some("TodoNew"), modelBId = Some("CommentNew"), None, None))
    steps should contain(UpdateModel("Comment", newName = "__CommentNew"))
    steps should contain(UpdateModel("Todo", newName = "__TodoNew"))
    steps should contain(UpdateModel("__CommentNew", newName = "CommentNew"))
    steps should contain(UpdateModel("__TodoNew", newName = "TodoNew"))
    steps should contain(
      UpdateField("Comment", "CommentNew", "todo", Some("todoNew"), None, None, None, None, None, Some(Some("_CommentNewToTodoNew")), None, None))
    steps should contain(
      UpdateField("Todo", "TodoNew", "comments", Some("commentsNew"), None, None, None, None, None, Some(Some("_CommentNewToTodoNew")), None, None))
  }

  // TODO: this spec probably cannot be fulfilled. And it probably does need to because the NextProjectInferer guarantees that those swaps cannot occur. Though this must be verified by extensive testing.
  "Switching modelA and modelB in a Relation" should "not generate any migration step" ignore {
    val relationName = "TodoToComments"
    val previousProject = SchemaBuilder() { schema =>
      val comment = schema.model("Comment")
      val todo    = schema.model("Todo")
      todo.oneToManyRelation("comments", "todo", comment, relationName = Some(relationName))
    }

    val nextProject = SchemaBuilder() { schema =>
      val comment = schema.model("Comment")
      val todo    = schema.model("Todo")
      comment.manyToOneRelation("todo", "comments", todo, relationName = Some(relationName))
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, SchemaMapping.empty)
    val steps    = proposer.evaluate()

    steps should have(size(0))
  }

  "Creating and using Enums" should "create CreateEnum and CreateField migration steps" in {
    val previousProject = SchemaBuilder() { schema =>
      schema.model("Todo")
    }

    val nextProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val proposer = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, SchemaMapping.empty)
    val steps    = proposer.evaluate()

    steps should have(size(2))
    steps should contain allOf (
      CreateEnum("TodoStatus", Seq("Active", "Done")),
      CreateField(
        model = "Todo",
        name = "status",
        typeName = "Enum",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = None,
        defaultValue = None,
        enum = Some(nextProject.enums.head.name)
      )
    )
  }

  "Updating an Enum Name" should "create one UpdateEnum and one UpdateField for each field using that Enum" in {
    val renames = SchemaMapping(enums = Vector(Mapping(previous = "TodoStatus", next = "TodoStatusNew")))

    val previousProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val nextProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatusNew", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val steps = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames).evaluate()

    steps should have(size(2))
    steps should contain allOf (
      UpdateEnum(
        name = "TodoStatus",
        newName = Some("TodoStatusNew"),
        values = None
      ),
      UpdateField(
        model = "Todo",
        newModel = "Todo",
        name = "status",
        newName = None,
        typeName = None,
        isRequired = None,
        isList = None,
        isHidden = None,
        isUnique = None,
        relation = None,
        defaultValue = None,
        enum = Some(Some("TodoStatusNew"))
      )
    )
  }

  "Updating the values of an Enum" should "create one UpdateEnum step" in {
    val renames = SchemaMapping.empty
    val previousProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val nextProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "AbsolutelyDone"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val steps = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames).evaluate()

    steps should have(size(1))
    steps should contain(
      UpdateEnum(
        name = "TodoStatus",
        newName = None,
        values = Some(Vector("Active", "AbsolutelyDone"))
      )
    )
  }

  // Regression
  "Enums" should "not be displayed as updated if they haven't been touched in a deploy" in {
    val renames = SchemaMapping(
      enums = Vector()
    )

    val previousProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("status", _.Enum, enum = Some(enum))
    }

    val nextProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
        .field("someField", _.String)
        .field("status", _.Enum, enum = Some(enum))
    }

    val steps = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames).evaluate()
    steps should have(size(1))
    steps should contain(
      CreateField(
        model = "Todo",
        name = "someField",
        typeName = "String",
        isRequired = false,
        isList = false,
        isUnique = false,
        relation = None,
        defaultValue = None,
        enum = None
      )
    )
  }

  "Removing Enums" should "create an DeleteEnum step" in {
    val renames = SchemaMapping.empty
    val previousProject = SchemaBuilder() { schema =>
      val enum = schema.enum("TodoStatus", Vector("Active", "Done"))
      schema
        .model("Todo")
    }

    val nextProject = SchemaBuilder() { schema =>
      schema.model("Todo")
    }

    val steps = MigrationStepsInferrerImpl(previousProject.schema, nextProject.schema, renames).evaluate()

    steps should have(size(1))
    steps should contain(
      DeleteEnum(
        name = "TodoStatus"
      )
    )
  }
}
