/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util.pipeline

internal actual fun Throwable.withCause(cause: Throwable?): Throwable = this
