/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.testing

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

expect abstract class EngineTestBase<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(
    applicationEngineFactory: ApplicationEngineFactory<TEngine, TConfiguration>,
) : BaseTest, CoroutineScope {

    override val coroutineContext: CoroutineContext

    @Target(AnnotationTarget.FUNCTION)
    @Retention
    protected annotation class Http2Only()

    val applicationEngineFactory: ApplicationEngineFactory<TEngine, TConfiguration>

    protected var enableHttp2: Boolean
    protected var enableSsl: Boolean
    protected var enableCertVerify: Boolean

    protected var port: Int
    protected var sslPort: Int
    protected var server: TEngine?

    protected fun createAndStartServer(
        log: Logger? = null,
        parent: CoroutineContext = EmptyCoroutineContext,
        routingConfigurer: Routing.() -> Unit
    ): TEngine

    protected open fun plugins(application: Application, routingConfigurer: Routing.() -> Unit)

    protected fun withUrl(
        path: String,
        builder: suspend HttpRequestBuilder.() -> Unit = {},
        block: suspend HttpResponse.(Int) -> Unit
    )
}
