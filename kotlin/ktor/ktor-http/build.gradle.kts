kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":ktor-utils"))
            }
        }
    }
}
