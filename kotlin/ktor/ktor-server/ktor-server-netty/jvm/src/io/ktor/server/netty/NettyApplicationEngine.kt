/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.network.*
import io.ktor.util.pipeline.*
import io.netty.bootstrap.*
import io.netty.channel.*
import io.netty.channel.epoll.*
import io.netty.channel.kqueue.*
import io.netty.channel.nio.*
import io.netty.channel.socket.*
import io.netty.channel.socket.nio.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.lang.reflect.*
import java.net.*
import java.util.concurrent.*
import kotlin.reflect.*

/**
 * [ApplicationEngine] implementation for running in a standalone Netty
 */
public class NettyApplicationEngine(
    environment: ApplicationEngineEnvironment,
    configure: Configuration.() -> Unit = {}
) : BaseApplicationEngine(environment) {

    /**
     * Configuration for the [NettyApplicationEngine]
     */
    public class Configuration : BaseApplicationEngine.Configuration() {
        /**
         * Size of the queue to store [ApplicationCall] instances that cannot be immediately processed
         */
        public var requestQueueLimit: Int = 16

        /**
         * Number of concurrently running requests from the same http pipeline
         */
        public var runningLimit: Int = 32

        /**
         * Do not create separate call event group and reuse worker group for processing calls
         */
        public var shareWorkGroup: Boolean = false

        /**
         * User-provided function to configure Netty's [ServerBootstrap]
         */
        public var configureBootstrap: ServerBootstrap.() -> Unit = {}

        /**
         * Timeout in seconds for sending responses to client
         */
        public var responseWriteTimeoutSeconds: Int = 10

        /**
         * Timeout in seconds for reading requests from client, "0" is infinite.
         */
        public var requestReadTimeoutSeconds: Int = 0

        /**
         * If set to `true`, enables TCP keep alive for connections so all
         * dead client connections will be discarded.
         * The timeout period is configured by the system so configure
         * your host accordingly.
         */
        public var tcpKeepAlive: Boolean = false

        /**
         * The url limit including query parameters
         */
        public var maxInitialLineLength: Int = HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH

        /**
         * The maximum length of all headers.
         * If the sum of the length of each header exceeds this value, a TooLongFrameException will be raised.
         */
        public var maxHeaderSize: Int = HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE

        /**
         * The maximum length of the content or each chunk
         */
        public var maxChunkSize: Int = HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE

        /**
         * User-provided function to configure Netty's [HttpServerCodec]
         */
        public var httpServerCodec: () -> HttpServerCodec = this::defaultHttpServerCodec

        /**
         * User-provided function to configure Netty's [ChannelPipeline]
         */
        public var channelPipelineConfig: ChannelPipeline.() -> Unit = {}

        /**
         * Default function to configure Netty's
         */
        private fun defaultHttpServerCodec() = HttpServerCodec(
            maxInitialLineLength,
            maxHeaderSize,
            maxChunkSize
        )
    }

    private val configuration = Configuration().apply(configure)

    /**
     * [EventLoopGroupProxy] for accepting connections
     */
    private val connectionEventGroup: EventLoopGroup by lazy {
        customBootstrap.config().group() ?: EventLoopGroupProxy.create(configuration.connectionGroupSize)
    }

    /**
     * [EventLoopGroupProxy] for processing incoming requests and doing engine's internal work
     */
    private val workerEventGroup: EventLoopGroup by lazy {
        customBootstrap.config().childGroup()?.let {
            return@lazy it
        }
        if (configuration.shareWorkGroup) {
            EventLoopGroupProxy.create(configuration.workerGroupSize + configuration.callGroupSize)
        } else {
            EventLoopGroupProxy.create(configuration.workerGroupSize)
        }
    }

    private val customBootstrap: ServerBootstrap by lazy {
        ServerBootstrap().apply(configuration.configureBootstrap)
    }

    /**
     * [EventLoopGroupProxy] for processing [ApplicationCall] instances
     */
    private val callEventGroup: EventLoopGroup by lazy {
        if (configuration.shareWorkGroup) {
            workerEventGroup
        } else {
            EventLoopGroupProxy.create(configuration.callGroupSize)
        }
    }

    private val nettyDispatcher: CoroutineDispatcher by lazy {
        NettyDispatcher
    }

    private val workerDispatcher by lazy {
        workerEventGroup.asCoroutineDispatcher()
    }

    private var cancellationDeferred: CompletableJob? = null

    private var channels: List<Channel>? = null
    internal val bootstraps: List<ServerBootstrap> by lazy {
        environment.connectors.map(::createBootstrap)
    }

    private val userContext = environment.parentCoroutineContext +
        nettyDispatcher +
        NettyApplicationCallHandler.CallHandlerCoroutineName +
        DefaultUncaughtExceptionHandler(environment.log)

    private fun createBootstrap(connector: EngineConnectorConfig): ServerBootstrap {
        return customBootstrap.clone().apply {
            if (config().group() == null && config().childGroup() == null) {
                group(connectionEventGroup, workerEventGroup)
            }

            if (config().channelFactory() == null) {
                channel(getChannelClass().java)
            }

            childHandler(
                NettyChannelInitializer(
                    pipeline,
                    environment,
                    callEventGroup,
                    workerDispatcher,
                    userContext,
                    connector,
                    configuration.requestQueueLimit,
                    configuration.runningLimit,
                    configuration.responseWriteTimeoutSeconds,
                    configuration.requestReadTimeoutSeconds,
                    configuration.httpServerCodec,
                    configuration.channelPipelineConfig
                )
            )
            if (configuration.tcpKeepAlive) {
                childOption(ChannelOption.SO_KEEPALIVE, true)
            }
        }
    }

    init {
        val afterCall = PipelinePhase("After")
        pipeline.insertPhaseAfter(EnginePipeline.Call, afterCall)
        pipeline.intercept(afterCall) {
            (call as? NettyApplicationCall)?.finish()
        }
    }

    override fun start(wait: Boolean): NettyApplicationEngine {
        addShutdownHook {
            stop(configuration.shutdownGracePeriod, configuration.shutdownTimeout)
        }

        environment.start()

        try {
            channels = bootstraps.zip(environment.connectors)
                .map { it.first.bind(it.second.host, it.second.port) }
                .map { it.sync().channel() }
            val connectors = channels!!.zip(environment.connectors)
                .map { it.second.withPort(it.first.localAddress().port) }
            resolvedConnectors.complete(connectors)
        } catch (cause: BindException) {
            terminate()
            throw cause
        }

        environment.monitor.raiseCatching(ServerReady, environment, environment.log)

        cancellationDeferred =
            stopServerOnCancellation(configuration.shutdownGracePeriod, configuration.shutdownTimeout)

        if (wait) {
            channels?.map { it.closeFuture() }?.forEach { it.sync() }
            stop(configuration.shutdownGracePeriod, configuration.shutdownTimeout)
        }
        return this
    }

    private fun terminate() {
        connectionEventGroup.shutdownGracefully().sync()
        workerEventGroup.shutdownGracefully().sync()
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        cancellationDeferred?.complete()
        environment.monitor.raise(ApplicationStopPreparing, environment)
        val channelFutures = channels?.mapNotNull { if (it.isOpen) it.close() else null }.orEmpty()

        try {
            val shutdownConnections =
                connectionEventGroup.shutdownGracefully(gracePeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS)
            shutdownConnections.await()

            val shutdownWorkers =
                workerEventGroup.shutdownGracefully(gracePeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS)
            if (configuration.shareWorkGroup) {
                shutdownWorkers.await()
            } else {
                val shutdownCall =
                    callEventGroup.shutdownGracefully(gracePeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS)
                shutdownWorkers.await()
                shutdownCall.await()
            }

            environment.stop()
        } finally {
            channelFutures.forEach { it.sync() }
        }
    }

    override fun toString(): String {
        return "Netty($environment)"
    }
}

/**
 * Transparently allows for the creation of [EventLoopGroup]'s utilising the optimal implementation for
 * a given operating system, subject to availability, or falling back to [NioEventLoopGroup] if none is available.
 */
public class EventLoopGroupProxy(
    public val channel: KClass<out ServerSocketChannel>,
    group: EventLoopGroup
) : EventLoopGroup by group {

    public companion object {

        public fun create(parallelism: Int): EventLoopGroupProxy {
            val defaultFactory = DefaultThreadFactory(EventLoopGroupProxy::class.java, true)

            val factory = ThreadFactory { runnable ->
                defaultFactory.newThread {
                    markParkingProhibited()
                    runnable.run()
                }
            }

            val channelClass = getChannelClass()

            return when {
                KQueue.isAvailable() -> EventLoopGroupProxy(channelClass, KQueueEventLoopGroup(parallelism, factory))
                Epoll.isAvailable() -> EventLoopGroupProxy(channelClass, EpollEventLoopGroup(parallelism, factory))
                else -> EventLoopGroupProxy(channelClass, NioEventLoopGroup(parallelism, factory))
            }
        }

        private val prohibitParkingFunction: Method? by lazy {
            try {
                Class.forName("io.ktor.utils.io.jvm.javaio.PollersKt")
                    .getMethod("prohibitParking")
            } catch (cause: Throwable) {
                null
            }
        }

        private fun markParkingProhibited() {
            try {
                prohibitParkingFunction?.invoke(null)
            } catch (_: Throwable) {
            }
        }
    }
}

private fun getChannelClass(): KClass<out ServerSocketChannel> = when {
    KQueue.isAvailable() -> KQueueServerSocketChannel::class
    Epoll.isAvailable() -> EpollServerSocketChannel::class
    else -> NioServerSocketChannel::class
}
