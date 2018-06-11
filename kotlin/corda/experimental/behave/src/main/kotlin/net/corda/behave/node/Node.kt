package net.corda.behave.node

import net.corda.behave.database.DatabaseConnection
import net.corda.behave.database.DatabaseType
import net.corda.behave.file.LogSource
import net.corda.behave.file.currentDirectory
import net.corda.behave.file.stagingRoot
import net.corda.behave.monitoring.PatternWatch
import net.corda.behave.node.configuration.*
import net.corda.behave.process.JarCommand
import net.corda.behave.service.Service
import net.corda.behave.service.ServiceSettings
import net.corda.behave.ssh.MonitoringSSHClient
import net.corda.behave.ssh.SSHClient
import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.seconds
import org.apache.commons.io.FileUtils
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * Corda node.
 */
class Node(
        val config: Configuration,
        private val rootDirectory: Path = currentDirectory,
        private val settings: ServiceSettings = ServiceSettings()
) {

    private val log = loggerFor<Node>()

    private val runtimeDirectory = rootDirectory / config.name

    private val logDirectory = runtimeDirectory / "logs"

    private val command = JarCommand(
            config.distribution.cordaJar,
            arrayOf("--config", "node.conf"),
            runtimeDirectory,
            settings.timeout,
            enableRemoteDebugging = false
    )

    private val isAliveLatch = PatternWatch(command.output, "Node for \".*\" started up and registered")

    private var isConfigured = false

    private val serviceDependencies = mutableListOf<Service>()

    private var isStarted = false

    private var haveDependenciesStarted = false

    private var haveDependenciesStopped = false

    fun describe(): String {
        val network = config.nodeInterface
        val database = config.database
        return """
            |Node Information: ${config.name}
            | - P2P: ${network.host}:${network.p2pPort}
            | - RPC: ${network.host}:${network.rpcPort}
            | - SSH: ${network.host}:${network.sshPort}
            | - DB:  ${network.host}:${database.port} (${database.type})
            |""".trimMargin()
    }

    fun configure() {
        if (isConfigured) { return }
        isConfigured = true
        log.info("Configuring {} ...", this)
        serviceDependencies.addAll(config.database.type.dependencies(config))
        config.distribution.ensureAvailable()
        config.writeToFile(rootDirectory / "${config.name}_node.conf")
        installApps()
    }

    fun start(): Boolean {
        if (!startDependencies()) {
            return false
        }
        log.info("Starting {} ...", this)
        return try {
            command.start()
            isStarted = true
            true
        } catch (e: Exception) {
            log.warn("Failed to start {}", this)
            e.printStackTrace()
            false
        }
    }

    fun waitUntilRunning(waitDuration: Duration? = null): Boolean {
        val ok = isAliveLatch.await(waitDuration ?: settings.timeout)
        if (!ok) {
            log.warn("{} did not start up as expected within the given time frame", this)
        } else {
            log.info("{} is running and ready for incoming connections", this)
        }
        return ok
    }

    fun shutDown(): Boolean {
        return try {
            if (isStarted) {
                log.info("Shutting down {} ...", this)
                command.kill()
            }
            stopDependencies()
            true
        } catch (e: Exception) {
            log.warn("Failed to shut down {} cleanly", this)
            e.printStackTrace()
            false
        }
    }

    val nodeInfoGenerationOutput: LogSource by lazy {
        LogSource(logDirectory, "node-info-gen.log")
    }

    val logOutput: LogSource by lazy {
        val hostname = InetAddress.getLocalHost().hostName
        LogSource(logDirectory, "node-$hostname.*.log")
    }

    val database: DatabaseConnection by lazy {
        DatabaseConnection(config.database, config.databaseType.settings.template)
    }

    fun ssh(
            exitLatch: CountDownLatch? = null,
            clientLogic: (MonitoringSSHClient) -> Unit
    ) {
        Thread(Runnable {
            val network = config.nodeInterface
            val user = config.users.first()
            val client = SSHClient.connect(network.sshPort, user.password, username = user.username)
            MonitoringSSHClient(client).use {
                log.info("Connected to {} over SSH", this)
                clientLogic(it)
                log.info("Disconnecting from {} ...", this)
                it.writeLine("bye")
                exitLatch?.countDown()
            }
        }).start()
    }

    fun <T> rpc(action: (CordaRPCOps) -> T): T {
        var result: T? = null
        val user = config.users.first()
        val address = config.nodeInterface
        val targetHost = NetworkHostAndPort(address.host, address.rpcPort)
        val config = object : CordaRPCClientConfiguration {
            override val connectionMaxRetryInterval = 10.seconds
        }
        log.info("Establishing RPC connection to ${targetHost.host} on port ${targetHost.port} ...")
        CordaRPCClient(targetHost, config).use(user.username, user.password) {
            log.info("RPC connection to ${targetHost.host}:${targetHost.port} established")
            val client = it.proxy
            result = action(client)
        }
        return result ?: error("Failed to run RPC action")
    }

    override fun toString(): String {
        return "Node(name = ${config.name}, version = ${config.distribution.version})"
    }

    fun startDependencies(): Boolean {
        if (haveDependenciesStarted) { return true }
        haveDependenciesStarted = true

        if (serviceDependencies.isEmpty()) { return true }

        log.info("Starting dependencies for {} ...", this)
        val latch = CountDownLatch(serviceDependencies.size)
        var failed = false
        serviceDependencies.parallelStream().forEach {
            val wasStarted = it.start()
            latch.countDown()
            if (!wasStarted) {
                failed = true
            }
        }
        latch.await()
        return if (!failed) {
            log.info("Dependencies started for {}", this)
            true
        } else {
            log.warn("Failed to start one or more dependencies for {}", this)
            false
        }
    }

    private fun stopDependencies() {
        if (haveDependenciesStopped) { return }
        haveDependenciesStopped = true

        if (serviceDependencies.isEmpty()) { return }

        log.info("Stopping dependencies for {} ...", this)
        val latch = CountDownLatch(serviceDependencies.size)
        serviceDependencies.parallelStream().forEach {
            it.stop()
            latch.countDown()
        }
        latch.await()
        log.info("Dependencies stopped for {}", this)
    }

    private fun installApps() {
        val version = config.distribution.version
        val appDirectory = stagingRoot / "corda" / version / "apps"
        if (appDirectory.exists()) {
            val targetAppDirectory = runtimeDirectory / "cordapps"
            FileUtils.copyDirectory(appDirectory.toFile(), targetAppDirectory.toFile())
        }
    }

    class Builder {

        var name: String? = null
            private set

        private var distribution = Distribution.MASTER

        private var databaseType = DatabaseType.H2

        private var notaryType = NotaryType.NONE

        private val issuableCurrencies = mutableListOf<String>()

        private var location: String = "London"

        private var country: String = "GB"

        private val apps = mutableListOf<String>()

        private var includeFinance = false

        private var directory: Path? = null

        private var timeout = Duration.ofSeconds(60)

        fun withName(newName: String): Builder {
            name = newName
            return this
        }

        fun withDistribution(newDistribution: Distribution): Builder {
            distribution = newDistribution
            return this
        }

        fun withDatabaseType(newDatabaseType: DatabaseType): Builder {
            databaseType = newDatabaseType
            return this
        }

        fun withNotaryType(newNotaryType: NotaryType): Builder {
            notaryType = newNotaryType
            return this
        }

        fun withIssuableCurrencies(vararg currencies: String): Builder {
            issuableCurrencies.addAll(currencies)
            return this
        }

        fun withIssuableCurrencies(currencies: List<String>): Builder {
            issuableCurrencies.addAll(currencies)
            return this
        }

        fun withLocation(location: String, country: String): Builder {
            this.location = location
            this.country = country
            return this
        }

        fun withFinanceApp(): Builder {
            includeFinance = true
            return this
        }

        fun withApp(app: String): Builder {
            apps.add(app)
            return this
        }

        fun withDirectory(newDirectory: Path): Builder {
            directory = newDirectory
            return this
        }

        fun withTimeout(newTimeout: Duration): Builder {
            timeout = newTimeout
            return this
        }

        fun build(): Node {
            val name = name ?: error("Node name not set")
            val directory = directory ?: error("Runtime directory not set")
            return Node(
                    Configuration(
                            name,
                            distribution,
                            databaseType,
                            location = location,
                            country = country,
                            notary = NotaryConfiguration(notaryType),
                            cordapps = CordappConfiguration(
                                    apps = apps,
                                    includeFinance = includeFinance
                            ),
                            configElements = *arrayOf(
                                    NotaryConfiguration(notaryType),
                                    CurrencyConfiguration(issuableCurrencies)
                            )
                    ),
                    directory,
                    ServiceSettings(timeout)
            )
        }

        private fun <T> error(message: String): T {
            throw IllegalArgumentException(message)
        }
    }

    companion object {

        fun new() = Builder()

    }

}