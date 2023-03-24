/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("DEPRECATION")

package io.ktor.client.plugins.json

import io.ktor.util.*

/**
 * Platform default serializer.
 */
@OptIn(InternalAPI::class)
public actual fun defaultSerializer(): JsonSerializer =
    serializersStore.first()

@Suppress("KDocMissingDocumentation")
@InternalAPI
public val serializersStore: MutableList<JsonSerializer> = mutableListOf()
