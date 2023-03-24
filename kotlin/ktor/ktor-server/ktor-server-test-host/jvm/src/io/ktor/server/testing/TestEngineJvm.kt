/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.testing

import io.ktor.server.config.*

internal actual fun DefaultTestConfig(configPath: String?): ApplicationConfig = ApplicationConfig(configPath)
