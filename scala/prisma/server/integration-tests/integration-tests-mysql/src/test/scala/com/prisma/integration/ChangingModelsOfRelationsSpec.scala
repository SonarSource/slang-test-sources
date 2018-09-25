package com.prisma.integration

import org.scalatest.{FlatSpec, Matchers}

class ChangingModelsOfRelationsSpec extends FlatSpec with Matchers with IntegrationBaseSpec {

  "Changing the model a relation points to" should "delete the existing relation data" in {

    val schema =
      """type A {
        |  a: String! @unique
        |  b: B @relation(name: "Relation")
        |}
        |
        |type B {
        |  b: String! @unique
        |  a: A @relation(name: "Relation")
        |}
        |
        |type C {
        |  c: String! @unique
        |}"""

    val (project, _) = setupProject(schema)

    apiServer.query("""mutation{createA(data:{a:"A", b:{create:{b: "B"}}}){a}}""", project)
    apiServer.query("""mutation{createC(data:{c:"C"}){c}}""", project)

    val schema1 =
      """type A {
        |  a: String! @unique
        |  b: C @relation(name: "Relation")
        |}
        |
        |type B {
        |  b: String! @unique
        |}
        |
        |type C {
        |  c: String! @unique
        |  a: A @relation(name: "Relation")
        |}"""

    val updatedProject = deployServer.deploySchemaThatMustWarnAndReturnProject(project, schema1, true)

    val as = apiServer.query("""{as{a, b{c}}}""", updatedProject)
    as.toString should be("""{"data":{"as":[{"a":"A","b":null}]}}""")
  }

  "Deleting one of the models of a relation and replacing it with a new one" should "work" in {

    val schema =
      """type A {
        |  a: String! @unique
        |  b: B @relation(name: "Relation")
        |}
        |
        |type B {
        |  b: String! @unique
        |  a: A @relation(name: "Relation")
        |}"""

    val (project, _) = setupProject(schema)

    apiServer.query("""mutation{createA(data:{a:"A", b:{create:{b: "B"}}}){a}}""", project)

    val schema1 =
      """type A {
        |  a: String! @unique
        |  b: C @relation(name: "Relation")
        |}
        |
        |type C {
        |  b: String! @unique
        |  a: A @relation(name: "Relation")
        |}"""

    val updatedProject = deployServer.deploySchemaThatMustWarnAndReturnProject(project, schema1, true)

    val as = apiServer.query("""{as{a, b{b}}}""", updatedProject)
    as.toString should be("""{"data":{"as":[{"a":"A","b":null}]}}""")
  }

  "Renaming a model with @rename but keeping its relation" should "work" in {

    val schema =
      """type A {
        |  a: String! @unique
        |  b: B @relation(name: "Relation")
        |}
        |
        |type B {
        |  b: String! @unique
        |  a: A @relation(name: "Relation")
        |}"""

    val (project, _) = setupProject(schema)

    apiServer.query("""mutation{createA(data:{a:"A", b:{create:{b: "B"}}}){a}}""", project)

    val schema1 =
      """type A {
        |  a: String! @unique
        |  b: C @relation(name: "Relation")
        |}
        |
        |type C @rename(oldName: "B"){
        |  b: String! @unique
        |  a: A @relation(name: "Relation")
        |}"""

    val updatedProject = deployServer.deploySchema(project, schema1)

    val as = apiServer.query("""{as{a, b{b}}}""", updatedProject)
    as.toString should be("""{"data":{"as":[{"a":"A","b":{"b":"B"}}]}}""")

    val cs = apiServer.query("""{cs{b, a{a}}}""", updatedProject)
    cs.toString should be("""{"data":{"cs":[{"b":"B","a":{"a":"A"}}]}}""")
  }
}
