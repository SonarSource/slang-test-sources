/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("DEPRECATION")

package io.ktor.server.testing

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.testing.client.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * A client attached to [TestApplication].
 */
@KtorDsl
public interface ClientProvider {
    /**
     * Returns a client with the default configuration.
     * @see [testApplication]
     */
    public val client: HttpClient

    /**
     * Creates a client with a custom configuration.
     * For example, to send JSON data in a test POST/PUT request, you can install the `ContentNegotiation` plugin:
     * ```kotlin
     * fun testPostCustomer() = testApplication {
     *     val client = createClient {
     *         install(ContentNegotiation) {
     *             json()
     *         }
     *     }
     * }
     * ```
     * @see [testApplication]
     */
    @KtorDsl
    public fun createClient(block: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit): HttpClient
}

/**
 * A configured instance of a test application running locally.
 * @see [testApplication]
 */
public class TestApplication internal constructor(
    private val builder: ApplicationTestBuilder
) : ClientProvider by builder {

    internal val engine by lazy { builder.engine }

    /**
     * Starts this [TestApplication] instance.
     */
    public fun start() {
        if (builder.engine.state.value != TestApplicationEngine.State.Stopped) return
        builder.engine.start()
        builder.externalServices.externalApplications.values.forEach { it.start() }
    }

    /**
     * Stops this [TestApplication] instance.
     */
    public fun stop() {
        builder.engine.stop(0, 0)
        builder.externalServices.externalApplications.values.forEach { it.stop() }
        client.close()
    }
}

/**
 * Creates an instance of [TestApplication] configured with the builder [block].
 * Make sure to call [TestApplication.stop] after your tests.
 * @see [testApplication]
 */
@KtorDsl
public fun TestApplication(
    block: TestApplicationBuilder.() -> Unit
): TestApplication {
    val builder = ApplicationTestBuilder()
    val testApplication = TestApplication(builder)
    builder.block()
    return testApplication
}

/**
 * Registers mocks for external services.
 */
@KtorDsl
public class ExternalServicesBuilder internal constructor(private val testApplicationBuilder: TestApplicationBuilder) {

    @Deprecated(message = "This constructor will be removed from public API", level = DeprecationLevel.HIDDEN)
    public constructor() : this(TestApplicationBuilder())

    private val externalApplicationBuilders = mutableMapOf<String, () -> TestApplication>()
    internal val externalApplications: Map<String, TestApplication> by lazy {
        externalApplicationBuilders.mapValues { it.value.invoke() }
    }

    /**
     * Registers a mock for an external service specified by [hosts] and configured with [block].
     * @see [testApplication]
     */
    @KtorDsl
    public fun hosts(vararg hosts: String, block: Application.() -> Unit) {
        check(hosts.isNotEmpty()) { "hosts can not be empty" }

        hosts.forEach {
            val protocolWithAuthority = Url(it).protocolWithAuthority
            externalApplicationBuilders[protocolWithAuthority] = {
                TestApplication {
                    environment(this@ExternalServicesBuilder.testApplicationBuilder.environmentBuilder)
                    applicationModules.add(block)
                }
            }
        }
    }
}

/**
 * A builder for [TestApplication].
 */
@KtorDsl
public open class TestApplicationBuilder {

    private var built = false

    internal val externalServices = ExternalServicesBuilder(this)
    internal val applicationModules = mutableListOf<Application.() -> Unit>()
    internal var environmentBuilder: ApplicationEngineEnvironmentBuilder.() -> Unit = {}
    internal val job = Job()

    internal val environment by lazy {
        built = true
        createTestEnvironment {
            modules.addAll(this@TestApplicationBuilder.applicationModules)
            developmentMode = true
            val oldConfig = config
            this@TestApplicationBuilder.environmentBuilder(this)
            if (config == oldConfig) { // the user did not set config. load the default one
                config = DefaultTestConfig()
            }
            parentCoroutineContext += this@TestApplicationBuilder.job
        }
    }

    internal val engine by lazy {
        TestApplicationEngine(environment)
    }

    /**
     * Builds mocks for external services using [ExternalServicesBuilder].
     * @see [testApplication]
     */
    @KtorDsl
    public fun externalServices(block: ExternalServicesBuilder.() -> Unit) {
        checkNotBuilt()
        externalServices.block()
    }

    /**
     * Builds an environment using [block].
     * @see [testApplication]
     */
    @KtorDsl
    public fun environment(block: ApplicationEngineEnvironmentBuilder.() -> Unit) {
        checkNotBuilt()
        val oldBuilder = environmentBuilder
        environmentBuilder = { oldBuilder(); block() }
    }

    /**
     * Adds a module to [TestApplication].
     * @see [testApplication]
     */
    @KtorDsl
    public fun application(block: Application.() -> Unit) {
        checkNotBuilt()
        applicationModules.add(block)
    }

    /**
     * Installs a [plugin] into [TestApplication]
     */
    @Suppress("UNCHECKED_CAST")
    @KtorDsl
    public fun <P : Pipeline<*, ApplicationCall>, B : Any, F : Any> install(
        plugin: Plugin<P, B, F>,
        configure: B.() -> Unit = {}
    ) {
        checkNotBuilt()
        applicationModules.add { install(plugin as Plugin<ApplicationCallPipeline, B, F>, configure) }
    }

    /**
     * Installs routing into [TestApplication]
     */
    @KtorDsl
    public fun routing(configuration: Routing.() -> Unit) {
        checkNotBuilt()
        applicationModules.add { routing(configuration) }
    }

    private fun checkNotBuilt() {
        check(!built) {
            "The test application has already been built. Make sure you configure the application " +
                "before accessing the client for the first time."
        }
    }
}

/**
 * A builder for a test that uses [TestApplication].
 */
@KtorDsl
public class ApplicationTestBuilder : TestApplicationBuilder(), ClientProvider {

    override val client by lazy { createClient { } }

    /**
     * Starts instance of [TestApplication].
     * Usually, users do not need to call this method because application will start on the first client call.
     * But it's still useful when you need to test your application lifecycle events.
     *
     * After calling this method, no modification of the application is allowed.
     */
    public fun startApplication() {
        val testApplication = TestApplication(this)
        testApplication.start()
    }

    @KtorDsl
    override fun createClient(
        block: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit
    ): HttpClient = HttpClient(DelegatingTestClientEngine) {
        engine {
            parentJob = this@ApplicationTestBuilder.job
            appEngineProvider = { this@ApplicationTestBuilder.engine }
            externalApplicationsProvider = { this@ApplicationTestBuilder.externalServices.externalApplications }
        }
        block()
    }
}

/**
 * Creates a test using [TestApplication].
 * To test a server Ktor application, do the following:
 * 1. Use [testApplication] function to set up a configured instance of a test application running locally.
 * 2. Use the [HttpClient] instance inside a test application to make a request to your server,
 * receive a response, and make assertions.
 *
 * Suppose, you have the following route that accepts GET requests made to the `/` path
 * and responds with a plain text response:
 * ```kotlin
 * routing {
 *     get("/") {
 *         call.respondText("Hello, world!")
 *     }
 * }
 * ```
 *
 * A test for this route will look as follows:
 * ```kotlin
 * @Test
 * fun testRoot() = testApplication {
 *     val response = client.get("/")
 *     assertEquals(HttpStatusCode.OK, response.status)
 *     assertEquals("Hello, world!", response.bodyAsText())
 * }
 * ```
 *
 * _Note: If you have the `application.conf` file in the `resources` folder,
 * [testApplication] loads all modules and properties specified in the configuration file automatically._
 *
 * You can learn more from [Testing](https://ktor.io/docs/testing.html).
 */
@KtorDsl
public fun testApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
    testApplication(EmptyCoroutineContext, block)
}

/**
 * Creates a test using [TestApplication].
 * To test a server Ktor application, do the following:
 * 1. Use [testApplication] function to set up a configured instance of a test application running locally.
 * 2. Use the [HttpClient] instance inside a test application to make a request to your server,
 * receive a response, and make assertions.
 *
 * Suppose, you have the following route that accepts GET requests made to the `/` path
 * and responds with a plain text response:
 * ```kotlin
 * routing {
 *     get("/") {
 *         call.respondText("Hello, world!")
 *     }
 * }
 * ```
 *
 * A test for this route will look as follows:
 * ```kotlin
 * @Test
 * fun testRoot() = testApplication {
 *     val response = client.get("/")
 *     assertEquals(HttpStatusCode.OK, response.status)
 *     assertEquals("Hello, world!", response.bodyAsText())
 * }
 * ```
 *
 * _Note: If you have the `application.conf` file in the `resources` folder,
 * [testApplication] loads all modules and properties specified in the configuration file automatically._
 *
 * You can learn more from [Testing](https://ktor.io/docs/testing.html).
 */
@KtorDsl
public fun testApplication(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    block: suspend ApplicationTestBuilder.() -> Unit
) {
    val builder = ApplicationTestBuilder()
        .apply {
            runBlocking(parentCoroutineContext) {
                if (parentCoroutineContext != EmptyCoroutineContext) {
                    environment {
                        this.parentCoroutineContext = parentCoroutineContext
                    }
                }
                block()
            }
        }

    val testApplication = TestApplication(builder)
    testApplication.start()
    testApplication.stop()
}
