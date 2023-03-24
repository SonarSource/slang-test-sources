/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.server.jetty.http2

import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.servlet.*
import org.eclipse.jetty.servlet.*
import javax.servlet.*

// the factory and engine are only suitable for testing
// you shouldn't use it for production code

internal class Servlet(private val async: Boolean) :
    ApplicationEngineFactory<JettyServletApplicationEngine, JettyApplicationEngineBase.Configuration> {
    override fun create(
        environment: ApplicationEngineEnvironment,
        configure: JettyApplicationEngineBase.Configuration.() -> Unit
    ): JettyServletApplicationEngine {
        return JettyServletApplicationEngine(environment, configure, async)
    }
}

internal class JettyServletApplicationEngine(
    environment: ApplicationEngineEnvironment,
    configure: Configuration.() -> Unit,
    async: Boolean
) : JettyApplicationEngineBase(environment, configure) {
    init {
        server.handler = ServletContextHandler().apply {
            classLoader = environment.classLoader
            setAttribute(ServletApplicationEngine.ApplicationEngineEnvironmentAttributeKey, environment)
            setAttribute(ServletApplicationEngine.ApplicationEnginePipelineAttributeKey, pipeline)

            insertHandler(
                ServletHandler().apply {
                    val holder = ServletHolder(
                        "ktor-servlet",
                        ServletApplicationEngine::class.java
                    ).apply {
                        isAsyncSupported = async
                        registration.setLoadOnStartup(1)
                        registration.setMultipartConfig(MultipartConfigElement(System.getProperty("java.io.tmpdir")))
                        registration.setAsyncSupported(async)
                    }

                    addServlet(holder)
                    addServletMapping(
                        ServletMapping().apply {
                            pathSpecs = arrayOf("*.", "/*")
                            servletName = "ktor-servlet"
                        }
                    )
                }
            )
        }
    }
}
