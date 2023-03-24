/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import io.ktor.client.plugins.gson.*
import io.ktor.client.plugins.json.*
import io.ktor.client.plugins.json.tests.*

@Suppress("DEPRECATION")
class DefaultSerializerJsonTest : JsonTest() {
    // Force JsonPlugin to use defaultSerializer()
    override val serializerImpl: JsonSerializer = GsonSerializer()
}
