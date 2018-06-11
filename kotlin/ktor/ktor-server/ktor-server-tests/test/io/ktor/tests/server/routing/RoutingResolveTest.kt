package io.ktor.tests.server.routing

import io.ktor.http.*
import io.ktor.routing.*
import io.ktor.server.testing.*
import io.ktor.util.*
import org.junit.Test
import kotlin.test.*

private object RootRouteSelector : RouteSelector(RouteSelectorEvaluation.qualityConstant) {
    override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation {
        throw UnsupportedOperationException("Root selector should not be evaluated")
    }

    override fun toString(): String = ""
}

fun routing() = Route(parent = null, selector = RootRouteSelector)
fun resolve(routing: Route, path: String, parameters: Parameters = Parameters.Empty, headers: Headers = Headers.Empty): RoutingResolveResult {
    return withTestApplication {
        RoutingResolveContext(routing, TestApplicationCall(application).apply {
            request.method = HttpMethod.Get
            request.uri = path + buildString {
                if (!parameters.isEmpty()) {
                    append("?")
                    parameters.formUrlEncodeTo(this)
                }
            }
            headers.flattenForEach { name, value -> request.addHeader(name, value) }
        }, emptyList()).resolve()
    }
}

fun Route.handle(selector: RouteSelector) = createChild(selector).apply { handle {} }

class RoutingResolveTest {
    @Test
    fun `empty routing`() {
        val root = routing()
        val result = resolve(root, "/foo/bar")
        assertTrue(result is RoutingResolveResult.Failure)
        assertEquals(root, result.route)
    }

    @Test
    fun `routing with foo`() {
        val root = routing()
        val fooEntry = root.handle(PathSegmentConstantRouteSelector("foo"))

        on("resolving /foo") {
            val result = resolve(root, "/foo")
            it("should succeed") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should have fooEntry as success entry") {
                assertEquals(fooEntry, result.route)
            }
        }
        on("resolving /foo/bar") {
            val result = resolve(root, "/foo/bar")
            it("should not succeed") {
                assertTrue(result is RoutingResolveResult.Failure)
            }
            it("should have fooEntry as fail entry") {
                assertEquals(fooEntry, result.route)
            }
        }
    }

    @Test
    fun `routing with foo-bar`() {
        val root = routing()
        val fooEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
        val barEntry = fooEntry.handle(PathSegmentConstantRouteSelector("bar"))

        on("resolving /foo") {
            val result = resolve(root, "/foo")
            it("should succeed") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should have fooEntry as success entry") {
                assertEquals(fooEntry, result.route)
            }
        }

        on("resolving /foo/bar") {
            val result = resolve(root, "/foo/bar")
            it("should succeed") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should have barEntry as success entry") {
                assertEquals(barEntry, result.route)
            }
        }

        on("resolving /other/bar") {
            val result = resolve(root, "/other/bar")
            it("should not succeed") {
                assertTrue(result is RoutingResolveResult.Failure)
            }
            it("should have root as fail entry") {
                assertEquals(root, result.route)
            }
        }
    }

    @Test
    fun `routing foo with parameter`() {
        val root = routing()
        val paramEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentParameterRouteSelector("param"))

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", (result as RoutingResolveResult.Success).parameters["param"])
            }
        }
    }

    @Test
    fun `routing foo with surrounded parameter`() {
        val root = routing()
        val paramEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentParameterRouteSelector("param", "a", "b"))

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/avalueb")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", result.parameters["param"])
            }
        }
    }

    @Test
    fun `routing foo with multiply parameters`() {
        val root = routing()
        root.handle(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentParameterRouteSelector("param1"))
                .handle(PathSegmentParameterRouteSelector("param2"))

        on("resolving /foo/value1/value2") {
            val result = resolve(root, "/foo/value1/value2")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should have parameter values equal to 'value1' and 'value2'") {
                assertEquals("value1", result.parameters["param1"])
                assertEquals("value2", result.parameters["param2"])
            }
        }
    }

    @Test
    fun `routing foo with multivalue parameter`() {
        val root = routing()
        root.handle(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentParameterRouteSelector("param"))
                .handle(PathSegmentParameterRouteSelector("param"))

        on("resolving /foo/value1/value2") {
            val result = resolve(root, "/foo/value1/value2")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should have parameter value equal to [value1, value2]") {
                assertEquals(listOf("value1", "value2"), result.parameters.getAll("param"))
            }
        }
    }

    @Test
    fun `routing foo with optional parameter`() {
        val root = routing()
        val paramEntry = root.createChild(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentOptionalParameterRouteSelector("param"))

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", result.parameters["param"])
            }
        }

        on("resolving /foo") {
            val result = resolve(root, "/foo")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should not have parameter value") {
                assertNull(result.parameters["param"])
            }
        }
    }

    @Test
    fun `routing foo with wildcard`() {
        val root = routing()
        val fooEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
        val paramEntry = fooEntry.handle(PathSegmentWildcardRouteSelector)

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
        }

        on("resolving /foo") {
            val result = resolve(root, "/foo")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to fooEntry") {
                assertEquals(fooEntry, result.route)
            }
        }
    }

    @Test
    fun `routing foo with anonymous tailcard`() {
        val root = routing()
        val paramEntry = root.createChild(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentTailcardRouteSelector())

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
        }

        on("resolving /foo") {
            val result = resolve(root, "/foo")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
        }

        on("resolving /foo/bar/baz/blah") {
            val result = resolve(root, "/foo/bar/baz/blah")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
        }
    }

    @Test
    fun `routing foo with named tailcard`() {
        val root = routing()
        val paramEntry = root.createChild(PathSegmentConstantRouteSelector("foo"))
                .handle(PathSegmentTailcardRouteSelector("items"))

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value") {
                assertEquals("value", result.parameters["items"])
            }
        }

        on("resolving /foo") {
            val result = resolve(root, "/foo")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to fooEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have empty parameter") {
                assertNull(result.parameters["items"])
            }
        }

        on("resolving /foo/bar/baz/blah") {
            val result = resolve(root, "/foo/bar/baz/blah")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value") {
                assertEquals(listOf("bar", "baz", "blah"), result.parameters.getAll("items"))
            }
        }
    }

    @Test
    fun `routing foo with parameter entry`() {
        val root = routing()
        val fooEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
        val paramEntry = fooEntry.handle(ParameterRouteSelector("name"))

        on("resolving /foo with query string name=value") {
            val result = resolve(root, "/foo", parametersOf("name", "value"))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value") {
                assertEquals(listOf("value"), result.parameters.getAll("name"))
            }
        }

        on("resolving /foo") {
            val result = resolve(root, "/foo")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to fooEntry") {
                assertEquals(fooEntry, result.route)
            }
            it("should have no parameter") {
                assertNull(result.parameters["name"])
            }
        }

        on("resolving /foo with multiple parameters") {
            val result = resolve(root, "/foo", parametersOf("name", listOf("value1", "value2")))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value") {
                assertEquals(listOf("value1", "value2"), result.parameters.getAll("name"))
            }
        }
    }

    @Test
    fun `routing foo with quality`() {
        val root = routing()
        val fooEntry = root.createChild(PathSegmentConstantRouteSelector("foo"))
        val paramEntry = fooEntry.handle(PathSegmentParameterRouteSelector("name"))
        val constantEntry = fooEntry.handle(PathSegmentConstantRouteSelector("admin"))

        on("resolving /foo/value") {
            val result = resolve(root, "/foo/value")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, result.route)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", result.parameters["name"])
            }
        }

        on("resolving /foo/admin") {
            val result = resolve(root, "/foo/admin")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to constantEntry") {
                assertEquals(constantEntry, result.route)
            }
            it("should not have parameter value") {
                assertNull(result.parameters["name"])
            }
        }

    }

    @Test
    fun `routing foo with quality and headers`() {
        val root = routing()
        val fooEntry = root.handle(PathSegmentConstantRouteSelector("foo"))
        val plainEntry = fooEntry.handle(HttpHeaderRouteSelector("Accept", "text/plain"))
        val htmlEntry = fooEntry.handle(HttpHeaderRouteSelector("Accept", "text/html"))

        on("resolving /foo with more specific") {
            val result = resolve(root, "/foo", headers = headersOf("Accept", listOf("text/*, text/html, */*")))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to htmlEntry") {
                assertEquals(htmlEntry, result.route)
            }
        }

        on("resolving /foo with equal preference") {
            val result = resolve(root, "/foo", headers = headersOf("Accept", listOf("text/plain, text/html")))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to plainEntry") {
                assertEquals(plainEntry, result.route)
            }
        }

        on("resolving /foo with preference of text/plain") {
            val result = resolve(root, "/foo", headers = headersOf("Accept", listOf("text/plain, text/html; q=0.5")))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to plainEntry") {
                assertEquals(plainEntry, result.route)
            }
        }

        on("resolving /foo with preference of text/html") {
            val result = resolve(root, "/foo", headers = headersOf("Accept", listOf("text/plain; q=0.5, text/html")))

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to htmlEntry") {
                assertEquals(htmlEntry, result.route)
            }
        }
    }

    @Test
    fun `select most specific route with root`() {
        val routing = routing()
        val rootEntry = routing.createRouteFromPath("/").apply { handle {} }
        val varargEntry = routing.createRouteFromPath("/{...}").apply { handle {} }

        on("resolving /") {
            val result = resolve(routing, "/")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to rootEntry") {
                assertEquals(rootEntry, result.route)
            }
        }
        on("resolving /path") {
            val result = resolve(routing, "/path")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to varargEntry") {
                assertEquals(varargEntry, result.route)
            }
        }

    }

    @Test
    fun `select most specific route with optional param`() {
        val routing = routing()
        val dateEntry = routing.createRouteFromPath("/sessions/{date}").apply { handle {} }
        val currentEntry = routing.createRouteFromPath("/sessions/current/{date?}").apply { handle {} }

        on("resolving date") {
            val result = resolve(routing, "/sessions/2017-11-02")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to rootEntry") {
                assertEquals(dateEntry, result.route)
            }
        }
        on("resolving current") {
            val result = resolve(routing, "/sessions/current/2017-11-02T10:00")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to varargEntry") {
                assertEquals(currentEntry, result.route)
            }
        }
        on("resolving optional current") {
            val result = resolve(routing, "/sessions/current/")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to varargEntry") {
                assertEquals(currentEntry, result.route)
            }
        }

    }

    @Test
    fun `decoding routing`() {
        val routing = routing()
        val spaceEntry = routing.createRouteFromPath("/a%20b").apply { handle {} }
        val plusEntry = routing.createRouteFromPath("/a+b").apply { handle {} }

        on("resolving /a%20b") {
            val result = resolve(routing, "/a%20b")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to varargEntry") {
                assertEquals(spaceEntry, result.route)
            }
        }

        on("resolving /a+b") {
            val result = resolve(routing, "/a+b")

            it("should successfully resolve") {
                assertTrue(result is RoutingResolveResult.Success)
            }
            it("should resolve to varargEntry") {
                assertEquals(plusEntry, result.route)
            }
        }
    }

}
