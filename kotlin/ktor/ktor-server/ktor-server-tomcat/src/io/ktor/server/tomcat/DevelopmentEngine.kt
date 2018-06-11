package io.ktor.server.tomcat

import io.ktor.server.engine.*

object DevelopmentEngine {
    /**
     * Main function for starting DevelopmentEngine with Tomcat
     * Creates an embedded Tomcat application with an environment built from command line arguments.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val applicationEnvironment = commandLineEnvironment(args)
        TomcatApplicationEngine(applicationEnvironment, {}).start(true)
    }
}
