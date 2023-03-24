val logback_version: String by extra

subprojects {
    kotlin {
        sourceSets {
            commonMain {
                dependencies {
                    api(project(":ktor-server:ktor-server-core"))
                }
            }
            commonTest {
                dependencies {
                    api(project(":ktor-server:ktor-server-test-host"))
                }
            }

            jvmTest {
                dependencies {
                    api(project(":ktor-server:ktor-server-core", configuration = "testOutput"))

                    api("ch.qos.logback:logback-classic:$logback_version")
                }
            }
        }
    }
}
