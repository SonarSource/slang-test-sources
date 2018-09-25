package com.prisma.api

import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{Assertion, FlatSpec, Matchers}

class Queries extends FlatSpec with Matchers with ApiSpecBase {

  "schema" should "include simple API features" in {
    val project = SchemaDsl.fromBuilder { schema =>
      schema.model("Car").field("wheelCount", _.Int).field_!("name", _.String).field_!("createdAt", _.DateTime).field_!("updatedAt", _.DateTime)
    }
    database.setup(project)

    // MUTATIONS

    val newId = server.query("""mutation { createCar(data: {wheelCount: 7, name: "Sleven"}){id} }""", project).pathAsString("data.createCar.id")
    server
      .query(s"""mutation { updateCar(where: {id: "${newId}"} data:{ wheelCount: 8} ){wheelCount} }""", project)
      .pathAsLong("data.updateCar.wheelCount") should be(8)
    val idToDelete =
      server.query("""mutation { createCar(data: {wheelCount: 7, name: "Sleven"}){id} }""", project).pathAsString("data.createCar.id")
    server
      .query(s"""mutation { deleteCar(where: {id: "${idToDelete}"}){wheelCount} }""", project)
      .pathAsLong("data.deleteCar.wheelCount") should be(7)

    // QUERIES

    server.query("""{cars{wheelCount}}""", project).pathAsLong("data.cars.[0].wheelCount") should be(8)
    server.query("""{carsConnection{edges{node{wheelCount}}}}""", project).pathAsLong("data.carsConnection.edges.[0].node.wheelCount") should be(8)
    server.query(s"""{car(where: {id:"${newId}"}){wheelCount}}""", project).pathAsLong("data.car.wheelCount") should be(8)
    ifConnectorIsActive { server.query(s"""{node(id:"${newId}"){... on Car { wheelCount }}}""", project).pathAsLong("data.node.wheelCount") should be(8) }
  }

  "schema" should "include old nested mutations" in {
    val project = SchemaDsl.fromBuilder { schema =>
      val car = schema.model("Car").field("wheelCount", _.Int).field_!("name", _.String).field_!("createdAt", _.DateTime).field_!("updatedAt", _.DateTime)
      schema.model("Wheel").manyToOneRelation("car", "wheels", car).field_!("size", _.Int).field_!("createdAt", _.DateTime).field_!("updatedAt", _.DateTime)

    }
    database.setup(project)

    // MUTATIONS

    server
      .query(
        """mutation { 
          |   createCar(data: {
          |     wheelCount: 7, 
          |     name: "Sleven", 
          |     wheels: { 
          |       create: [{size: 20}, {size: 19}]
          |     }
          |   }){
          |     wheels { size } 
          |     } 
          |}""".stripMargin,
        project
      )
      .pathAsLong("data.createCar.wheels.[0].size") should be(20)

    // QUERIES

    server.query("""{cars{wheels{size}}}""", project).pathAsLong("data.cars.[0].wheels.[0].size") should be(20)
  }
}
