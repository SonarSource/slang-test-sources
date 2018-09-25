package com.prisma.deploy.migration.inference

import com.prisma.deploy.connector.InferredTables
import com.prisma.deploy.migration.validation.SchemaSyntaxValidator
import com.prisma.shared.models.{OnDelete, Schema}
import com.prisma.shared.schema_dsl.{SchemaDsl, TestProject}
import org.scalatest.{Matchers, WordSpec}

class SchemaInfererOnDeleteSpec extends WordSpec with Matchers {

  val inferer      = SchemaInferrer()
  val emptyProject = TestProject.empty

  "Inferring onDelete relationDirectives" should {
    "work if one side provides onDelete" in {
      val types =
        """
          |type Todo {
          |  comments: [Comment!] @relation(name:"MyRelationName" onDelete: CASCADE)
          |}
          |
          |type Comment {
          |  todo: Todo!
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(1))
      val relation = schema.getRelationByName_!("MyRelationName")
      relation.modelAName should equal("Comment")
      relation.modelAOnDelete should equal(OnDelete.SetNull)
      relation.modelBName should equal("Todo")
      relation.modelBOnDelete should equal(OnDelete.Cascade)
    }

    "work if both sides provide onDelete" in {
      val types =
        """
          |type Todo {
          |  comments: [Comment!] @relation(name:"MyRelationName" onDelete: CASCADE)
          |}
          |
          |type Comment {
          |  todo: Todo! @relation(name:"MyRelationName" onDelete: SET_NULL)
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(1))
      val relation = schema.getRelationByName_!("MyRelationName")
      relation.modelAName should equal("Comment")
      relation.modelAOnDelete should equal(OnDelete.SetNull)
      relation.modelBName should equal("Todo")
      relation.modelBOnDelete should equal(OnDelete.Cascade)
    }

    "work if second side provides onDelete" in {
      val types =
        """
          |type Todo {
          |  comments: [Comment!] @relation(name:"MyRelationName")
          |}
          |
          |type Comment {
          |  todo: Todo! @relation(name:"MyRelationName" onDelete: CASCADE)
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(1))
      val relation = schema.getRelationByName_!("MyRelationName")
      relation.modelAName should equal("Comment")
      relation.modelAOnDelete should equal(OnDelete.Cascade)
      relation.modelBName should equal("Todo")
      relation.modelBOnDelete should equal(OnDelete.SetNull)
    }

    "handle two relations between the same models" in {
      val types =
        """
          |type Todo {
          |  comments: [Comment!] @relation(name:"MyRelationName")
          |  comments2: [Comment!] @relation(name:"MyRelationName2" onDelete: CASCADE)
          |}
          |
          |type Comment {
          |  todo: Todo! @relation(name:"MyRelationName" onDelete: CASCADE)
          |  todo2: Todo! @relation(name:"MyRelationName2")
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(2))
      val relation = schema.getRelationByName_!("MyRelationName")
      relation.modelAName should equal("Comment")
      relation.modelAOnDelete should equal(OnDelete.Cascade)
      relation.modelBName should equal("Todo")
      relation.modelBOnDelete should equal(OnDelete.SetNull)

      val relation2 = schema.getRelationByName_!("MyRelationName2")
      relation2.modelAName should equal("Comment")
      relation2.modelAOnDelete should equal(OnDelete.SetNull)
      relation2.modelBName should equal("Todo")
      relation2.modelBOnDelete should equal(OnDelete.Cascade)
    }

    "handle two relations between the same models 2" in {
      val types =
        """
          |type Todo {
          |  comments: [Comment!] @relation(name:"MyRelationName", onDelete: CASCADE)
          |  comments2: [Comment!] @relation(name:"MyRelationName2", onDelete: CASCADE)
          |}
          |
          |type Comment {
          |  todo: Todo! @relation(name:"MyRelationName", onDelete: CASCADE)
          |  todo2: Todo! @relation(name:"MyRelationName2",onDelete: CASCADE)
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(2))
      val relation = schema.getRelationByName_!("MyRelationName")
      relation.modelAName should equal("Comment")
      relation.modelAOnDelete should equal(OnDelete.Cascade)
      relation.modelBName should equal("Todo")
      relation.modelBOnDelete should equal(OnDelete.Cascade)

      val relation2 = schema.getRelationByName_!("MyRelationName2")
      relation2.modelAName should equal("Comment")
      relation2.modelAOnDelete should equal(OnDelete.Cascade)
      relation2.modelBName should equal("Todo")
      relation2.modelBOnDelete should equal(OnDelete.Cascade)
    }

    "handle relations between the three models" in {
      val types =
        """
          |type Parent {
          |  child: Child! @relation(name:"ParentToChild", onDelete: CASCADE)
          |  stepChild: StepChild! @relation(name:"ParentToStepChild", onDelete: CASCADE)
          |}
          |
          |type Child {
          |  parent: Parent!
          |}
          |
          |type StepChild {
          |  parent: Parent!
          |}
        """.stripMargin.trim()
      val schema = infer(emptyProject.schema, types)

      schema.relations should have(size(2))
      val relation = schema.getRelationByName_!("ParentToChild")
      relation.modelAName should equal("Child")
      relation.modelAOnDelete should equal(OnDelete.SetNull)
      relation.modelBName should equal("Parent")
      relation.modelBOnDelete should equal(OnDelete.Cascade)

      val relation2 = schema.getRelationByName_!("ParentToStepChild")
      relation2.modelAName should equal("Parent")
      relation2.modelAOnDelete should equal(OnDelete.Cascade)
      relation2.modelBName should equal("StepChild")
      relation2.modelBOnDelete should equal(OnDelete.SetNull)
    }
  }

  def infer(schema: Schema, types: String, mapping: SchemaMapping = SchemaMapping.empty): Schema = {
    val validator = SchemaSyntaxValidator(
      types,
      SchemaSyntaxValidator.directiveRequirements,
      SchemaSyntaxValidator.reservedFieldsRequirementsForAllConnectors,
      SchemaSyntaxValidator.requiredReservedFields,
      true
    )

    val prismaSdl = validator.generateSDL

    SchemaInferrer().infer(schema, SchemaMapping.empty, prismaSdl, InferredTables.empty)
  }
}
