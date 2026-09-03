package com.openlattice.chronicle.storage.rls

import java.sql.Connection

internal const val RLS_TEST_APP_ROLE = "chronicle_app_test"

/**
 * Applies the production RLS session settings transaction-locally for database
 * policy tests. Keeping this in one helper prevents individual policy suites
 * from drifting back to interpolated session values.
 */
internal fun Connection.applyLocalRlsTestContext(
    isAdmin: Boolean,
    authorizedStudies: String,
) {
    check(!autoCommit) { "SET LOCAL RLS test context requires an active transaction" }
    createStatement().use { statement ->
        statement.execute("SET LOCAL ROLE $RLS_TEST_APP_ROLE")
    }
    prepareStatement(
        """
        SELECT set_config('app.current_user_id', ?, true),
               set_config('app.is_admin', ?, true),
               set_config('app.authorized_studies', ?, true)
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, "tester")
        statement.setString(2, isAdmin.toString())
        statement.setString(3, authorizedStudies)
        statement.execute()
    }
}
