/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.cio

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.network.tls.certificates.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.debug.junit4.*
import org.junit.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import javax.net.ssl.*
import kotlin.concurrent.*
import kotlin.test.*
import kotlin.test.Test

@Suppress("KDocMissingDocumentation", "BlockingMethodInNonBlockingContext")
class ConnectErrorsTest {
    @get:Rule
    val timeout = CoroutinesTimeout.seconds(60)

    private val serverSocket = ServerSocket(0, 1)

    @AfterTest
    fun teardown() {
        serverSocket.close()
    }

    @Test
    fun testConnectAfterConnectionErrors(): Unit = runBlocking {
        val client = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 1
                endpoint.connectTimeout = SOCKET_CONNECT_TIMEOUT
                endpoint.connectAttempts = 3
            }
        }

        client.use {
            serverSocket.close()

            repeat(5) {
                try {
                    client.request("http://localhost:${serverSocket.localPort}/")
                    fail("Shouldn't reach here")
                } catch (_: java.net.ConnectException) {
                }
            }

            ServerSocket(serverSocket.localPort).use { newServer ->
                val thread = thread {
                    try {
                        newServer.accept().use { client ->
                            client.getOutputStream().let { out ->
                                out.write(
                                    "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\nOK".toByteArray()
                                )
                                out.flush()
                            }
                            client.getInputStream().readBytes()
                        }
                    } catch (ignore: SocketException) {
                    }
                }
                withTimeout(10000L) {
                    assertEquals("OK", client.get("http://localhost:${serverSocket.localPort}/").body())
                }
                thread.join()
            }
        }
    }

    @Test
    fun testResponseWithNoLengthChunkedAndConnectionClosedWithHttp10(): Unit = runBlocking {
        val client = HttpClient(CIO)

        client.use {
            serverSocket.close()

            ServerSocket(serverSocket.localPort).use { newServer ->
                val thread = thread {
                    try {
                        newServer.accept().use { client ->
                            client.getOutputStream().let { out ->
                                out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nOK".toByteArray())
                                out.flush()
                                out.close()
                            }
                            client.getInputStream().readBytes()
                        }
                    } catch (ignore: SocketException) {
                    }
                }
                assertEquals("OK", client.get("http://localhost:${serverSocket.localPort}/").body())
                thread.join()
            }
        }
    }

    @Test
    fun testResponseErrorWithNoLengthChunkedAndConnectionClosedWithHttp11(): Unit = runBlocking {
        val client = HttpClient(CIO)

        client.use {
            serverSocket.close()

            ServerSocket(serverSocket.localPort).use { newServer ->
                val thread = thread {
                    try {
                        newServer.accept().use { client ->
                            client.getOutputStream().let { out ->
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nOK".toByteArray())
                                out.flush()
                                out.close()
                            }
                            client.getInputStream().readBytes()
                        }
                    } catch (ignore: SocketException) {
                    }
                }
                assertFails { client.get("http://localhost:${serverSocket.localPort}/") }
                thread.join()
            }
        }
    }

    @Test
    fun testLateServerStart(): Unit = runBlocking {
        val keyStoreFile = File("build/temp.jks")
        val keyStore = generateCertificate(keyStoreFile, algorithm = "SHA256withECDSA", keySizeInBits = 256)

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }

        HttpClient(
            CIO.config {
                maxConnectionsCount = 3

                endpoint {
                    connectTimeout = SOCKET_CONNECT_TIMEOUT
                    connectAttempts = 1
                }

                https {
                    trustManager = trustManagerFactory.trustManagers
                        .first { it is X509TrustManager } as X509TrustManager
                }
            }
        ).use { client ->
            val serverPort = ServerSocket(0).use { it.localPort }
            val server = embeddedServer(
                Netty,
                environment = applicationEngineEnvironment {
                    sslConnector(keyStore, "mykey", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                        port = serverPort
                        keyStorePath = keyStoreFile.absoluteFile
                    }
                    module {
                        routing {
                            get {
                                call.respondText("OK")
                            }
                        }
                    }
                }
            )

            try {
                client.get { url(scheme = "https", path = "/", port = serverPort) }.body<String>()
            } catch (_: java.net.ConnectException) {
            }

            try {
                server.start()

                val message = client.get { url(scheme = "https", path = "/", port = serverPort) }.body<String>()
                assertEquals("OK", message)
            } finally {
                server.stop(0, 0, TimeUnit.MILLISECONDS)
            }
        }
    }

    companion object {
        // It is important to have it greater than 1100 ms
        // because Windows, in average, takes slightly more than a second to produce connection refused error
        // even over loopback.
        // So instead of a connection refused error it may produce a timeout exception
        private const val SOCKET_CONNECT_TIMEOUT: Long = 2000
    }
}
