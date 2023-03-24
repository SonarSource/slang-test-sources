/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.plugins.auth

import io.ktor.client.plugins.auth.providers.*
import io.ktor.http.auth.*
import kotlin.test.*

class BasicProviderTest {
    @Test
    fun testUnicodeCredentials() {
        assertEquals(
            "Basic VW1sYXV0ZcOEw7zDtjphJlNlY3JldCUhMjM=",
            buildAuthString("UmlauteÄüö", "a&Secret%!23")
        )
    }

    @Test
    fun testLoginWithColon() {
        assertEquals(
            "Basic dGVzdDo0NzExOmFwYXNzd29yZA==",
            buildAuthString("test:4711", "apassword")
        )
    }

    @Test
    fun testSimpleCredentials() {
        assertEquals(
            "Basic YWRtaW46YWRtaW4=",
            buildAuthString("admin", "admin")
        )
    }

    @Test
    fun testCapitalizedSchemeIsApplicable() {
        val provider = BasicAuthProvider(credentials = {
            BasicAuthCredentials("user", "password")
        })
        val header = parseAuthorizationHeader("BASIC realm=\"ktor\"")
        assertNotNull(header)

        assertTrue(provider.isApplicable(header), "Provider with capitalized scheme should be applicable")
    }

    private fun buildAuthString(username: String, password: String): String =
        constructBasicAuthValue(BasicAuthCredentials(username, password))
}
