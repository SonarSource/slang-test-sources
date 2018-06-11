package net.corda.client.rpc

import net.corda.client.rpc.internal.CordaRPCClientConfigurationImpl
import net.corda.client.rpc.internal.RPCClient
import net.corda.client.rpc.internal.serialization.amqp.AMQPClientSerializationScheme
import net.corda.core.context.Actor
import net.corda.core.context.Trace
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.ArtemisTcpTransport.Companion.rpcConnectorTcpTransport
import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.serialization.internal.AMQP_RPC_CLIENT_CONTEXT
import java.time.Duration

/**
 * This class is essentially just a wrapper for an RPCConnection<CordaRPCOps> and can be treated identically.
 *
 * @see RPCConnection
 */
class CordaRPCConnection internal constructor(connection: RPCConnection<CordaRPCOps>) : RPCConnection<CordaRPCOps> by connection

/**
 * Can be used to configure the RPC client connection.
 */
interface CordaRPCClientConfiguration {

    /** The minimum protocol version required from the server */
    val minimumServerProtocolVersion: Int get() = default().minimumServerProtocolVersion
    /**
     * If set to true the client will track RPC call sites. If an error occurs subsequently during the RPC or in a
     * returned Observable stream the stack trace of the originating RPC will be shown as well. Note that
     * constructing call stacks is a moderately expensive operation.
     */
    val trackRpcCallSites: Boolean get() = default().trackRpcCallSites
    /**
     * The interval of unused observable reaping. Leaked Observables (unused ones) are detected using weak references
     * and are cleaned up in batches in this interval. If set too large it will waste server side resources for this
     * duration. If set too low it wastes client side cycles.
     */
    val reapInterval: Duration get() = default().reapInterval
    /** The number of threads to use for observations (for executing [Observable.onNext]) */
    val observationExecutorPoolSize: Int get() = default().observationExecutorPoolSize
    /**
     * Determines the concurrency level of the Observable Cache. This is exposed because it implicitly determines
     * the limit on the number of leaked observables reaped because of garbage collection per reaping.
     * See the implementation of [com.google.common.cache.LocalCache] for details.
     */
    val cacheConcurrencyLevel: Int get() = default().cacheConcurrencyLevel
    /** The retry interval of artemis connections in milliseconds */
    val connectionRetryInterval: Duration get() = default().connectionRetryInterval
    /** The retry interval multiplier for exponential backoff */
    val connectionRetryIntervalMultiplier: Double get() = default().connectionRetryIntervalMultiplier
    /** Maximum retry interval */
    val connectionMaxRetryInterval: Duration get() = default().connectionMaxRetryInterval
    /** Maximum reconnect attempts on failover */
    val maxReconnectAttempts: Int get() = default().maxReconnectAttempts
    /** Maximum file size */
    val maxFileSize: Int get() = default().maxFileSize
    /** The cache expiry of a deduplication watermark per client. */
    val deduplicationCacheExpiry: Duration get() = default().deduplicationCacheExpiry

    companion object {
        fun default(): CordaRPCClientConfiguration = CordaRPCClientConfigurationImpl.default
    }
}

/**
 * An RPC client connects to the specified server and allows you to make calls to the server that perform various
 * useful tasks. Please see the Client RPC section of docs.corda.net to learn more about how this API works. A brief
 * description is provided here.
 *
 * Calling [start] returns an [RPCConnection] containing a proxy that lets you invoke RPCs on the server. Calls on
 * it block, and if the server throws an exception then it will be rethrown on the client. Proxies are thread safe and
 * may be used to invoke multiple RPCs in parallel.
 *
 * RPC sends and receives are logged on the net.corda.rpc logger.
 *
 * The [CordaRPCOps] defines what client RPCs are available. If an RPC returns an [rx.Observable] anywhere in the object
 * graph returned then the server-side observable is transparently forwarded to the client side here.
 * *You are expected to use it*. The server will begin sending messages immediately that will be buffered on the
 * client, you are expected to drain by subscribing to the returned observer. You can opt-out of this by simply
 * calling the [net.corda.client.rpc.notUsed] method on it.
 *
 * You don't have to explicitly close the observable if you actually subscribe to it: it will close itself and free up
 * the server-side resources either when the client or JVM itself is shutdown, or when there are no more subscribers to
 * it. Once all the subscribers to a returned observable are unsubscribed or the observable completes successfully or
 * with an error, the observable is closed and you can't then re-subscribe again: you'll have to re-request a fresh
 * observable with another RPC.
 *
 * In case of loss of connection to the server, the client will try to reconnect using the settings provided via
 * [CordaRPCClientConfiguration]. While attempting failover, current and future RPC calls will throw
 * [RPCException] and previously returned observables will call onError().
 *
 * If the client was created using a list of hosts, automatic failover will occur (the servers have to be started in
 * HA mode).
 *
 * @param hostAndPort The network address to connect to.
 * @param configuration An optional configuration used to tweak client behaviour.
 * @param sslConfiguration An optional [ClientRpcSslOptions] used to enable secure communication with the server.
 * @param haAddressPool A list of [NetworkHostAndPort] representing the addresses of servers in HA mode.
 * The client will attempt to connect to a live server by trying each address in the list. If the servers are not in
 * HA mode, the client will round-robin from the beginning of the list and try all servers.
 */
class CordaRPCClient private constructor(
        private val hostAndPort: NetworkHostAndPort,
        private val configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default(),
        private val sslConfiguration: ClientRpcSslOptions? = null,
        private val nodeSslConfiguration: SSLConfiguration? = null,
        private val classLoader: ClassLoader? = null,
        private val haAddressPool: List<NetworkHostAndPort> = emptyList(),
        private val internalConnection: Boolean = false
) {
    @JvmOverloads
    constructor(hostAndPort: NetworkHostAndPort,
                configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default())
            : this(hostAndPort, configuration, null)

    /**
     * @param haAddressPool A list of [NetworkHostAndPort] representing the addresses of servers in HA mode.
     * The client will attempt to connect to a live server by trying each address in the list. If the servers are not in
     * HA mode, the client will round-robin from the beginning of the list and try all servers.
     * @param configuration An optional configuration used to tweak client behaviour.
     */
    @JvmOverloads
    constructor(haAddressPool: List<NetworkHostAndPort>, configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default()) : this(haAddressPool.first(), configuration, null, null, null, haAddressPool)

    companion object {
        fun createWithSsl(
                hostAndPort: NetworkHostAndPort,
                sslConfiguration: ClientRpcSslOptions,
                configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default()
        ): CordaRPCClient {
            return CordaRPCClient(hostAndPort, configuration, sslConfiguration)
        }

        fun createWithSsl(
                haAddressPool: List<NetworkHostAndPort>,
                sslConfiguration: ClientRpcSslOptions,
                configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default()
        ): CordaRPCClient {
            return CordaRPCClient(haAddressPool.first(), configuration, sslConfiguration, haAddressPool = haAddressPool)
        }

        internal fun createWithSslAndClassLoader(
                hostAndPort: NetworkHostAndPort,
                configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default(),
                sslConfiguration: ClientRpcSslOptions? = null,
                classLoader: ClassLoader? = null
        ): CordaRPCClient {
            return CordaRPCClient(hostAndPort, configuration, sslConfiguration, null, classLoader)
        }

        internal fun createWithInternalSslAndClassLoader(
                hostAndPort: NetworkHostAndPort,
                configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default(),
                sslConfiguration: SSLConfiguration?,
                classLoader: ClassLoader? = null
        ): CordaRPCClient {
            return CordaRPCClient(hostAndPort, configuration, null, sslConfiguration, classLoader, internalConnection = true)
        }
    }

    init {
        try {
            effectiveSerializationEnv
        } catch (e: IllegalStateException) {
            try {
                AMQPClientSerializationScheme.initialiseSerialization()
            } catch (e: IllegalStateException) {
                // Race e.g. two of these constructed in parallel, ignore.
            }
        }
    }

    private fun getRpcClient(): RPCClient<CordaRPCOps> {
        return when {
        // Node->RPC broker, mutually authenticated SSL. This is used when connecting the integrated shell
            internalConnection == true -> RPCClient(hostAndPort, nodeSslConfiguration!!)

        // Client->RPC broker
            haAddressPool.isEmpty() -> RPCClient(
                    rpcConnectorTcpTransport(hostAndPort, config = sslConfiguration),
                    configuration,
                    if (classLoader != null) AMQP_RPC_CLIENT_CONTEXT.withClassLoader(classLoader) else AMQP_RPC_CLIENT_CONTEXT)
            else -> {
                RPCClient(haAddressPool,
                        sslConfiguration,
                        configuration,
                        if (classLoader != null) AMQP_RPC_CLIENT_CONTEXT.withClassLoader(classLoader) else AMQP_RPC_CLIENT_CONTEXT)
            }
        }
    }

    /**
     * Logs in to the target server and returns an active connection. The returned connection is a [java.io.Closeable]
     * and can be used with a try-with-resources statement. If you don't use that, you should use the
     * [RPCConnection.notifyServerAndClose] or [RPCConnection.forceClose] methods to dispose of the connection object
     * when done.
     *
     * @param username The username to authenticate with.
     * @param password The password to authenticate with.
     * @throws RPCException if the server version is too low or if the server isn't reachable within a reasonable timeout.
     */
    fun start(username: String, password: String): CordaRPCConnection {
        return start(username, password, null, null)
    }

    /**
     * Logs in to the target server and returns an active connection. The returned connection is a [java.io.Closeable]
     * and can be used with a try-with-resources statement. If you don't use that, you should use the
     * [RPCConnection.notifyServerAndClose] or [RPCConnection.forceClose] methods to dispose of the connection object
     * when done.
     *
     * @param username The username to authenticate with.
     * @param password The password to authenticate with.
     * @param externalTrace external [Trace] for correlation.
     * @throws RPCException if the server version is too low or if the server isn't reachable within a reasonable timeout.
     */
    fun start(username: String, password: String, externalTrace: Trace?, impersonatedActor: Actor?): CordaRPCConnection {
        return CordaRPCConnection(getRpcClient().start(CordaRPCOps::class.java, username, password, externalTrace, impersonatedActor))
    }

    /**
     * A helper for Kotlin users that simply closes the connection after the block has executed. Be careful not to
     * over-use this, as setting up and closing connections takes time.
     */
    inline fun <A> use(username: String, password: String, block: (CordaRPCConnection) -> A): A {
        return start(username, password).use(block)
    }
}