/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

description = ""

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":ktor-server:ktor-server-plugins:ktor-server-conditional-headers"))
            }
        }
    }
}
