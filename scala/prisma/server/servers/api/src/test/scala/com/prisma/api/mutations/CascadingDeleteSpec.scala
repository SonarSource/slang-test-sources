package com.prisma.api.mutations

import com.prisma.api.ApiSpecBase
import com.prisma.shared.models._
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class CascadingDeleteSpec extends FlatSpec with Matchers with ApiSpecBase {

  //region  TOP LEVEL DELETE

  "P1!-C1! relation deleting the parent" should "work if parent is marked marked cascading" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c"}}}){p, c {c}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2"}}}){p, c {c}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","p":{"p":"p2"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(2) }
  }

  "PM-CM relation deleting the parent" should "delete all children if the parent is marked cascading" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.manyToManyRelation("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p",  c: {create:[{c: "c"},  {c: "c2"}]}}){p, c {c}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:[{c: "cx"}, {c: "cx2"}]}}){p, c {c}}}""", project)
    server.query("""mutation{updateC(where:{c:"c2"}, data:{p: {create:{p: "pz"}}}){id}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":[{"c":"cx"},{"c":"cx2"}]},{"p":"pz","c":[]}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"cx","p":[{"p":"p2"}]},{"c":"cx2","p":[{"p":"p2"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "PM-CM relation deleting the parent" should "error if both sides are marked cascading since it would be a circle" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.manyToManyRelation("p", "c", parent, modelAOnDelete = OnDelete.Cascade, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p",  c: {create:[{c: "c"},  {c: "c2"}]}}){p, c {c}}}""", project)
    server.query("""mutation{updateC(where:{c:"c2"}, data:{p: {create:{p: "pz"}}}){id}}""", project)

    server.queryThatMustFail("""mutation{deleteP(where: {p:"p"}){id}}""", project, errorCode = 3043)
    server.query("""query{ps{p, c {c}}}""", project).toString should be(
      """{"data":{"ps":[{"p":"p","c":[{"c":"c"},{"c":"c2"}]},{"p":"pz","c":[{"c":"c2"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "P1!-C1! relation deleting the parent" should "error if both sides are marked marked cascading" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelAOnDelete = OnDelete.Cascade, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c"}}}){p, c {c}}}""", project)

    server.queryThatMustFail("""mutation{deleteP(where: {p:"p"}){id}}""", project, errorCode = 3043)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p","c":{"c":"c"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(2) }
  }

  "P1!-C1! relation deleting the parent" should "error if only child is marked marked cascading" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelAOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c"}}}){p, c {c}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2"}}}){p, c {c}}}""", project)

    server.queryThatMustFail("""mutation{deleteP(where: {p:"p"}){id}}""", project, errorCode = 3042)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p","c":{"c":"c"}},{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c","p":{"p":"p"}},{"c":"c2","p":{"p":"p2"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "P1!-C1!-C1!-GC! relation deleting the parent and child and grandchild if marked cascading" should "work" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)

    server.query("""query{ps{p, c {c, gc{gc}}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2","gc":{"gc":"gc2"}}}]}}""")
    server.query("""query{cs{c, gc{gc}, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","gc":{"gc":"gc2"},"p":{"p":"p2"}}]}}""")
    server.query("""query{gCs{gc, c {c, p{p}}}}""", project).toString should be("""{"data":{"gCs":[{"gc":"gc2","c":{"c":"c2","p":{"p":"p2"}}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(3) }
  }

  "P1!-C1!-C1-GC relation deleting the parent and child marked cascading" should "work but preserve the grandchild" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation("c", "gc", child)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)

    server.query("""query{ps{p, c {c, gc{gc}}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2","gc":{"gc":"gc2"}}}]}}""")
    server.query("""query{cs{c, gc{gc}, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","gc":{"gc":"gc2"},"p":{"p":"p2"}}]}}""")
    server.query("""query{gCs{gc, c {c, p{p}}}}""", project).toString should be(
      """{"data":{"gCs":[{"gc":"gc","c":null},{"gc":"gc2","c":{"c":"c2","p":{"p":"p2"}}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "P1!-C1! relation deleting the parent marked cascading" should "error if the child is required in another non-cascading relation" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation_!("c", "gc", child)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.queryThatMustFail("""mutation{deleteP(where: {p:"p"}){id}}""", project, errorCode = 3042)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p","c":{"c":"c"}},{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c","p":{"p":"p"}},{"c":"c2","p":{"p":"p2"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(6) }
  }

  "If the parent is not cascading nothing on the path" should "be deleted except for the parent" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      child.oneToOneRelation("p", "c", parent, modelAOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation("c", "gc", child)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c","p":null}]}}""")
    server.query("""query{gCs{gc, c {c}}}""", project).toString should be("""{"data":{"gCs":[{"gc":"gc","c":{"c":"c"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(2) }
  }

  "P1!-C1! PM-SC1! relation deleting the parent marked cascading" should "work" in {
    //         P
    //       /   \
    //      C     SC

    val project = SchemaDsl.fromBuilder { schema =>
      val parent    = schema.model("P").field_!("p", _.String, isUnique = true)
      val child     = schema.model("C").field_!("c", _.String, isUnique = true)
      val stepChild = schema.model("SC").field_!("sc", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      parent.oneToManyRelation_!("scs", "p", stepChild, modelAOnDelete = OnDelete.Cascade)
    }

    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c"}}, scs: {create:[{sc: "sc1"},{sc: "sc2"}]}}){p, c {c},scs{sc}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2"}}, scs: {create:[{sc: "sc3"},{sc: "sc4"}]}}){p, c {c},scs{sc}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)

    server.query("""query{ps{p, c {c}, scs {sc}}}""", project).toString should be(
      """{"data":{"ps":[{"p":"p2","c":{"c":"c2"},"scs":[{"sc":"sc3"},{"sc":"sc4"}]}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","p":{"p":"p2"}}]}}""")
    server.query("""query{sCs{sc,  p{p}}}""", project).toString should be("""{"data":{"sCs":[{"sc":"sc3","p":{"p":"p2"}},{"sc":"sc4","p":{"p":"p2"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "P!->C PM->SC relation without backrelations" should "work when deleting the parent marked cascading" in {
    //         P
    //       /   \      not a real circle since from the children there are no backrelations to the parent
    //      C  -  SC

    val project = SchemaDsl.fromBuilder { schema =>
      val parent    = schema.model("P").field_!("p", _.String, isUnique = true)
      val child     = schema.model("C").field_!("c", _.String, isUnique = true)
      val stepChild = schema.model("SC").field_!("sc", _.String, isUnique = true)

      parent.oneToOneRelation_!("c", "doesNotMatter", child, modelAOnDelete = OnDelete.Cascade, isRequiredOnFieldB = false, includeFieldB = false)
      parent.oneToManyRelation("scs", "doesNotMatter", stepChild, modelAOnDelete = OnDelete.Cascade, includeFieldB = false)
      child.oneToOneRelation("sc", "c", stepChild, modelAOnDelete = OnDelete.Cascade)
    }

    database.setup(project)

    server.query("""mutation{createC(data:{c:"c", sc: {create:{sc: "sc"}}}){c, sc{sc}}}""", project)
    server.query("""mutation{createC(data:{c:"c2", sc: {create:{sc: "sc2"}}}){c, sc{sc}}}""", project)
    server.query("""mutation{createP(data:{p:"p", c: {connect:{c: "c"}}, scs: {connect:[{sc: "sc"},{sc: "sc2"}]}}){p, c {c}, scs{sc}}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)

    server.query("""query{ps{p, c {c}, scs {sc}}}""", project).toString should be("""{"data":{"ps":[]}}""")
    server.query("""query{cs{c}}""", project).toString should be("""{"data":{"cs":[{"c":"c2"}]}}""")
    server.query("""query{sCs{sc}}""", project).toString should be("""{"data":{"sCs":[]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(1) }
  }

  "A path that is interrupted since there are nodes missing" should "only cascade up until the gap" in {
    //         P-C-GC-|-D-E
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)
      val levelD     = schema.model("D").field_!("d", _.String, isUnique = true)
      val levelE     = schema.model("E").field_!("e", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      levelD.manyToManyRelation("gc", "d", grandChild, modelBOnDelete = OnDelete.Cascade)
      levelE.manyToManyRelation("d", "e", levelD, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.query("""mutation{createD(data:{d:"d", e: {create:[{e: "e"}]}}){d}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}, gc {gc}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","p":{"p":"p2"},"gc":{"gc":"gc2"}}]}}""")
    server.query("""query{gCs{gc, c {c}, d {d}}}""", project).toString should be("""{"data":{"gCs":[{"gc":"gc2","c":{"c":"c2"},"d":[]}]}}""")
    server.query("""query{ds{d, gc {gc},e {e}}}""", project).toString should be("""{"data":{"ds":[{"d":"d","gc":[],"e":[{"e":"e"}]}]}}""")
    server.query("""query{es{e, d {d}}}""", project).toString should be("""{"data":{"es":[{"e":"e","d":[{"d":"d"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(5) }
  }

  "A deep uninterrupted path" should "cascade all the way down" in {
    //         P-C-GC-D-E
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)
      val levelD     = schema.model("D").field_!("d", _.String, isUnique = true)
      val levelE     = schema.model("E").field_!("e", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      levelD.manyToManyRelation("gc", "d", grandChild, modelBOnDelete = OnDelete.Cascade)
      levelE.manyToManyRelation("d", "e", levelD, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.query("""mutation{createD(data:{d:"d", e: {create:[{e: "e"}]}, gc: {connect:{gc: "gc"}}}){d}}""", project)

    server.query("""mutation{deleteP(where: {p:"p"}){id}}""", project)
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}, gc {gc}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","p":{"p":"p2"},"gc":{"gc":"gc2"}}]}}""")
    server.query("""query{gCs{gc, c {c}, d {d}}}""", project).toString should be("""{"data":{"gCs":[{"gc":"gc2","c":{"c":"c2"},"d":[]}]}}""")
    server.query("""query{ds{d, gc {gc},e {e}}}""", project).toString should be("""{"data":{"ds":[]}}""")
    server.query("""query{es{e, d {d}}}""", project).toString should be("""{"data":{"es":[]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(3) }
  }

  "A deep uninterrupted path" should "error on a required relation violation at the end" in {
    //         P-C-GC-D-E-F!
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)
      val levelD     = schema.model("D").field_!("d", _.String, isUnique = true)
      val levelE     = schema.model("E").field_!("e", _.String, isUnique = true)
      val levelF     = schema.model("F").field_!("f", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      levelD.manyToManyRelation("gc", "d", grandChild, modelBOnDelete = OnDelete.Cascade)
      levelE.manyToManyRelation("d", "e", levelD, modelBOnDelete = OnDelete.Cascade)
      levelF.oneToOneRelation_!("e", "f", levelE)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)
    server.query("""mutation{createD(data:{d:"d", e: {create:[{e: "e", f: {create :{f:"f"}}}]}, gc: {connect:{gc: "gc"}}}){d}}""", project)

    server.queryThatMustFail(
      """mutation{deleteP(where: {p:"p"}){id}}""",
      project,
      errorCode = 3042,
      errorContains = """The change you are trying to make would violate the required relation 'FToE' between F and E"""
    )

    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p","c":{"c":"c"}},{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}, gc {gc}}}""", project).toString should be(
      """{"data":{"cs":[{"c":"c","p":{"p":"p"},"gc":{"gc":"gc"}},{"c":"c2","p":{"p":"p2"},"gc":{"gc":"gc2"}}]}}""")
    server.query("""query{gCs{gc, c {c}, d {d}}}""", project).toString should be(
      """{"data":{"gCs":[{"gc":"gc","c":{"c":"c"},"d":[{"d":"d"}]},{"gc":"gc2","c":{"c":"c2"},"d":[]}]}}""")
    server.query("""query{ds{d, gc {gc},e {e}}}""", project).toString should be("""{"data":{"ds":[{"d":"d","gc":[{"gc":"gc"}],"e":[{"e":"e"}]}]}}""")
    server.query("""query{fs{f, e {e}}}""", project).toString should be("""{"data":{"fs":[{"f":"f","e":{"e":"e"}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(9) }
  }

  "A required relation violation anywhere on the path" should "error and roll back all of the changes" in {

    /**           A           If cascading all the way down to D from A is fine, but deleting C would
      *          /            violate a required relation on E that is not cascading then this should
      *         B             error and not delete anything.
      *          \
      *          C . E
      *          /
      *         D
      */
    val project = SchemaDsl.fromBuilder { schema =>
      val a = schema.model("A").field_!("a", _.DateTime, isUnique = true)
      val b = schema.model("B").field_!("b", _.DateTime, isUnique = true)
      val c = schema.model("C").field_!("c", _.DateTime, isUnique = true)
      val d = schema.model("D").field_!("d", _.DateTime, isUnique = true)
      val e = schema.model("E").field_!("e", _.DateTime, isUnique = true)

      a.oneToOneRelation_!("b", "a", b, modelAOnDelete = OnDelete.Cascade)
      b.oneToOneRelation_!("c", "b", c, modelAOnDelete = OnDelete.Cascade)
      c.manyToManyRelation("d", "c", d, modelAOnDelete = OnDelete.Cascade)
      c.oneToOneRelation_!("e", "c", e)
    }
    database.setup(project)

    server.query("""mutation{createA(data:{a:"2020", b: {create:{b: "2021", c :{create:{c: "2022", e: {create:{e: "2023"}}}}}}}){a}}""", project)
    server.query("""mutation{createA(data:{a:"2030", b: {create:{b: "2031", c :{create:{c: "2032", e: {create:{e: "2033"}}}}}}}){a}}""", project)

    server.query("""mutation{updateC(where: {c: "2022"}, data:{d: {create:[{d: "2024"},{d: "2025"}] }}){c}}""", project)
    server.query("""mutation{updateC(where: {c: "2032"}, data:{d: {create:[{d: "2034"},{d: "2035"}] }}){c}}""", project)

    server.queryThatMustFail(
      """mutation{deleteA(where: {a:"2020"}){a}}""",
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'CToE' between C and E"
    )
  }

  "A required relation violation on the parent" should "roll back all cascading deletes on the path" in {

    /**           A           If A!<->D! ia not marked cascading an existing D should cause all the deletes to fail
      *         / | :         even if A<->B, A<->C and C<->E could successfully cascade.
      *        B  C  D
      *          |
      *          E
      */
    val project = SchemaDsl.fromBuilder { schema =>
      val a = schema.model("A").field_!("a", _.String, isUnique = true)
      val b = schema.model("B").field_!("b", _.String, isUnique = true)
      val c = schema.model("C").field_!("c", _.String, isUnique = true)
      val d = schema.model("D").field_!("d", _.String, isUnique = true)
      val e = schema.model("E").field_!("e", _.String, isUnique = true)

      a.oneToOneRelation_!("d", "a", d)
      a.oneToOneRelation_!("b", "a", b, modelAOnDelete = OnDelete.Cascade)
      a.manyToManyRelation("c", "a", c, modelAOnDelete = OnDelete.Cascade)
      c.oneToOneRelation_!("e", "c", e, modelAOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query(
      """mutation{createA(data:{a:"a", 
        |                       b: {create: {b: "b"}},
        |                       c: {create:[{c: "c1", e: {create:{e: "e"}}},{c: "c2", e: {create:{e: "e2"}}}]}, 
        |                       d: {create: {d: "d"}}
        |                      }){a}}""".stripMargin,
      project
    )

    server.queryThatMustFail(
      """mutation{deleteA(where: {a:"a"}){a}}""",
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'AToD' between A and D"
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(7) }
  }

  "Several relations between the same model" should "be handled correctly" in {

    /**           A           If there are two relations between B and C and only one of them is marked
      *          /            cascading, then only the nodes connected to C's which are connected to B
      *         B             by this relations should be deleted.
      *        /  :
      *       Cs   C
      *        \ /
      *         D
      */
    val project = SchemaDsl.fromBuilder { schema =>
      val a = schema.model("A").field_!("a", _.Float, isUnique = true)
      val b = schema.model("B").field_!("b", _.Float, isUnique = true)
      val c = schema.model("C").field_!("c", _.Float, isUnique = true)
      val d = schema.model("D").field_!("d", _.Float, isUnique = true)

      a.oneToOneRelation("b", "a", b, modelAOnDelete = OnDelete.Cascade)
      b.manyToManyRelation("cs", "bs", c, modelAOnDelete = OnDelete.Cascade, relationName = Some("Relation1"))
      c.manyToManyRelation("d", "c", d, modelAOnDelete = OnDelete.Cascade, relationName = Some("Relation2"))
      b.oneToOneRelation("c", "b", c)
    }
    database.setup(project)

    server.query("""mutation{createA(data:{a: 10.10, b: {create:{b: 11.11}}}){a}}""", project)

    server.query("""mutation{updateB(where: {b: 11.11}, data:{cs: {create:[{c: 12.12},{c: 12.13}]}}){b}}""", project)
    server.query("""mutation{updateB(where: {b: 11.11}, data:{c: {create:{c: 12.99}}}){b}}""", project)

    server.query("""mutation{updateC(where: {c: 12.12}, data:{d: {create:{d: 13.13}}}){c}}""", project)
    server.query("""mutation{updateC(where: {c: 12.99}, data:{d: {create:{d: 13.99}}}){c}}""", project)

    server.query("""mutation{deleteA(where: {a:10.10}){a}}""", project)

    server.query("""query{as{a, b {b}}}""", project).toString should be("""{"data":{"as":[]}}""")
    server.query("""query{bs{b, c {c}, cs {c}}}""", project).toString should be("""{"data":{"bs":[]}}""")
    server.query("""query{cs{c, d {d}}}""", project).toString should be("""{"data":{"cs":[{"c":12.99,"d":[{"d":13.99}]}]}}""")
    server.query("""query{ds{d}}""", project).toString should be("""{"data":{"ds":[{"d":13.99}]}}""")
  }
  //endregion

  //region  NESTED DELETE

  "NESTING P1!-C1! relation deleting the parent" should "work if parent is marked cascading but error on returning previous values" in {
    //         P-C
    val project = SchemaDsl.fromBuilder { schema =>
      val parent = schema.model("P").field_!("p", _.String, isUnique = true)
      val child  = schema.model("C").field_!("c", _.String, isUnique = true)

      child.oneToOneRelation_!("p", "c", parent, modelBOnDelete = OnDelete.Cascade)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c"}}}){p, c {c}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2"}}}){p, c {c}}}""", project)

    server.queryThatMustFail("""mutation{updateC(where: {c:"c"} data: {p: {delete: true}}){id}}""",
                             project,
                             errorCode = 3039,
                             errorContains = "No Node for the model")
    server.query("""query{ps{p, c {c}}}""", project).toString should be("""{"data":{"ps":[{"p":"p2","c":{"c":"c2"}}]}}""")
    server.query("""query{cs{c, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","p":{"p":"p2"}}]}}""")
    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(2) }
  }

  "P1-C1-C1!-GC! relation updating the parent to delete the child and grandchild if marked cascading" should "work" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      child.oneToOneRelation("p", "c", parent)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.query("""mutation{updateP(where: {p:"p"}, data: { c: {delete: true}}){id}}""", project)

    server.query("""query{ps{p, c {c, gc{gc}}}}""", project).toString should be(
      """{"data":{"ps":[{"p":"p","c":null},{"p":"p2","c":{"c":"c2","gc":{"gc":"gc2"}}}]}}""")
    server.query("""query{cs{c, gc{gc}, p {p}}}""", project).toString should be("""{"data":{"cs":[{"c":"c2","gc":{"gc":"gc2"},"p":{"p":"p2"}}]}}""")
    server.query("""query{gCs{gc, c {c, p{p}}}}""", project).toString should be("""{"data":{"gCs":[{"gc":"gc2","c":{"c":"c2","p":{"p":"p2"}}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(4) }
  }

  "P1!-C1!-C1!-GC! relation updating the parent to delete the child and grandchild if marked cascading" should "error if the child is required on parent" in {
    //         P-C-GC
    val project = SchemaDsl.fromBuilder { schema =>
      val parent     = schema.model("P").field_!("p", _.String, isUnique = true)
      val child      = schema.model("C").field_!("c", _.String, isUnique = true)
      val grandChild = schema.model("GC").field_!("gc", _.String, isUnique = true)

      grandChild.oneToOneRelation_!("c", "gc", child, modelBOnDelete = OnDelete.Cascade)
      child.oneToOneRelation_!("p", "c", parent)
    }
    database.setup(project)

    server.query("""mutation{createP(data:{p:"p", c: {create:{c: "c", gc :{create:{gc: "gc"}}}}}){p, c {c, gc{gc}}}}""", project)
    server.query("""mutation{createP(data:{p:"p2", c: {create:{c: "c2", gc :{create:{gc: "gc2"}}}}}){p, c {c,gc{gc}}}}""", project)

    server.queryThatMustFail(
      """mutation{updateP(where: {p:"p"}, data: { c: {delete: true}}){id}}""",
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'CToP' between C and P"
    )

    server.query("""query{ps{p, c {c, gc{gc}}}}""", project).toString should be(
      """{"data":{"ps":[{"p":"p","c":{"c":"c","gc":{"gc":"gc"}}},{"p":"p2","c":{"c":"c2","gc":{"gc":"gc2"}}}]}}""")
    server.query("""query{cs{c, gc{gc}, p {p}}}""", project).toString should be(
      """{"data":{"cs":[{"c":"c","gc":{"gc":"gc"},"p":{"p":"p"}},{"c":"c2","gc":{"gc":"gc2"},"p":{"p":"p2"}}]}}""")
    server.query("""query{gCs{gc, c {c, p{p}}}}""", project).toString should be(
      """{"data":{"gCs":[{"gc":"gc","c":{"c":"c","p":{"p":"p"}}},{"gc":"gc2","c":{"c":"c2","p":{"p":"p2"}}}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_RelayId").await should be(6) }
  }
  //endregion
}
