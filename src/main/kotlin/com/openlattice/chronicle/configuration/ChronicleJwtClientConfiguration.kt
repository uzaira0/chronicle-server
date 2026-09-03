package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleJwtClientConfiguration(
    val audience: String,
    val base64EncodedSecret: Boolean = false,
    val issuer: String,
    val jwkSetUri: String? = null,
    val secret: String = "",
    val signingAlgorithm: String = "HS256",
    val testingTokenIssuer: Boolean = true,
)
