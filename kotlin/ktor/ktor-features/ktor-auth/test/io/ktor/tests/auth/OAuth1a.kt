package io.ktor.tests.auth

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.testing.*
import io.ktor.server.testing.client.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import org.junit.*
import org.junit.Test
import java.time.*
import java.util.concurrent.*
import kotlin.test.*

class OAuth1aSignatureTest {
    @Test
    fun testSignatureBaseString() {
        val header = HttpAuthHeader.Parameterized("OAuth", mapOf(
                "oauth_consumer_key" to "1CV4Ud1ZOOzRMwmRyCEe0PY7J",
                "oauth_nonce" to "e685449bf73912c1ebb57220a2158380",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "1447068216",
                "oauth_version" to "1.0"
        ))

        val baseString = signatureBaseString(header, HttpMethod.Post, "https://api.twitter.com/oauth/request_token", emptyList())

        assertEquals("POST&https%3A%2F%2Fapi.twitter.com%2Foauth%2Frequest_token&oauth_consumer_key%3D1CV4Ud1ZOOzRMwmRyCEe0PY7J%26oauth_nonce%3De685449bf73912c1ebb57220a2158380%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1447068216%26oauth_version%3D1.0", baseString)
    }

    @Test
    fun testSignatureBaseStringWithCallback() {
        val header = HttpAuthHeader.Parameterized("OAuth", mapOf(
                "oauth_callback" to "http://localhost/sign-in-with-twitter/",
                "oauth_consumer_key" to "1CV4Ud1ZOOzRMwmRyCEe0PY7J",
                "oauth_nonce" to "2f085f69a50e55ea6f1bd4e2b3907448",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "1447072553",
                "oauth_version" to "1.0"
        ))

        val baseString = signatureBaseString(header, HttpMethod.Post, "https://api.twitter.com/oauth/request_token", emptyList())

        assertEquals("POST&https%3A%2F%2Fapi.twitter.com%2Foauth%2Frequest_token&oauth_callback%3Dhttp%253A%252F%252Flocalhost%252Fsign-in-with-twitter%252F%26oauth_consumer_key%3D1CV4Ud1ZOOzRMwmRyCEe0PY7J%26oauth_nonce%3D2f085f69a50e55ea6f1bd4e2b3907448%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1447072553%26oauth_version%3D1.0", baseString)
    }
}

class OAuth1aFlowTest {
    var testClient: HttpClient? = null

    @Before
    fun createServer() {
        testClient = createOAuthServer(object : TestingOAuthServer {

            override fun requestToken(ctx: ApplicationCall, callback: String?, consumerKey: String, nonce: String, signature: String, signatureMethod: String, timestamp: Long): TestOAuthTokenResponse {
                if (consumerKey != "1CV4Ud1ZOOzRMwmRyCEe0PY7J") {
                    throw IllegalArgumentException("Bad consumer key specified: $consumerKey")
                }
                if (signatureMethod != "HMAC-SHA1") {
                    throw IllegalArgumentException("Bad signature method specified: $signatureMethod")
                }
                val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                if (Math.abs(now - timestamp) > 10000) {
                    throw IllegalArgumentException("timestamp is too old: $timestamp (now $now)")
                }

                return TestOAuthTokenResponse(callback == "http://localhost/login?redirected=true", "token1", "tokenSecret1")
            }

            override suspend fun authorize(call: ApplicationCall, oauthToken: String) {
                if (oauthToken != "token1") {
                    call.respondRedirect("http://localhost/login?redirected=true&error=Wrong+token+$oauthToken")
                }

                call.respondRedirect("http://localhost/login?redirected=true&oauth_token=$oauthToken&oauth_verifier=verifier1")
            }

            override fun accessToken(ctx: ApplicationCall, consumerKey: String, nonce: String, signature: String, signatureMethod: String, timestamp: Long, token: String, verifier: String): OAuthAccessTokenResponse.OAuth1a {
                if (consumerKey != "1CV4Ud1ZOOzRMwmRyCEe0PY7J") {
                    throw IllegalArgumentException("Bad consumer key specified $consumerKey")
                }
                if (signatureMethod != "HMAC-SHA1") {
                    throw IllegalArgumentException("Bad signature method specified: $signatureMethod")
                }
                val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                if (Math.abs(now - timestamp) > 10000) {
                    throw IllegalArgumentException("timestamp is too old: $timestamp (now $now)")
                }
                // NOTE real server should test it but as we don't test the whole workflow in one test we can't do it
                //            if (nonce !in knownNonceSet) {
                //                throw IllegalArgumentException("Bad nonce specified: $nonce")
                //            }
                if (token != "token1") {
                    throw IllegalArgumentException("Wrong token specified: $token")
                }
                if (verifier != "verifier1") {
                    throw IllegalArgumentException("Wrong verifier specified: $verifier")
                }

                return OAuthAccessTokenResponse.OAuth1a("temp-token-1", "temp-secret-1", Parameters.Empty)
            }
        })
    }

    @After
    fun destroyServer() {
        testClient?.close()
        testClient = null
    }

    val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val settings = OAuthServerSettings.OAuth1aServerSettings(
            name = "oauth1a",
            requestTokenUrl = "https://login-server-com/oauth/request_token",
            authorizeUrl = "https://login-server-com/oauth/authorize",
            accessTokenUrl = "https://login-server-com/oauth/access_token",

            consumerKey = "1CV4Ud1ZOOzRMwmRyCEe0PY7J",
            consumerSecret = "0xPR3CQaGOilgXCGUt4g6SpBkhti9DOGkWtBCOImNFomedZ3ZU"
    )

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun testRequestToken() {
        withTestApplication {
            application.configureServer("http://localhost/login?redirected=true")

            val result = handleRequest(HttpMethod.Get, "/login")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.Found, result.response.status())
            assertNull(result.response.content)
            assertEquals("https://login-server-com/oauth/authorize?oauth_token=token1", result.response.headers[HttpHeaders.Location], "Redirect target location is not valid")
        }
    }

    @Test
    fun testRequestTokenWrongConsumerKey() {
        withTestApplication {
            application.configureServer("http://localhost/login?redirected=true", mutateSettings = {
                OAuthServerSettings.OAuth1aServerSettings(name, requestTokenUrl, authorizeUrl, accessTokenUrl, "badConsumerKey", consumerSecret)
            })

            val result = handleRequest(HttpMethod.Get, "/login")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.OK, result.response.status())
            assertEquals("Ho, null", result.response.content)
        }
    }

    @Test
    fun testRequestTokenFailedRedirect() {
        withTestApplication {
            application.configureServer("http://localhost/login")

            val result = handleRequest(HttpMethod.Get, "/login")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.OK, result.response.status())
            assertEquals("Ho, null", result.response.content)
        }
    }

    @Test
    fun testAccessToken() {
        withTestApplication {
            application.configureServer()

            val result = handleRequest(HttpMethod.Get, "/login?redirected=true&oauth_token=token1&oauth_verifier=verifier1")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.OK, result.response.status())
            assertTrue { result.response.content!!.startsWith("Ho, ") }
            assertFalse { result.response.content!!.contains("[]") }
        }
    }

    @Test
    fun testAccessTokenWrongVerifier() {
        withTestApplication {
            application.configureServer()

            val result = handleRequest(HttpMethod.Get, "/login?redirected=true&oauth_token=token1&oauth_verifier=verifier2")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.OK, result.response.status())
            assertTrue { result.response.content!!.startsWith("Ho, ") }
            assertTrue { result.response.content!!.contains("null") }
        }
    }

    @Test
    fun testRequestTokenLowLevel() {
        withTestApplication {
            application.routing {
                get("/login") {
                    oauthRespondRedirect(testClient!!, dispatcher, settings, "http://localhost/login?redirected=true")
                }
            }

            val result = handleRequest(HttpMethod.Get, "/login")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.Found, result.response.status())
            assertEquals("https://login-server-com/oauth/authorize?oauth_token=token1", result.response.headers[HttpHeaders.Location], "Redirect target location is not valid")
        }
    }

    @Test
    fun testAccessTokenLowLevel() {
        withTestApplication {
            application.routing {
                get("/login") {
                    oauthHandleCallback(testClient!!, dispatcher, settings, "http://localhost/login?redirected=true", "/") { token ->
                        call.respondText("Ho, $token")
                    }
                }
            }

            val result = handleRequest(HttpMethod.Get, "/login?redirected=true&oauth_token=token1&oauth_verifier=verifier1")

            waitExecutor()

            assertTrue(result.requestHandled, "request should be handled")
            assertEquals(HttpStatusCode.OK, result.response.status())
            assertTrue { result.response.content!!.startsWith("Ho, ") }
            assertFalse { result.response.content!!.contains("null") }
        }
    }

    @Test
    fun testAccessTokenLowLevelErrorRedirect() {
        withTestApplication {
            application.routing {
                get("/login") {
                    oauthHandleCallback(testClient!!, dispatcher, settings, "http://localhost/login?redirected=true", "/") { token ->
                        call.respondText("Ho, $token")
                    }
                }
            }

            val result = handleRequest(HttpMethod.Get, "/login?redirected=true&oauth_token=token1&error_description=failed")
            assertTrue(result.requestHandled, "request should be handled asynchronously")

            assertEquals(HttpStatusCode.Found, result.response.status())
        }
    }

    private fun Application.configureServer(redirectUrl: String = "http://localhost/login?redirected=true", mutateSettings: OAuthServerSettings.OAuth1aServerSettings.() -> OAuthServerSettings.OAuth1aServerSettings = { this }) {
        install(Authentication) {
            oauth {
                client = testClient!!
                providerLookup = { settings.mutateSettings() }
                urlProvider = { redirectUrl }
            }
        }

        routing {
            authenticate {
                get("/login") {
                    call.respondText("Ho, ${call.authentication.principal}")
                }
            }
        }
    }

    private fun waitExecutor() {
        val latch = CountDownLatch(1)
        executor.submit {
            latch.countDown()
        }
        latch.await(1L, TimeUnit.MINUTES)
    }


}

// NOTICE in fact we can potentially reorganize it to provide API for ktor-users to build their own OAuth servers
//          for now we have it only for the testing purpose

private interface TestingOAuthServer {
    fun requestToken(ctx: ApplicationCall, callback: String?, consumerKey: String, nonce: String, signature: String, signatureMethod: String, timestamp: Long): TestOAuthTokenResponse
    suspend fun authorize(call: ApplicationCall, oauthToken: String)
    fun accessToken(ctx: ApplicationCall, consumerKey: String, nonce: String, signature: String, signatureMethod: String,
                    timestamp: Long, token: String, verifier: String): OAuthAccessTokenResponse.OAuth1a
}

private fun createOAuthServer(server: TestingOAuthServer): HttpClient {
    val environment = createTestEnvironment {
        module {
            routing {
                post("/oauth/request_token") {
                    val authHeader = call.request.parseAuthorizationHeader()
                            ?: throw IllegalArgumentException("No auth header found")

                    assertEquals(AuthScheme.OAuth, authHeader.authScheme, "This is not an OAuth request")
                    if (authHeader !is HttpAuthHeader.Parameterized) {
                        call.fail("Bad OAuth header supplied: should be parameterized auth header but token68 blob found")
                        return@post
                    }

                    val callback = authHeader.parameter(HttpAuthHeader.Parameters.OAuthCallback)?.let { decodeURLPart(it) }
                    val consumerKey = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthConsumerKey)
                    val nonce = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthNonce)
                    val signatureMethod = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthSignatureMethod)
                    val signature = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthSignature)
                    val timestamp = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthTimestamp).toLong()
                    val version = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthVersion)

                    assertEquals("1.0", version)

                    try {
                        val rr = server.requestToken(call, callback, consumerKey, nonce, signature, signatureMethod, timestamp)

                        call.response.status(HttpStatusCode.OK)
                        call.respondText(listOf(
                                HttpAuthHeader.Parameters.OAuthToken to rr.token,
                                HttpAuthHeader.Parameters.OAuthTokenSecret to rr.tokenSecret,
                                HttpAuthHeader.Parameters.OAuthCallbackConfirmed to rr.callbackConfirmed.toString()
                        ).formUrlEncode(),
                                ContentType.Application.FormUrlEncoded
                        )
                    } catch (e: Exception) {
                        call.fail(e.message)
                    }
                }
                post("/oauth/access_token") {
                    val authHeader = call.request.parseAuthorizationHeader()
                            ?: throw IllegalArgumentException("No auth header found")
                    assertEquals(AuthScheme.OAuth, authHeader.authScheme, "This is not an OAuth request")
                    if (authHeader !is HttpAuthHeader.Parameterized) {
                        throw IllegalStateException("Bad OAuth header supplied: should be parameterized auth header but token68 blob found")
                    }

                    val consumerKey = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthConsumerKey)
                    val nonce = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthNonce)
                    val signature = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthSignature)
                    val signatureMethod = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthSignatureMethod)
                    val timestamp = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthTimestamp).toLong()
                    val token = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthToken)
                    val version = authHeader.requireParameter(HttpAuthHeader.Parameters.OAuthVersion)

                    if (version != "1.0") {
                        call.fail("Only version 1.0 is supported")
                    }

                    if (!call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
                        call.fail("content type should be ${ContentType.Application.FormUrlEncoded}")
                    }

                    val values = call.receiveParameters()
                    val verifier = values[HttpAuthHeader.Parameters.OAuthVerifier]
                            ?: throw IllegalArgumentException("oauth_verified is not provided in the POST request body")

                    try {
                        val tokenPair = server.accessToken(call, consumerKey, nonce, signature, signatureMethod, timestamp, token, verifier)

                        call.response.status(HttpStatusCode.OK)
                        call.respondText((listOf(
                                HttpAuthHeader.Parameters.OAuthToken to tokenPair.token,
                                HttpAuthHeader.Parameters.OAuthTokenSecret to tokenPair.tokenSecret
                        ) + tokenPair.extraParameters.flattenEntries()).formUrlEncode(), ContentType.Application.FormUrlEncoded)
                    } catch (e: Exception) {
                        call.fail(e.message)
                    }
                }
                post("/oauth/authorize") {
                    val oauthToken = call.parameters[HttpAuthHeader.Parameters.OAuthToken]
                            ?: throw IllegalArgumentException("No oauth_token parameter specified")
                    server.authorize(call, oauthToken)
                    call.response.status(HttpStatusCode.OK)
                }
            }

        }
    }
    val engine = TestApplicationEngine(environment)
    engine.start()
    return HttpClient(TestHttpClientEngine.config { app = engine })
}

private suspend fun ApplicationCall.fail(text: String?) {
    val message = text ?: "Auth failed"
    response.status(HttpStatusCode.InternalServerError)
    respondText(message)
}

private fun HttpAuthHeader.Parameterized.requireParameter(name: String) = parameter(name)?.let { decodeURLPart(it) }
        ?: throw IllegalArgumentException("No $name parameter specified in OAuth header")

data class TestOAuthTokenResponse(val callbackConfirmed: Boolean, val token: String, val tokenSecret: String)