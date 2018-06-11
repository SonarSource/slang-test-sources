package io.ktor.features

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.util.*
import java.util.*
import kotlin.reflect.*

/**
 * Represents request and connection parameters possibly overridden via https headers.
 * By default it fallbacks to [ApplicationRequest.local]
 */
val ApplicationRequest.origin: RequestConnectionPoint
    get() = call.attributes.getOrNull(@Suppress("DEPRECATION") (io.ktor.features.MutableOriginConnectionPointKey)) ?: local

@Deprecated("Not yet decided about API")
val MutableOriginConnectionPointKey = AttributeKey<MutableOriginConnectionPoint>("MutableOriginConnectionPointKey")

class MutableOriginConnectionPoint(delegate: RequestConnectionPoint) : RequestConnectionPoint {
    override var version by AssignableWithDelegate { delegate.version }
    override var uri by AssignableWithDelegate { delegate.uri }
    override var method by AssignableWithDelegate { delegate.method }
    override var scheme by AssignableWithDelegate { delegate.scheme }
    override var host by AssignableWithDelegate { delegate.host }
    override var port by AssignableWithDelegate { delegate.port }
    override var remoteHost by AssignableWithDelegate { delegate.remoteHost }
}

object XForwardedHeadersSupport : ApplicationFeature<ApplicationCallPipeline, XForwardedHeadersSupport.Config, XForwardedHeadersSupport.Config> {

    override val key = AttributeKey<Config>("XForwardedHeadersSupport")

    override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit): Config {
        val config = Config()
        configure(config)

        pipeline.intercept(ApplicationCallPipeline.Infrastructure) {
            call.forEachHeader(config.protoHeaders) { value ->
                call.mutableOriginConnectionPoint.let { route ->
                    route.scheme = value
                    URLProtocol.byName[value]?.let {
                        route.port = it.defaultPort
                    }
                }
            }

            call.forEachHeader(config.httpsFlagHeaders) { value ->
                if (value.toBoolean()) {
                    call.mutableOriginConnectionPoint.let { route ->
                        route.scheme = "https"
                        URLProtocol.byName[route.scheme]?.let {
                            route.port = it.defaultPort
                        }
                    }
                }
            }

            call.forEachHeader(config.hostHeaders) { value ->
                val host = value.substringBefore(':')
                val port = value.substringAfter(':', "")

                call.mutableOriginConnectionPoint.let { route ->
                    route.host = host
                    port.tryParseInt()?.let {
                        route.port = it
                    } ?: URLProtocol.byName[route.scheme]?.let {
                        route.port = it.defaultPort
                    }
                }
            }

            call.forEachHeader(config.forHeaders) { xForwardedFor ->
                val remoteHost = xForwardedFor.split(",").first().trim()
                if (remoteHost.isNotBlank()) {
                    call.mutableOriginConnectionPoint.remoteHost = remoteHost
                }
            }
        }

        return config
    }

    private fun String.toBoolean() = this == "yes" || this == "true" || this == "on"

    class Config {
        val hostHeaders = arrayListOf(HttpHeaders.XForwardedHost, HttpHeaders.XForwardedServer)
        val protoHeaders = arrayListOf(HttpHeaders.XForwardedProto, "X-Forwarded-Protocol")
        val forHeaders = arrayListOf(HttpHeaders.XForwardedFor)
        val httpsFlagHeaders = arrayListOf("X-Forwarded-SSL", "Front-End-Https")
    }
}

/**
 * Forwarded header support. See RFC 7239 https://tools.ietf.org/html/rfc7239
 */
object ForwardedHeaderSupport : ApplicationFeature<ApplicationCallPipeline, Unit, Unit> {
    val ForwardedParsedKey = AttributeKey<List<ForwardedHeaderValue>>("ForwardedParsedKey")
    override val key = AttributeKey<Unit>("ForwardedHeaderSupport")

    override fun install(pipeline: ApplicationCallPipeline, configure: Unit.() -> Unit) {
        configure(Unit)

        pipeline.intercept(ApplicationCallPipeline.Infrastructure) {
            val forwarded = call.request.forwarded()
            if (forwarded != null) {
                call.attributes.put(ForwardedParsedKey, forwarded)
                val firstForward = forwarded.firstOrNull()

                if (firstForward != null) {
                    if (firstForward.proto != null) {
                        call.mutableOriginConnectionPoint.let { route ->
                            val proto: String = firstForward.proto
                            route.scheme = proto
                            URLProtocol.byName[proto]?.let { p ->
                                route.port = p.defaultPort
                            }
                        }
                    }
                    if (firstForward.forParam != null) {
                        val remoteHost = firstForward.forParam.split(",").first().trim()
                        if (remoteHost.isNotBlank()) {
                            call.mutableOriginConnectionPoint.remoteHost = remoteHost
                        }
                    }
                    if (firstForward.host != null) {
                        val host = firstForward.host.substringBefore(':')
                        val port = firstForward.host.substringAfter(':', "")

                        call.mutableOriginConnectionPoint.let { route ->
                            route.host = host
                            port.tryParseInt()?.let { route.port = it } ?: URLProtocol.byName[route.scheme]?.let {
                                route.port = it.defaultPort
                            }
                        }
                    }
                }
            }
        }
    }

    data class ForwardedHeaderValue(val host: String?, val by: String?, val forParam: String?, val proto: String?, val others: Map<String, String>)

    // do we need it public?
    private fun ApplicationRequest.forwarded() = headers.getAll(HttpHeaders.Forwarded)?.flatMap { parseHeaderValue(";" + it) }?.mapNotNull { parseForwardedValue(it) }

    private fun parseForwardedValue(value: HeaderValue): ForwardedHeaderValue? {
        val map = value.params.associateByTo(HashMap<String, String>(), { it.name }, { it.value })

        return ForwardedHeaderValue(map.remove("host"), map.remove("by"), map.remove("for"), map.remove("proto"), map)
    }
}

internal val ApplicationCall.mutableOriginConnectionPoint: MutableOriginConnectionPoint
    get() = attributes.computeIfAbsent(@Suppress("DEPRECATION") MutableOriginConnectionPointKey) { MutableOriginConnectionPoint(request.local) }

private inline fun ApplicationCall.forEachHeader(headers: List<String>, block: (String) -> Unit) {
    for (name in headers) {
        val value = request.header(name)
        if (value != null) {
            block(value)
        }
    }
}

private class AssignableWithDelegate<T : Any>(val property: () -> T) {
    private var assigned: T? = null

    @Suppress("UNCHECKED_CAST")
    operator fun getValue(thisRef: Any, property: KProperty<*>): T = assigned ?: property()

    operator fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        assigned = value
    }
}

private fun String.tryParseInt() = try {
    if (isNotEmpty()) toInt() else null
} catch (nfe: NumberFormatException) {
    null
}