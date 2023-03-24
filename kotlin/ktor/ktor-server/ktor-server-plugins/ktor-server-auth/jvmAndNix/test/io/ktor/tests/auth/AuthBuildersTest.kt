/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.auth

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlin.test.*

@Suppress("DEPRECATION")
class AuthBuildersTest {

    @Test
    fun testPrincipalsAccess() {
        val username = "testuser"

        withTestApplication {
            application.install(Authentication) {
                form { validate { c -> UserIdPrincipal(c.name) } }
            }

            application.routing {
                authenticate {
                    route("/") {
                        handle {
                            assertEquals(username, call.authentication.principal<UserIdPrincipal>()?.name)
                        }
                    }
                }
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=$username&password=p")
            }
        }
    }

    @Test
    fun testMultipleConfigurationsNested() {
        withTestApplication {
            application.install(Authentication) {
                form("first") { validate { c -> if (c.name == "first") UserIdPrincipal(c.name) else null } }
                basic("second") { validate { c -> if (c.name == "second") UserIdPrincipal(c.name) else null } }
            }

            application.routing {
                authenticate("first") {
                    authenticate("second") {
                        route("/both") {
                            handle {
                                assertEquals("first", call.authentication.principal<UserIdPrincipal>()?.name)
                                call.respondText("OK")
                            }
                        }
                        route("/{name}") {
                            handle {
                                assertEquals(
                                    call.parameters["name"],
                                    call.authentication.principal<UserIdPrincipal>()?.name
                                )
                                call.respondText("OK")
                            }
                        }
                    }
                }
            }

            handleRequest(HttpMethod.Post, "/nobody") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=nobody&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Post, "/first") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=first&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Get, "/second") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                addHeader(
                    HttpHeaders.Authorization,
                    HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
                )
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Post, "/both") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                addHeader(
                    HttpHeaders.Authorization,
                    HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
                )
                setBody("user=first&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }
        }
    }

    @Test
    fun testMultipleRequiredConfigurations() = testApplication {
        class Principal1(val name: String) : Principal
        class Principal2(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") Principal1(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") Principal2(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Required) {
                authenticate("second", strategy = AuthenticationStrategy.Required) {
                    route("/{...}") {
                        handle {
                            call.respondText(
                                call.principal<Principal1>()?.name + " " + call.principal<Principal2>()?.name
                            )
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first second", it.bodyAsText())
        }
    }

    @Test
    fun testMultipleRequiredConfigurationsAccessByName() = testApplication {
        class UserNamePrincipal(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") UserNamePrincipal(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") UserNamePrincipal(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Required) {
                authenticate("second", strategy = AuthenticationStrategy.Required) {
                    route("/{...}") {
                        handle {
                            call.respondText(
                                call.principal<UserNamePrincipal>("first")?.name +
                                    " " + call.principal<UserNamePrincipal>("second")?.name
                            )
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first second", it.bodyAsText())
        }
    }

    @Test
    fun testDefaultConfigurationOverwrittenWithRequired() = testApplication {
        class Principal1(val name: String) : Principal
        class Principal2(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") Principal1(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") Principal2(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Required) {
                authenticate("first") {
                    authenticate("second", strategy = AuthenticationStrategy.Required) {
                        route("/{...}") {
                            handle {
                                call.respondText(
                                    call.principal<Principal1>()?.name + " " + call.principal<Principal2>()?.name
                                )
                            }
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first second", it.bodyAsText())
        }
    }

    @Test
    fun testRequiredAndDefaultConfigurations() = testApplication {
        class Principal1(val name: String) : Principal
        class Principal2(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") Principal1(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") Principal2(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Required) {
                authenticate("second") {
                    route("/{...}") {
                        handle {
                            call.respondText(
                                call.principal<Principal1>()?.name + " " + call.principal<Principal2>()?.name
                            )
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first null", it.bodyAsText())
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first null", it.bodyAsText())
        }
    }

    @Test
    fun testRequiredAndOptionalConfigurations() = testApplication {
        class Principal1(val name: String) : Principal
        class Principal2(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") Principal1(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") Principal2(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Optional) {
                authenticate("second", strategy = AuthenticationStrategy.Required) {
                    route("/{...}") {
                        handle {
                            call.respondText(
                                call.principal<Principal1>()?.name + " " + call.principal<Principal2>()?.name
                            )
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("null second", it.bodyAsText())
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("null second", it.bodyAsText())
        }
    }

    @Test
    fun testOptionalAndDefaultConfigurations() = testApplication {
        class Principal1(val name: String) : Principal
        class Principal2(val name: String) : Principal

        install(Authentication) {
            form("first") { validate { c -> if (c.name == "first") Principal1(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") Principal2(c.name) else null } }
        }

        routing {
            authenticate("first", strategy = AuthenticationStrategy.Optional) {
                authenticate("second") {
                    route("/{...}") {
                        handle {
                            call.respondText(
                                call.principal<Principal1>()?.name + " " + call.principal<Principal2>()?.name
                            )
                        }
                    }
                }
            }
        }

        client.post("/nobody") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=nobody&password=p")
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }

        client.post("/first") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first null", it.bodyAsText())
        }

        client.post("/second") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("null second", it.bodyAsText())
        }

        client.post("/both") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
            setBody("user=first&password=p")
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("first null", it.bodyAsText())
        }
    }

    @Test
    fun testMultipleConfigurations() {
        withTestApplication {
            application.install(Authentication) {
                form("first") { validate { c -> if (c.name == "first") UserIdPrincipal(c.name) else null } }
                basic("second") { validate { c -> if (c.name == "second") UserIdPrincipal(c.name) else null } }
            }

            application.routing {
                authenticate("first", "second") {
                    route("/both") {
                        handle {
                            assertEquals("first", call.authentication.principal<UserIdPrincipal>()?.name)
                            call.respondText("OK")
                        }
                    }
                    route("/{name}") {
                        handle {
                            assertEquals(
                                call.parameters["name"],
                                call.authentication.principal<UserIdPrincipal>()?.name
                            )
                            call.respondText("OK")
                        }
                    }
                }
            }

            handleRequest(HttpMethod.Post, "/nobody") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=nobody&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Post, "/first") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=first&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Get, "/second") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                addHeader(
                    HttpHeaders.Authorization,
                    HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
                )
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Post, "/both") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                addHeader(
                    HttpHeaders.Authorization,
                    HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
                )
                setBody("user=first&password=p")
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }
        }
    }

    @Test
    fun testMultipleConfigurationsInstallLevel() = testApplication {
        install(Authentication) {
            basic("first") { validate { c -> if (c.name == "first") UserIdPrincipal(c.name) else null } }
            basic("second") { validate { c -> if (c.name == "second") UserIdPrincipal(c.name) else null } }
        }

        routing {
            authenticate("first") {
                post("/first") {
                    call.respondText("From: ${call.principal<UserIdPrincipal>()!!.name}")
                }
            }
            route("/second") {
                authenticate("second") {
                    post {
                        call.respondText("From: ${call.principal<UserIdPrincipal>()!!.name}")
                    }
                }
            }
        }

        client.post("/first") {
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "first:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("From: first", it.bodyAsText())
        }

        client.post("/second") {
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            assertEquals("From: second", it.bodyAsText())
        }

        client.post("/first") {
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("second", "second:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }
        client.post("/second") {
            header(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "first:".encodeBase64()).render()
            )
        }.let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
        }
    }

    @Test
    fun testNoRouting() {
        withTestApplication {
            application.install(Authentication) {
                form { validate { UserIdPrincipal(it.name) } }
            }

            application.install(AuthenticationInterceptors)

            application.intercept(ApplicationCallPipeline.Call) {
                call.respondText("OK")
            }

            handleRequest(HttpMethod.Get, "/").let { call ->
                assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=any&password=")
            }.let { call ->
                assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            }
        }
    }

    @Test
    fun testModifyingAuthentication() = withTestApplication {
        application.authentication {
            basic("1") {
                validate { it.name.takeIf { it == "aaa" }?.let { UserIdPrincipal(it) } }
            }
        }

        on("add a new auth method") {
            application.authentication {
                form("2") {
                    validate { it.name.takeIf { it == "bbb" }?.let { UserIdPrincipal(it) } }
                }
            }
        }

        on("auth method name conflict") {
            application.authentication {
                assertFails {
                    basic("2") {}
                }
            }
        }

        application.routing {
            authenticate("1", "2") {
                get("/") {
                    call.respondText(call.principal<UserIdPrincipal>()?.name ?: "?")
                }
            }
        }

        on("attempt to auth") {
            fun TestApplicationRequest.addFormAuth(name: String) {
                addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("user=$name&password=")
            }

            on("try first auth provider") {
                val call = handleRequest(HttpMethod.Get, "/") {
                    addBasicAuth("aaa")
                }
                assertEquals("aaa", call.response.content)
            }
            on("try second auth provider") {
                val call = handleRequest(HttpMethod.Get, "/") {
                    addFormAuth("bbb")
                }
                assertEquals("bbb", call.response.content)
            }
            on("try invalid user name") {
                val call = handleRequest(HttpMethod.Get, "/") {
                    addBasicAuth("unknown")
                }
                assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
            }
        }
    }

    @Test
    fun testAuthenticateOptionally() {
        withTestApplication {
            application.apply {
                authentication {
                    basic {
                        validate { it.name.takeIf { it == "aaa" }?.let { UserIdPrincipal(it) } }
                    }
                }
                routing {
                    authenticate(optional = true) {
                        get("/auth") {
                            call.respond("OK:${call.authentication.principal<UserIdPrincipal>()?.name}")
                        }
                    }
                }

                on("try call with authentication") {
                    val call = handleRequest(HttpMethod.Get, "/auth") {
                        addBasicAuth("aaa")
                    }
                    assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
                    assertEquals("OK:aaa", call.response.content)
                }
                on("try call with bad authentication") {
                    val call = handleRequest(HttpMethod.Get, "/auth") {
                        addBasicAuth("unknown")
                    }
                    assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
                }
                on("try call without authentication") {
                    val call = handleRequest(HttpMethod.Get, "/auth") {}
                    assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
                    assertEquals("OK:null", call.response.content)
                }
            }
        }
    }

    @Test
    fun testAuthProviderFailureNoChallenge(): Unit = withTestApplication {
        class CustomErrorCause : AuthenticationFailedCause.Error("custom error")

        application.apply {
            authentication {
                provider("custom") {
                    authenticate { context ->
                        context.error(this, AuthenticationFailedCause.Error("test"))
                    }
                }
                provider("custom-inheritance") {
                    authenticate { context ->
                        context.error(this, CustomErrorCause())
                    }
                }
            }
            routing {
                authenticate("custom") {
                    get("/fail") {
                        fail("shouldn't reach here")
                    }
                }
                authenticate("custom", optional = true) {
                    get("/pass") {
                        call.respondText("OK")
                    }
                }
                authenticate("custom-inheritance") {
                    get("/fail-inheritance") {
                        fail("shouldn't reach here")
                    }
                }
                authenticate("custom-inheritance", optional = true) {
                    get("/pass-inheritance") {
                        call.respondText("OK")
                    }
                }
            }
        }

        handleRequest(HttpMethod.Get, "/fail").let { call ->
            assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
        }

        handleRequest(HttpMethod.Get, "/pass").let { call ->
            assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            assertEquals("OK", call.response.content)
        }

        handleRequest(HttpMethod.Get, "/fail-inheritance").let { call ->
            assertEquals(HttpStatusCode.Unauthorized.value, call.response.status()?.value)
        }

        handleRequest(HttpMethod.Get, "/pass-inheritance").let { call ->
            assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            assertEquals("OK", call.response.content)
        }
    }

    @Test
    fun testAuthProviderFailureWithChallenge(): Unit = withTestApplication {
        application.apply {
            authentication {
                provider("custom") {
                    authenticate { context ->
                        context.challenge(this, AuthenticationFailedCause.Error("test")) { challenge, call ->
                            call.respondText("Challenge")
                            challenge.complete()
                        }
                    }
                }
            }
            routing {
                authenticate("custom") {
                    get("/fail") {
                        fail("shouldn't reach here")
                    }
                }
                authenticate("custom", optional = true) {
                    get("/pass") {
                        call.respondText("OK")
                    }
                }
            }
        }

        handleRequest(HttpMethod.Get, "/fail").let { call ->
            assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            assertEquals("Challenge", call.response.content)
        }

        handleRequest(HttpMethod.Get, "/pass").let { call ->
            assertEquals(HttpStatusCode.OK.value, call.response.status()?.value)
            assertEquals("OK", call.response.content)
        }
    }

    @Test
    fun testAuthDoesntChangeRoutePriority(): Unit = withTestApplication {
        application.apply {
            application.install(Authentication) {
                form { validate { c -> UserIdPrincipal(c.name) } }
            }

            routing {
                get("/bar:{baz}") {
                    call.respondText("bar")
                }
                authenticate {
                    get("/{baz}") {
                        call.respondText("baz")
                    }
                }
                get("/foo:{baz}") {
                    call.respondText("foo")
                }
            }
        }

        handleRequest(HttpMethod.Get, "/foo:asd") {
            addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=username&password=p")
        }.let { call ->
            assertEquals("foo", call.response.content)
        }
        handleRequest(HttpMethod.Get, "/bar:asd") {
            addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=username&password=p")
        }.let { call ->
            assertEquals("bar", call.response.content)
        }
        handleRequest(HttpMethod.Get, "/baz") {
            addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=username&password=p")
        }.let { call ->
            assertEquals("baz", call.response.content)
        }
    }

    @Test
    fun testAuthInterceptorKeepCallParameters() = testApplication {
        application {
            intercept(ApplicationCallPipeline.Monitoring) {
                call.authentication
            }

            install(Authentication) {
                basic {
                    validate {
                        assertEquals("id1", parameters["id"])
                        it.name.takeIf { it == "aaa" }?.let { UserIdPrincipal(it) }
                    }
                }
            }

            routing {
                authenticate {
                    get("/{id}") {
                        call.respond("Id: ${call.parameters["id"]}")
                    }
                }
            }
        }

        client.get("/id1") {
            headers.append(
                HttpHeaders.Authorization,
                HttpAuthHeader.Single("basic", "aaa:bbb".encodeBase64()).render()
            )
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Id: id1", response.bodyAsText())
        }
    }

    @Test
    fun testCompleteApplication() = testApplication {
        install(Sessions) {
            cookie<TestSession>("S")
        }
        install(Authentication) {
            session<TestSession>("S") {
                challenge {} // a root session provider doesn't send any challenge
                validate { UserIdPrincipal(it.name) } // but optionally provides an authenticated user
            }
            session<TestSession>("S_web") {
                challenge("/login") // for web roots it does redirect to login
                validate { UserIdPrincipal(it.name) } // unfortunately it does check twice
            }
            basic("B") {
                realm = "test-app"
                validate { UserIdPrincipal(it.name) }
            }
            form("F") {
                validate { UserIdPrincipal(it.name) }
            }
        }
        routing {
            authenticate("S") {
                get("/") {
                    val logText = call.principal<UserIdPrincipal>()?.name
                        ?.let { "Logged in as $it." } ?: "Not logged in."
                    call.respondText("Public index. $logText")
                }
                route("/user") {
                    authenticate("S_web") {
                        get("profile") {
                            call.respondText("Profile for ${call.principal<UserIdPrincipal>()?.name}.")
                        }
                    }
                    authenticate("B") {
                        get("files/{name...}") {
                            call.respondText(
                                "File ${call.parameters["name"]} for user " +
                                    "${call.principal<UserIdPrincipal>()?.name}."
                            )
                        }
                    }
                }
                get("/login") {
                    val user = call.principal<UserIdPrincipal>()
                    if (user != null) {
                        call.respondRedirect("/")
                    } else {
                        call.respondText("Login form goes here.")
                    }
                }
                authenticate("F") {
                    post("/login") {
                        val user = call.principal<UserIdPrincipal>()
                        assertNotNull(user)
                        call.sessions.set(TestSession(user.name))
                        call.respondText("Logged in successfully as ${user.name}.")
                    }
                }
            }
        }

        val serializedSession = defaultSessionSerializer<TestSession>().serialize(
            TestSession("tester")
        )
        val sessionCookieContent = "S=$serializedSession"
        fun HttpRequestBuilder.addCookie() {
            header(HttpHeaders.Cookie, sessionCookieContent)
        }

        fun HttpRequestBuilder.addFormAuth(name: String, pass: String) {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("user=$name&password=$pass")
        }

        val client = createClient {
            followRedirects = false
        }

        val response1 = client.get("/")
        assertTrue { response1.status.isSuccess() }
        assertEquals("Public index. Not logged in.", response1.bodyAsText())

        val response2 = client.get("/") {
            addCookie()
        }
        assertTrue { response2.status.isSuccess() }
        assertEquals("Public index. Logged in as tester.", response2.bodyAsText())

        val response3 = client.get("/user/profile")
        assertEquals("/login", response3.headers[HttpHeaders.Location])

        val response4 = client.get("/user/profile") {
            addCookie()
        }
        assertEquals("Profile for tester.", response4.bodyAsText())

        val response5 = client.get("/login") {
            addCookie()
        }
        assertEquals(HttpStatusCode.Found, response5.status)

        val response6 = client.get("/login")
        assertEquals("Login form goes here.", response6.bodyAsText())

        val response7 = client.post("/login") {
            addFormAuth("tester", "")
        }
        val cookies = response7.headers[HttpHeaders.SetCookie]?.let { parseServerSetCookieHeader(it) }
        assertNotNull(cookies, "Set-Cookie should be sent")
        assertEquals(serializedSession, cookies.value)
        assertEquals("Logged in successfully as tester.", response7.bodyAsText())

        val response8 = client.get("/user/files/doc1.txt") {
            addCookie()
        }
        assertEquals("File doc1.txt for user tester.", response8.bodyAsText())

        val firstAttempt = client.get("/user/files/doc1.txt")
        // with no auth header we should get basic auth challenge
        assertEquals(
            "Basic realm=test-app, charset=UTF-8",
            firstAttempt.headers[HttpHeaders.WWWAuthenticate]
        )

        // so a download tool should show a prompt so user can provide name and password
        // and retry with basic auth credentials
        val response9 = client.get("/user/files/doc1.txt") {
            addBasicAuth()
        }
        assertEquals("File doc1.txt for user tester.", response9.bodyAsText())
    }

    private fun TestApplicationRequest.addBasicAuth(name: String = "tester") {
        addHeader(
            HttpHeaders.Authorization,
            HttpAuthHeader.Single("basic", "$name:".encodeBase64()).render()
        )
    }

    private fun HttpRequestBuilder.addBasicAuth(name: String = "tester") {
        header(
            HttpHeaders.Authorization,
            HttpAuthHeader.Single("basic", "$name:".encodeBase64()).render()
        )
    }

    @Serializable
    data class TestSession(val name: String)
}
