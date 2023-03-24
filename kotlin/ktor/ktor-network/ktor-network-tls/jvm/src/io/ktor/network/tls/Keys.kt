/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.tls

import javax.crypto.*
import javax.crypto.spec.*

private val MASTER_SECRET_LABEL = "master secret".toByteArray()
private val KEY_EXPANSION_LABEL = "key expansion".toByteArray()

internal val CLIENT_FINISHED_LABEL = "client finished".toByteArray()
internal val SERVER_FINISHED_LABEL = "server finished".toByteArray()

internal fun ByteArray.clientMacKey(suite: CipherSuite): SecretKeySpec = SecretKeySpec(
    this,
    0,
    suite.macStrengthInBytes,
    suite.hash.macName
)

internal fun ByteArray.serverMacKey(suite: CipherSuite): SecretKeySpec = SecretKeySpec(
    this,
    suite.macStrengthInBytes,
    suite.macStrengthInBytes,
    suite.hash.macName
)

internal fun ByteArray.serverKey(suite: CipherSuite): SecretKeySpec = SecretKeySpec(
    this,
    2 * suite.macStrengthInBytes + suite.keyStrengthInBytes,
    suite.keyStrengthInBytes,
    suite.jdkCipherName.substringBefore("/")
)

internal fun ByteArray.clientKey(suite: CipherSuite): SecretKeySpec = SecretKeySpec(
    this,
    2 * suite.macStrengthInBytes,
    suite.keyStrengthInBytes,
    suite.jdkCipherName.substringBefore("/")
)

internal fun ByteArray.clientIV(suite: CipherSuite): ByteArray = copyOfRange(
    2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes,
    2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + suite.fixedIvLength
)

internal fun ByteArray.serverIV(suite: CipherSuite): ByteArray = copyOfRange(
    2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + suite.fixedIvLength,
    2 * suite.macStrengthInBytes + 2 * suite.keyStrengthInBytes + 2 * suite.fixedIvLength
)

internal fun keyMaterial(
    masterSecret: SecretKey,
    seed: ByteArray,
    keySize: Int,
    macSize: Int,
    ivSize: Int
): ByteArray {
    val materialSize = 2 * macSize + 2 * keySize + 2 * ivSize
    return PRF(masterSecret, KEY_EXPANSION_LABEL, seed, materialSize)
}

internal fun masterSecret(
    preMasterSecret: SecretKey,
    clientRandom: ByteArray,
    serverRandom: ByteArray
): SecretKeySpec = SecretKeySpec(
    PRF(preMasterSecret, MASTER_SECRET_LABEL, clientRandom + serverRandom, 48),
    preMasterSecret.algorithm
)
