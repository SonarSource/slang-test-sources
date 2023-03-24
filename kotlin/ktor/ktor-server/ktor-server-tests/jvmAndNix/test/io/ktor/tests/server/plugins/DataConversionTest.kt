/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.dataconversion.*
import io.ktor.server.testing.*
import io.ktor.util.reflect.*
import kotlin.test.*

@Suppress("DEPRECATION")
class DataConversionTest {
    @Test
    fun testDefaultConversion() = withTestApplication {
        val id = application.conversionService.fromValues(listOf("1"), typeInfo<Int>())
        assertEquals(1, id)
    }

    private val expectedList = listOf(1, 2)

    @Test
    fun testDefaultConversionList() = withTestApplication {
        val type = typeInfo<List<Int>>()
        val id = application.conversionService.fromValues(listOf("1", "2"), type)
        assertEquals(expectedList, id)
    }

    data class EntityID(val typeId: Int, val entityId: Int)

    @Test
    fun testInstalledConversion() = withTestApplication {
        application.install(DataConversion) {
            convert<EntityID> {
                decode { values ->
                    val (typeId, entityId) = values.single().split('-').map { it.toInt() }
                    EntityID(typeId, entityId)
                }

                encode { value -> listOf("${value.typeId}-${value.entityId}") }
            }
        }

        val id = application.conversionService.fromValues(listOf("42-999"), typeInfo<EntityID>())
        assertEquals(EntityID(42, 999), id)
    }
}
