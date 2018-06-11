package io.ktor.response

import io.ktor.content.*
import io.ktor.http.*

interface ResponsePushBuilder {
    val url: URLBuilder
    val headers: HeadersBuilder
    var method: HttpMethod
    val versions: MutableList<Version>
}