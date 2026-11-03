package com.openlattice.chronicle.contract

import java.util.Properties

/** Shared by the authentication store and the guarded restore transaction. */
internal object JwtSessionContract {
    private val values = Properties().apply {
        requireNotNull(JwtSessionContract::class.java.getResourceAsStream("/restore-session.properties")) {
            "Missing researcher session contract"
        }.use { load(it) }
    }
    val blocklistMap: String = requireNotNull(values.getProperty("blocklistMap"))
    val revokeAllBeforeKey: String = requireNotNull(values.getProperty("revokeAllBeforeKey"))
}
