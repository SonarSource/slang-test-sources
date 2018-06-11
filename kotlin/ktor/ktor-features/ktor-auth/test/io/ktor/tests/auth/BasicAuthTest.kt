package io.ktor.tests.auth

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.testing.*
import io.ktor.util.*
import org.junit.Test
import java.nio.charset.*
import kotlin.test.*

class BasicAuthTest {
    @Test
    fun testBasicAuthNoAuth() {
        withTestApplication {
            application.configureServer()

            val call = handleRequest {
                uri = "/"
            }

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.Unauthorized, call.response.status())
            assertNull(call.response.content)

            assertWWWAuthenticateHeaderExist(call)
            assertEquals("Basic realm=ktor-test, charset=UTF-8", call.response.headers[HttpHeaders.WWWAuthenticate])
        }
    }

    @Test
    fun testCharsetNull() {
        withTestApplication {
            application.apply {
                install(Authentication) {
                    basic {
                        realm = "ktor-test"
                        charset = null
                        validate { null }
                    }
                }

                routing {
                    authenticate {
                        get("/") { call.respondText("Secret info") }
                    }
                }
            }

            val call = handleRequest {
                uri = "/"
            }

            assertEquals(
                "Basic realm=ktor-test",
                call.response.headers[HttpHeaders.WWWAuthenticate]
            )
        }
    }

    @Test
    fun testBasicAuthNoInterceptor() {
        withTestApplication {
            val user = "user1"
            val p = "user1"

            application.intercept(ApplicationCallPipeline.Infrastructure) {
                val authInfo = call.request.basicAuthenticationCredentials()
                assertNotNull(authInfo)
                assertEquals(authInfo, call.request.basicAuthenticationCredentials())

                assertEquals(user, authInfo!!.name)
                assertEquals(p, authInfo.password)

                call.response.status(HttpStatusCode.OK)
                call.respondText("ok")
            }

            val call = handleRequestWithBasic("/", user, p)

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.OK, call.response.status())
            assertEquals("ok", call.response.content)
        }
    }

    @Test
    fun testBasicAuthSuccess() {
        withTestApplication {
            application.configureServer()
            val user = "user1"
            val p = "user1"

            val call = handleRequestWithBasic("/", user, p)

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.OK, call.response.status())
            assertEquals("Secret info", call.response.content)
        }
    }

    @Test
    fun testUtf8Charset() {
        withTestApplication {
            val user = "Лира"
            val p = "Лира"

            application.configureServer {
                if (it.name == user && it.password == p) UserIdPrincipal(it.name) else null
            }

            val call = handleRequestWithBasic("/", user, p, charset = Charsets.UTF_8)

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.OK, call.response.status())
            assertEquals("Secret info", call.response.content)
        }
    }

    @Test
    fun testBasicAuthFailed() {
        withTestApplication {
            application.configureServer()
            val user = "user1"
            val p = "wrong password"

            val call = handleRequestWithBasic("/", user, p)

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.Unauthorized, call.response.status())
            assertNotEquals("Secret info", call.response.content)

            assertWWWAuthenticateHeaderExist(call)
        }
    }

    @Test
    fun testBasicAuthDifferentScheme() {
        withTestApplication {
            application.configureServer()

            val call = handleRequest {
                uri = "/"
                addHeader(HttpHeaders.Authorization, "Bearer some-token")
            }

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.Unauthorized, call.response.status())
            assertNotEquals("Secret info", call.response.content)

            assertWWWAuthenticateHeaderExist(call)
        }
    }

    @Test
    fun testBasicAuthInvalidBase64() {
        withTestApplication {
            application.configureServer()

            val call = handleRequest {
                uri = "/"
                addHeader(HttpHeaders.Authorization, "Basic +-test")
            }

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.Unauthorized, call.response.status())
            assertNotEquals("Secret info", call.response.content)

            assertWWWAuthenticateHeaderExist(call)
        }
    }

    @Test
    fun testBasicAuthFiltered() {
        withTestApplication {
            application.configureServer()
            val user = "user1"
            val p = "wrong password"

            val call = handleRequestWithBasic("/?backdoor", user, p)

            assertTrue(call.requestHandled)
            assertEquals(HttpStatusCode.OK, call.response.status())
            assertEquals("Secret info", call.response.content)
        }
    }

    @Test
    fun testSimplifiedFlow() {
        withTestApplication {
            application.install(Authentication) {
                basic {
                    realm = "ktor-test"
                    validate { c -> if (c.name == "good") UserIdPrincipal(c.name) else null }
                }
            }

            application.routing {
                authenticate {
                    get("/") { call.respondText("Secret info") }
                }
            }

            handleRequestWithBasic("/", "bad", "").let { call ->
                assertTrue(call.requestHandled)
                assertEquals(HttpStatusCode.Unauthorized, call.response.status())
                assertNotEquals("Secret info", call.response.content)

                assertWWWAuthenticateHeaderExist(call)
            }

            handleRequestWithBasic("/", "good", "").let { call ->
                assertTrue(call.requestHandled)
                assertEquals(HttpStatusCode.OK, call.response.status())
                assertEquals("Secret info", call.response.content)
            }
        }
    }

    private fun TestApplicationEngine.handleRequestWithBasic(
        url: String, user: String, pass: String, charset: Charset = Charsets.ISO_8859_1
    ) =
        handleRequest {
            uri = url

            val up = "$user:$pass"
            val encoded = encodeBase64(up.toByteArray(charset))
            addHeader(HttpHeaders.Authorization, "Basic $encoded")
        }

    private fun assertWWWAuthenticateHeaderExist(call: ApplicationCall) {
        assertNotNull(call.response.headers[HttpHeaders.WWWAuthenticate])
        val header =
            parseAuthorizationHeader(call.response.headers[HttpHeaders.WWWAuthenticate]!!) as HttpAuthHeader.Parameterized

        assertEquals(AuthScheme.Basic, header.authScheme)
        assertEquals("ktor-test", header.parameter(HttpAuthHeader.Parameters.Realm))
    }

    private fun Application.configureServer(
        validate: suspend (UserPasswordCredential) -> Principal? = {
            if (it.name == it.password) UserIdPrincipal(it.name) else null

        }
    ) {
        install(Authentication) {
            basic {
                realm = "ktor-test"
                validate {
                    validate(it)
                }
                skipWhen { it.request.origin.uri.contains("backdoor") }
            }
        }

        routing {
            authenticate {
                route("/") {
                    handle { call.respondText("Secret info") }
                }
            }
        }
    }
}
