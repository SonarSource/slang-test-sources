description = "Shared functionality for client and server"

subprojects {
    kotlin.sourceSets {
        commonMain {
            dependencies {
                api(project(":ktor-http"))
            }
        }
    }
}
