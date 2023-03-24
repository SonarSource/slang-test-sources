/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

import io.ktor.client.plugins.json.*
import io.ktor.client.plugins.kotlinx.serializer.*
import io.ktor.util.*

@OptIn(ExperimentalStdlibApi::class)
@InternalAPI
@Suppress("unused", "DEPRECATION")
@EagerInitialization
public val initializer: SerializerInitializer = SerializerInitializer

@InternalAPI
public object SerializerInitializer {
    init {
        @Suppress("DEPRECATION")
        serializersStore += KotlinxSerializer()
    }
}
