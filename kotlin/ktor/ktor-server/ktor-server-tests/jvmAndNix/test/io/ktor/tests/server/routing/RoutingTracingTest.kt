/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.server.routing

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.header
import io.ktor.server.testing.*
import kotlin.test.*

class RoutingTracingTest {

    @Test
    fun testRoutingGetBar() {
        val trace = tracingApplication {
            assertEquals("/bar", client.get("/bar").bodyAsText())
        }

        assertEquals(
            """
    Trace for [bar]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:1 -> SUCCESS @ /bar
        /bar/(method:GET), segment:1 -> SUCCESS @ /bar/(method:GET)
      /baz, segment:0 -> FAILURE "Selector didn't match" @ /baz
      /{param}, segment:0 -> FAILURE "Better match was already found" @ /{param}
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "bar" -> "(method:GET)"
    Route resolve result:
      SUCCESS @ /bar/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingBarX() {
        val trace = tracingApplication {
            assertEquals("/{param}/x", client.get("/bar/x").bodyAsText())
        }

        assertEquals(
            """
    Trace for [bar, x]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:1 -> SUCCESS @ /bar
        /bar/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /bar/(method:GET)
      /baz, segment:0 -> FAILURE "Selector didn't match" @ /baz
      /{param}, segment:1 -> SUCCESS; Parameters [param=[bar]] @ /{param}
        /{param}/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /{param}/(method:GET)
        /{param}/x, segment:2 -> SUCCESS @ /{param}/x
          /{param}/x/(method:GET), segment:2 -> SUCCESS @ /{param}/x/(method:GET)
          /{param}/x/z, segment:2 -> FAILURE "Selector didn't match" @ /{param}/x/z
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "{param}" -> "x" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [param=[bar]] @ /{param}/x/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingBazX() {
        val trace = tracingApplication {
            assertEquals("/baz/x", client.get("/baz/x").bodyAsText())
        }

        assertEquals(
            """
    Trace for [baz, x]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:1 -> SUCCESS @ /baz
        /baz/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /baz/(method:GET)
        /baz/x, segment:2 -> SUCCESS @ /baz/x
          /baz/x/(method:GET), segment:2 -> SUCCESS @ /baz/x/(method:GET)
          /baz/x/{optional?}, segment:2 -> FAILURE "Better match was already found" @ /baz/x/{optional?}
        /baz/{y}, segment:1 -> FAILURE "Better match was already found" @ /baz/{y}
      /{param}, segment:0 -> FAILURE "Better match was already found" @ /{param}
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "baz" -> "x" -> "(method:GET)"
    Route resolve result:
      SUCCESS @ /baz/x/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingBazDoo() {
        val trace = tracingApplication {
            assertEquals("/baz/{y}", client.get("/baz/doo").bodyAsText())
        }

        assertEquals(
            """
    Trace for [baz, doo]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:1 -> SUCCESS @ /baz
        /baz/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /baz/(method:GET)
        /baz/x, segment:1 -> FAILURE "Selector didn't match" @ /baz/x
        /baz/{y}, segment:2 -> SUCCESS; Parameters [y=[doo]] @ /baz/{y}
          /baz/{y}/(method:GET), segment:2 -> SUCCESS @ /baz/{y}/(method:GET)
          /baz/{y}/value, segment:2 -> FAILURE "Selector didn't match" @ /baz/{y}/value
      /{param}, segment:0 -> FAILURE "Better match was already found" @ /{param}
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "baz" -> "{y}" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [y=[doo]] @ /baz/{y}/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingBazXZ() {
        val trace = tracingApplication {
            assertEquals("/baz/x/{optional?}", client.get("/baz/x/z").bodyAsText())
        }

        assertEquals(
            """
    Trace for [baz, x, z]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:1 -> SUCCESS @ /baz
        /baz/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /baz/(method:GET)
        /baz/x, segment:2 -> SUCCESS @ /baz/x
          /baz/x/(method:GET), segment:2 -> FAILURE "Not all segments matched" @ /baz/x/(method:GET)
          /baz/x/{optional?}, segment:3 -> SUCCESS; Parameters [optional=[z]] @ /baz/x/{optional?}
            /baz/x/{optional?}/(method:GET), segment:3 -> SUCCESS @ /baz/x/{optional?}/(method:GET)
        /baz/{y}, segment:1 -> FAILURE "Better match was already found" @ /baz/{y}
      /{param}, segment:0 -> FAILURE "Better match was already found" @ /{param}
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "baz" -> "x" -> "{optional?}" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [optional=[z]] @ /baz/x/{optional?}/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingBazXValue() {
        val trace = tracingApplication {
            assertEquals("/baz/x/{optional?}", client.get("/baz/x/value").bodyAsText())
        }

        assertEquals(
            """
    Trace for [baz, x, value]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:1 -> SUCCESS @ /baz
        /baz/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /baz/(method:GET)
        /baz/x, segment:2 -> SUCCESS @ /baz/x
          /baz/x/(method:GET), segment:2 -> FAILURE "Not all segments matched" @ /baz/x/(method:GET)
          /baz/x/{optional?}, segment:3 -> SUCCESS; Parameters [optional=[value]] @ /baz/x/{optional?}
            /baz/x/{optional?}/(method:GET), segment:3 -> SUCCESS @ /baz/x/{optional?}/(method:GET)
        /baz/{y}, segment:1 -> FAILURE "Better match was already found" @ /baz/{y}
      /{param}, segment:0 -> FAILURE "Better match was already found" @ /{param}
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "baz" -> "x" -> "{optional?}" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [optional=[value]] @ /baz/x/{optional?}/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingP() {
        val trace = tracingApplication {
            assertEquals("/{param}", client.get("/p").bodyAsText())
        }

        assertEquals(
            """
    Trace for [p]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:0 -> FAILURE "Selector didn't match" @ /baz
      /{param}, segment:1 -> SUCCESS; Parameters [param=[p]] @ /{param}
        /{param}/(method:GET), segment:1 -> SUCCESS @ /{param}/(method:GET)
        /{param}/x, segment:1 -> FAILURE "Selector didn't match" @ /{param}/x
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "{param}" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [param=[p]] @ /{param}/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingPX() {
        val trace = tracingApplication {
            assertEquals("/{param}/x", client.get("/p/x").bodyAsText())
        }

        assertEquals(
            """
    Trace for [p, x]
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:0 -> FAILURE "Selector didn't match" @ /baz
      /{param}, segment:1 -> SUCCESS; Parameters [param=[p]] @ /{param}
        /{param}/(method:GET), segment:1 -> FAILURE "Not all segments matched" @ /{param}/(method:GET)
        /{param}/x, segment:2 -> SUCCESS @ /{param}/x
          /{param}/x/(method:GET), segment:2 -> SUCCESS @ /{param}/x/(method:GET)
          /{param}/x/z, segment:2 -> FAILURE "Selector didn't match" @ /{param}/x/z
      /*, segment:0 -> FAILURE "Better match was already found" @ /*
      /(header:a = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:a = x)
      /(header:b = x), segment:0 -> FAILURE "Selector didn't match" @ /(header:b = x)
    Matched routes:
      "" -> "{param}" -> "x" -> "(method:GET)"
    Route resolve result:
      SUCCESS; Parameters [param=[p]] @ /{param}/x/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    @Test
    fun testRoutingRoot() {
        val trace = tracingApplication {
            val response = client.get("/") {
                header("a", "x")
                header("b", "x")
                method = HttpMethod.Get
            }

            assertEquals("a", response.bodyAsText())
        }

        assertEquals(
            """
    Trace for []
    /, segment:0 -> SUCCESS @ /
      /bar, segment:0 -> FAILURE "Selector didn't match" @ /bar
      /baz, segment:0 -> FAILURE "Selector didn't match" @ /baz
      /{param}, segment:0 -> FAILURE "Selector didn't match" @ /{param}
      /*, segment:0 -> FAILURE "Selector didn't match" @ /*
      /(header:a = x), segment:0 -> SUCCESS @ /(header:a = x)
        /(header:a = x)/(method:GET), segment:0 -> SUCCESS @ /(header:a = x)/(method:GET)
      /(header:b = x), segment:0 -> SUCCESS @ /(header:b = x)
        /(header:b = x)/(method:GET), segment:0 -> SUCCESS @ /(header:b = x)/(method:GET)
    Matched routes:
      "" -> "(header:a = x)" -> "(method:GET)"
      "" -> "(header:b = x)" -> "(method:GET)"
    Route resolve result:
      SUCCESS @ /(header:a = x)/(method:GET)
            """.trimIndent(),
            trace
        )
    }

    private fun tracingApplication(
        block: suspend ApplicationTestBuilder.() -> Unit
    ): String {
        var trace: RoutingResolveTrace? = null
        testApplication {
            application {
                routing {
                    trace {
                        trace = it
                    }

                    testRouting()
                }
            }

            block(this)
        }

        return trace?.buildText()!!
    }

    private fun Routing.testRouting() {
        get("/bar") { call.respond("/bar") }
        get("/baz") { call.respond("/baz") }
        get("/baz/x") { call.respond("/baz/x") }
        get("/baz/x/{optional?}") { call.respond("/baz/x/{optional?}") }
        get("/baz/{y}") { call.respond("/baz/{y}") }
        get("/baz/{y}/value") { call.respond("/baz/{y}/value") }
        get("/{param}") { call.respond("/{param}") }
        get("/{param}/x") { call.respond("/{param}/x") }
        get("/{param}/x/z") { call.respond("/{param}/x/z") }
        get("/*/extra") { call.respond("/*/extra") }
        header("a", "x") { get { call.respond("a") } }
        header("b", "x") { get { call.respond("b") } }
    }
}
