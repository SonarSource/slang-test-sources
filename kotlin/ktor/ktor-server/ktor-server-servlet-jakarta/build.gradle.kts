description = ""

kotlin.sourceSets {
    jvmMain {
        dependencies {
            api(project(":ktor-server:ktor-server-host-common"))

            compileOnly(libs.jakarta.servlet)
        }
    }

    jvmTest {
        dependencies {
            api(project(":ktor-server:ktor-server-core", configuration = "testOutput"))
            implementation(libs.mockk)
            implementation(libs.jakarta.servlet)
        }
    }
}
