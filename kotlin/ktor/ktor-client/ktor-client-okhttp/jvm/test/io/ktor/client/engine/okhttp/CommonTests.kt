/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.client.tests.*

class OkHttpHttpClientTest : HttpClientTest(OkHttp)

class OkHttpSslOverProxyTest : SslOverProxyTest<OkHttpConfig>(OkHttp) {

    override fun OkHttpConfig.disableCertificatePinning() {
        config {
            sslSocketFactory(unsafeSslContext.socketFactory, trustAllCertificates[0])
        }
    }
}
