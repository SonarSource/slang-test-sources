/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.ratelimit

import io.ktor.server.application.*
import io.ktor.util.*
import io.ktor.util.collections.*
import io.ktor.util.logging.*

internal val LOGGER = KtorSimpleLogger("io.ktor.server.plugins.ratelimit.RateLimit")
internal val LIMITER_NAME_GLOBAL = RateLimitName("KTOR_GLOBAL_RATE_LIMITER")
internal val LIMITER_NAME_EMPTY = RateLimitName("KTOR_NO_NAME_RATE_LIMITER")

internal val RateLimiterInstancesRegistryKey =
    AttributeKey<ConcurrentMap<Pair<RateLimitName, Any>, RateLimiter>>("RateLimiterInstancesRegistryKey")

internal val RateLimiterConfigsRegistryKey =
    AttributeKey<Map<RateLimitName, RateLimitProvider>>("RateLimiterConfigsRegistryKey")

/**
 * A plugin that provides rate limiting for incoming requests.
 */
public val RateLimit: ApplicationPlugin<RateLimitConfig> = createApplicationPlugin("RateLimit", ::RateLimitConfig) {
    val global = pluginConfig.global
    val providers = when {
        global != null -> pluginConfig.providers + (LIMITER_NAME_GLOBAL to global)
        else -> pluginConfig.providers.toMap()
    }
    check(providers.isNotEmpty()) { "At least one provider must be specified" }
    application.attributes.put(RateLimiterConfigsRegistryKey, providers)

    if (global == null) return@createApplicationPlugin
    application.install(RateLimitApplicationInterceptors) {
        this.providerNames = listOf(LIMITER_NAME_GLOBAL)
    }
}

internal class RateLimitProvider(config: RateLimitProviderConfig) {
    val name = config.name
    val requestKey = config.requestKey
    val requestWeight = config.requestWeight
    val modifyResponse = config.modifyResponse
    val rateLimiter = config.rateLimiterProvider
        ?: throw IllegalStateException("Please set rateLimiter in the plugin install block")
}
