/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.util

public object Hash {
    public fun combine(vararg objects: Any): Int = objects.toList().hashCode()
}
