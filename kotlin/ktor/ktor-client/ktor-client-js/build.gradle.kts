kotlin {
    if (fastTarget()) return@kotlin

    sourceSets {
        jsMain {
            dependencies {
                api(project(":ktor-client:ktor-client-core"))
            }
        }
    }
}
