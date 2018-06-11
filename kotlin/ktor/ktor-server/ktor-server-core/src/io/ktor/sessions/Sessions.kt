package io.ktor.sessions

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.util.*
import kotlin.reflect.*

/**
 * Sessions reature that provides a mechanism to persist information between requests.
 */
class Sessions(val providers: List<SessionProvider>) {
    class Configuration {
        val providers = mutableListOf<SessionProvider>()

        fun register(provider: SessionProvider) {
            // todo: check that type & name is unique
            providers.add(provider)
        }
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Sessions.Configuration, Sessions> {
        override val key = AttributeKey<Sessions>("Sessions")
        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): Sessions {
            val configuration = Sessions.Configuration().apply(configure)
            val sessions = Sessions(configuration.providers)

            // For each call, call each provider and retrieve session data if needed.
            // Capture data in the attribute's value
            pipeline.intercept(ApplicationCallPipeline.Infrastructure) {
                val providerData = sessions.providers.associateBy({ it.name }) {
                    val receivedValue = it.transport.receive(call)
                    val unwrapped = it.tracker.load(call, receivedValue)
                    SessionProviderData(unwrapped, unwrapped != null, it)
                }
                val sessionData = SessionData(sessions, providerData)
                call.attributes.put(SessionKey, sessionData)
            }

            // When response is being sent, call each provider to update/remove session data
            pipeline.sendPipeline.intercept(ApplicationSendPipeline.Before) {
                val sessionData = call.attributes.getOrNull(SessionKey)
                if (sessionData == null) {
                    // If sessionData is not available it means response happened before Session feature got a
                    // chance to deserialize the data. We should ignore this call in this case.
                    // An example would be CORS feature responding with 403 Forbidden
                    return@intercept
                }

                sessionData.providerData.forEach { (_, data) ->
                    when {
                        data.value != null -> {
                            val value = data.value
                            value ?: throw IllegalStateException("Session data shouldn't be null in Modified state")
                            val wrapped = data.provider.tracker.store(call, value)
                            data.provider.transport.send(call, wrapped)
                        }
                        data.incoming && data.value == null -> {
                            /* Deleted session should be cleared off */
                            data.provider.transport.clear(call)
                            data.provider.tracker.clear(call)
                        }
                    }
                }
            }

            return sessions
        }
    }
}

val ApplicationCall.sessions: CurrentSession
    get() = attributes.getOrNull(SessionKey) ?: throw IllegalStateException("Sessions feature should be installed to use sessions")

interface CurrentSession {
    fun set(name: String, value: Any?)
    fun get(name: String): Any?
    fun clear(name: String)
    fun findName(type: KClass<*>): String
}

inline fun <reified T> CurrentSession.set(value: T?) = set(findName(T::class), value)
inline fun <reified T> CurrentSession.get(): T? = get(findName(T::class)) as T?
inline fun <reified T> CurrentSession.clear() = clear(findName(T::class))

private data class SessionData(val sessions: Sessions,
                               val providerData: Map<String, SessionProviderData>) : CurrentSession {

    override fun findName(type: KClass<*>): String {
        val entry = providerData.entries.firstOrNull { it.value.provider.type == type } ?:
                throw IllegalArgumentException("Session data for type `$type` was not registered")
        return entry.value.provider.name
    }

    override fun set(name: String, value: Any?) {
        val providerData = providerData[name] ?: throw IllegalStateException("Session data for `$name` was not registered")
        if (value != null)
            providerData.provider.tracker.validate(value)
        providerData.value = value
    }

    override fun get(name: String): Any? {
        val providerData = providerData[name] ?: throw IllegalStateException("Session data for `$name` was not registered")
        return providerData.value
    }

    override fun clear(name: String) {
        val providerData = providerData[name] ?: throw IllegalStateException("Session data for `$name` was not registered")
        providerData.value = null
    }
}

private data class SessionProviderData(var value: Any?, val incoming: Boolean, val provider: SessionProvider)

private val SessionKey = AttributeKey<SessionData>("SessionKey")

