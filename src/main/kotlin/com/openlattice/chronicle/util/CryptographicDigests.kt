package com.openlattice.chronicle.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun hmacSha256(key: ByteArray, input: String): ByteArray =
    Mac.getInstance(HMAC_SHA256).apply { init(SecretKeySpec(key, HMAC_SHA256)) }
        .doFinal(input.toByteArray(Charsets.UTF_8))

private const val HMAC_SHA256 = "HmacSHA256"
