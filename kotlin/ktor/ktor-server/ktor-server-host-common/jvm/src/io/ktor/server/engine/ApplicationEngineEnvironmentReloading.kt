/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.events.*
import io.ktor.events.EventDefinition
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.internal.*
import io.ktor.util.*
import io.ktor.util.logging.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import java.io.*
import java.net.*
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*
import kotlin.coroutines.*

/**
 * Implements [ApplicationEngineEnvironment] by loading an [Application] from a folder or jar.
 *
 * [watchPaths] specifies substrings to match against class path entries to monitor changes in folder/jar and implements hot reloading
 */
public class ApplicationEngineEnvironmentReloading(
    override val classLoader: ClassLoader,
    override val log: Logger,
    override val config: ApplicationConfig,
    override val connectors: List<EngineConnectorConfig>,
    internal val modules: List<Application.() -> Unit>,
    internal val watchPaths: List<String> = emptyList(),
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    override val rootPath: String = "",
    override val developmentMode: Boolean = true
) : ApplicationEngineEnvironment {

    private val configuredWatchPath get() = config.propertyOrNull("ktor.deployment.watch")?.getList() ?: listOf()
    private val watchPatterns: List<String> = configuredWatchPath + watchPaths

    override val parentCoroutineContext: CoroutineContext = when {
        developmentMode && watchPatterns.isNotEmpty() ->
            parentCoroutineContext + ClassLoaderAwareContinuationInterceptor
        else -> parentCoroutineContext
    }

    public constructor(
        classLoader: ClassLoader,
        log: Logger,
        config: ApplicationConfig,
        connectors: List<EngineConnectorConfig>,
        modules: List<Application.() -> Unit>,
        watchPaths: List<String> = emptyList(),
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
        rootPath: String = "",
    ) : this(
        classLoader, log, config, connectors, modules, watchPaths,
        parentCoroutineContext, rootPath, developmentMode = true
    )

    private var _applicationInstance: Application? = Application(this)
    private var recreateInstance: Boolean = false
    private var _applicationClassLoader: ClassLoader? = null
    private val applicationInstanceLock = ReentrantReadWriteLock()
    private var packageWatchKeys = emptyList<WatchKey>()

    private val configModulesNames: List<String> = run {
        config.propertyOrNull("ktor.application.modules")?.getList() ?: emptyList()
    }

    internal val modulesNames: List<String> = configModulesNames

    private val watcher: WatchService? by lazy {
        try {
            FileSystems.getDefault().newWatchService()
        } catch (_: NoClassDefFoundError) {
            null
        }
    }

    override val monitor: Events = Events()

    override val application: Application
        get() = currentApplication()

    /**
     * Reload application: destroy it first and then create again
     */
    public fun reload() {
        applicationInstanceLock.write {
            destroyApplication()
            val (application, classLoader) = createApplication()
            _applicationInstance = application
            _applicationClassLoader = classLoader
        }
    }

    private fun currentApplication(): Application = applicationInstanceLock.read {
        val currentApplication = _applicationInstance ?: error("ApplicationEngineEnvironment was not started")

        if (!developmentMode) {
            return@read currentApplication
        }

        val changes = packageWatchKeys.flatMap { it.pollEvents() }
        if (changes.isEmpty()) {
            return@read currentApplication
        }

        log.info("Changes in application detected.")

        var count = changes.size
        while (true) {
            Thread.sleep(200)
            val moreChanges = packageWatchKeys.flatMap { it.pollEvents() }
            if (moreChanges.isEmpty()) {
                break
            }

            log.debug("Waiting for more changes.")
            count += moreChanges.size
        }

        log.debug("Changes to $count files caused application restart.")
        changes.take(5).forEach { log.debug("...  ${it.context()}") }

        applicationInstanceLock.write {
            destroyApplication()
            val (application, classLoader) = createApplication()
            _applicationInstance = application
            _applicationClassLoader = classLoader
        }

        return@read _applicationInstance ?: error("ApplicationEngineEnvironment was not started")
    }

    private fun createApplication(): Pair<Application, ClassLoader> {
        val classLoader = createClassLoader()
        val currentThread = Thread.currentThread()
        val oldThreadClassLoader = currentThread.contextClassLoader
        currentThread.contextClassLoader = classLoader

        try {
            return instantiateAndConfigureApplication(classLoader) to classLoader
        } finally {
            currentThread.contextClassLoader = oldThreadClassLoader
        }
    }

    private fun createClassLoader(): ClassLoader {
        val baseClassLoader = classLoader

        if (!developmentMode) {
            log.info("Autoreload is disabled because the development mode is off.")
            return baseClassLoader
        }

        val watchPatterns = watchPatterns
        if (watchPatterns.isEmpty()) {
            log.info("No ktor.deployment.watch patterns specified, automatic reload is not active.")
            return baseClassLoader
        }

        val allUrls = baseClassLoader.allURLs()
        val jre = File(System.getProperty("java.home")).parent
        val debugUrls = allUrls.map { it.file }
        log.debug("Java Home: $jre")
        log.debug("Class Loader: $baseClassLoader: ${debugUrls.filter { !it.toString().startsWith(jre) }}")

        // we shouldn't watch URL for ktor-server classes, even if they match patterns,
        // because otherwise it loads two ApplicationEnvironment (and other) types which do not match
        val coreUrls = listOf(
            ApplicationEnvironment::class.java, // ktor-server
            ApplicationEngineEnvironment::class.java, // ktor-server-host-common
            Pipeline::class.java, // ktor-parsing
            HttpStatusCode::class.java, // ktor-http
            kotlin.jvm.functions.Function1::class.java, // kotlin-stdlib
            Logger::class.java, // slf4j
            ByteReadChannel::class.java,
            Input::class.java, // kotlinx-io
            Attributes::class.java
        ).mapNotNullTo(HashSet()) { it.protectionDomain.codeSource.location }

        val watchUrls = allUrls.filter { url ->
            url !in coreUrls && watchPatterns.any { pattern -> url.toString().contains(pattern) } &&
                !(url.path ?: "").startsWith(jre)
        }

        if (watchUrls.isEmpty()) {
            log.info("No ktor.deployment.watch patterns match classpath entries, automatic reload is not active")
            return baseClassLoader
        }

        watchUrls(watchUrls)
        return OverridingClassLoader(watchUrls, baseClassLoader)
    }

    private fun safeRiseEvent(event: EventDefinition<Application>, application: Application) {
        monitor.raiseCatching(event, application)
    }

    private fun destroyApplication() {
        val currentApplication = _applicationInstance
        val applicationClassLoader = _applicationClassLoader
        _applicationInstance = null
        _applicationClassLoader = null

        if (currentApplication != null) {
            safeRiseEvent(ApplicationStopping, currentApplication)
            try {
                currentApplication.dispose()
                (applicationClassLoader as? OverridingClassLoader)?.close()
            } catch (e: Throwable) {
                log.error("Failed to destroy application instance.", e)
            }

            safeRiseEvent(ApplicationStopped, currentApplication)
        }
        packageWatchKeys.forEach { it.cancel() }
        packageWatchKeys = mutableListOf()
    }

    private fun watchUrls(urls: List<URL>) {
        val paths = HashSet<Path>()
        for (url in urls) {
            val path = url.path ?: continue
            val decodedPath = URLDecoder.decode(path, "utf-8")
            val folder = runCatching { File(decodedPath).toPath() }.getOrNull() ?: continue

            if (!Files.exists(folder)) {
                continue
            }

            val visitor = object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    paths.add(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dir = file.parent
                    if (dir != null) {
                        paths.add(dir)
                    }

                    return FileVisitResult.CONTINUE
                }
            }

            if (Files.isDirectory(folder)) {
                Files.walkFileTree(folder, visitor)
            }
        }

        paths.forEach { path ->
            log.debug("Watching $path for changes.")
        }

        val modifiers = get_com_sun_nio_file_SensitivityWatchEventModifier_HIGH()?.let { arrayOf(it) } ?: emptyArray()
        packageWatchKeys = paths.mapNotNull { path ->
            watcher?.let {
                path.register(it, arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), *modifiers)
            }
        }
    }

    override fun start() {
        applicationInstanceLock.write {
            val (application, classLoader) = try {
                createApplication()
            } catch (cause: Throwable) {
                destroyApplication()
                if (watchPatterns.isNotEmpty()) {
                    cleanupWatcher()
                }

                throw cause
            }
            _applicationInstance = application
            _applicationClassLoader = classLoader
        }
    }

    override fun stop() {
        applicationInstanceLock.write {
            destroyApplication()
        }
        if (watchPatterns.isNotEmpty()) {
            cleanupWatcher()
        }
    }

    private fun instantiateAndConfigureApplication(currentClassLoader: ClassLoader): Application {
        val newInstance = if (recreateInstance || _applicationInstance == null) {
            Application(this)
        } else {
            recreateInstance = true
            _applicationInstance!!
        }

        safeRiseEvent(ApplicationStarting, newInstance)

        avoidingDoubleStartup {
            modulesNames.forEach { name ->
                launchModuleByName(name, currentClassLoader, newInstance)
            }

            modules.forEach { module ->
                val name = module.methodName()

                try {
                    launchModuleByName(name, currentClassLoader, newInstance)
                } catch (_: ReloadingException) {
                    module(newInstance)
                }
            }
        }

        safeRiseEvent(ApplicationStarted, newInstance)
        return newInstance
    }

    private fun launchModuleByName(name: String, currentClassLoader: ClassLoader, newInstance: Application) {
        avoidingDoubleStartupFor(name) {
            executeModuleFunction(currentClassLoader, name, newInstance)
        }
    }

    private fun avoidingDoubleStartup(block: () -> Unit) {
        try {
            block()
        } finally {
            currentStartupModules.get()?.let {
                if (it.isEmpty()) {
                    currentStartupModules.remove()
                }
            }
        }
    }

    private fun avoidingDoubleStartupFor(fqName: String, block: () -> Unit) {
        val modules = currentStartupModules.getOrSet { ArrayList(1) }
        check(!modules.contains(fqName)) {
            "Module startup is already in progress for function $fqName (recursive module startup from module main?)"
        }

        modules.add(fqName)
        try {
            block()
        } finally {
            modules.remove(fqName)
        }
    }

    private fun cleanupWatcher() {
        try {
            watcher?.close()
        } catch (_: NoClassDefFoundError) {
        }
    }

    public companion object
}

private object ClassLoaderAwareContinuationInterceptor : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*> =
        object : CoroutineContext.Key<ClassLoaderAwareContinuationInterceptor> {}

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        val classLoader = Thread.currentThread().contextClassLoader
        return object : Continuation<T> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<T>) {
                Thread.currentThread().contextClassLoader = classLoader
                continuation.resumeWith(result)
            }
        }
    }
}
