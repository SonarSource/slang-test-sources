/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.servlet.jakarta

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import jakarta.servlet.http.*

public abstract class ServletApplicationRequest(
    call: ApplicationCall,
    public val servletRequest: HttpServletRequest
) : BaseApplicationRequest(call) {

    override val local: RequestConnectionPoint = ServletConnectionPoint(servletRequest)

    override val queryParameters: Parameters by lazy { encodeParameters(rawQueryParameters) }

    override val rawQueryParameters: Parameters by lazy(LazyThreadSafetyMode.NONE) {
        val uri = servletRequest.queryString ?: return@lazy Parameters.Empty
        parseQueryString(uri, decode = false)
    }

    override val headers: Headers = ServletApplicationRequestHeaders(servletRequest)

    @Suppress("LeakingThis") // this is safe because we don't access any content in the request
    override val cookies: RequestCookies = ServletApplicationRequestCookies(servletRequest, this)
}
