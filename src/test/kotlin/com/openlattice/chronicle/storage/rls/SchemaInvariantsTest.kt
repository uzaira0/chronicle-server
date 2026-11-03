package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.participants.ParticipantIds
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.study.Study
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.Size
import org.mockito.kotlin.mock
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaInvariantsTest {
    @Test
    fun participantIdsMatchTheApplicationContractAtTheDatabaseBoundary() = transaction { connection ->
        val study = UUID.randomUUID()
        val candidate = UUID.randomUUID()
        seed(connection, study, candidate)
        for (id in listOf("", ".", "..", "p/1", "p 1", "p\n1", "p\n", "\np", "a".repeat(256))) {
            assertTrue("Application rejects $id", !ParticipantIds.isValid(id))
            rejected(connection, "23514") { participant(connection, study, candidate, id) }
        }
        for (id in listOf("a", "...", "abc_12-3.4", "a".repeat(255))) {
            assertTrue(ParticipantIds.isValid(id))
            assertEquals(1, participant(connection, study, candidate, id))
        }
    }

    @Test
    fun enrollmentRequiresExistingStudyAndCandidateAndPreservesDeletionOrder() = transaction { connection ->
        val study = UUID.randomUUID()
        val candidate = UUID.randomUUID()
        seed(connection, study, candidate)
        rejected(connection, "23503") { participant(connection, UUID.randomUUID(), candidate, "unknown-study") }
        rejected(connection, "23503") { participant(connection, study, UUID.randomUUID(), "unknown-candidate") }
        participant(connection, study, candidate, "enrolled")
        rejected(connection, "23503") {
            connection.createStatement().use { it.executeUpdate("DELETE FROM candidates WHERE candidate_id = '$candidate'") }
        }
        connection.createStatement().use { statement ->
            assertEquals(1, statement.executeUpdate("DELETE FROM studies WHERE study_id = '$study'"))
            statement.executeQuery("SELECT count(*) FROM study_participants WHERE study_id = '$study'").use { rows ->
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
            }
            assertEquals(1, statement.executeUpdate("DELETE FROM candidates WHERE candidate_id = '$candidate'"))
        }
    }

    @Test
    fun studyAndOrganizationTitlesCannotBeNullOrBlank() = transaction { connection ->
        for ((table, key) in listOf("studies" to "study_id", "organizations" to "organization_id")) {
            val maxLength = titleMaximum(if (table == "studies") Study::class.java else Organization::class.java)
            for (title in listOf<String?>(null, "", "   ", "\t\n", "a".repeat(maxLength + 1), "😀".repeat(maxLength / 2 + 1))) {
                rejected(connection, if (title == null) "23502" else "23514") {
                    connection.prepareStatement("INSERT INTO $table ($key, title) VALUES (?, ?)").use { statement ->
                        statement.setObject(1, UUID.randomUUID()); statement.setString(2, title); statement.executeUpdate()
                    }
                }
            }
            val validTitle = "😀".repeat(maxLength / 2)
            connection.prepareStatement("""
                SELECT current_setting('server_encoding'), char_length(?),
                    length(regexp_replace(?, '[\U00010000-\U0010FFFF]', 'xx', 'g'))
            """.trimIndent()).use { statement ->
                statement.setString(1, validTitle); statement.setString(2, validTitle)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Unicode schema contract; char_length=${rows.getInt(2)}, utf16_length=${rows.getInt(3)}",
                        "UTF8", rows.getString(1))
                    assertEquals("UTF-16 title length; server_encoding=${rows.getString(1)}, char_length=${rows.getInt(2)}",
                        validTitle.length, rows.getInt(3))
                }
            }
            connection.prepareStatement("INSERT INTO $table ($key, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, UUID.randomUUID()); statement.setString(2, validTitle)
                assertEquals(1, statement.executeUpdate())
            }
            assertTrue("$table accepts the declared maximum", titleAccepted(connection, table, key, "a".repeat(maxLength)))
        }
    }

    @Test
    fun unicodeBlankTitlesMatchThePublicValidationContract() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            transaction { connection -> assertUnicodeTitleParity(connection, factory.validator) }
        }
    }

    private fun assertUnicodeTitleParity(connection: Connection, validator: Validator) {
        val whitespace = (Character.MIN_CODE_POINT..Character.MAX_CODE_POINT)
            .filter { Character.isWhitespace(it) }.map { String(Character.toChars(it)) }
        val candidates = whitespace + listOf("\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", "\uFEFF", "\nvisible\n", "😀")
        for ((table, key) in listOf("studies" to "study_id", "organizations" to "organization_id")) {
            val model = if (table == "studies") Study::class.java else Organization::class.java
            for (title in candidates) {
                val applicationAccepts = validator.validateValue(model, "title", title).isEmpty()
                val databaseAccepts = titleAccepted(connection, table, key, title)
                assertEquals("${model.simpleName} ${title.codePoints().toArray().toList()}: " +
                    "application=$applicationAccepts, database=$databaseAccepts", applicationAccepts, databaseAccepts)
            }
        }
    }

    private fun titleAccepted(connection: Connection, table: String, key: String, title: String): Boolean {
        val savepoint = connection.setSavepoint()
        return try {
            connection.prepareStatement("INSERT INTO $table ($key, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, UUID.randomUUID()); statement.setString(2, title); statement.executeUpdate() == 1
            }
        } catch (failure: SQLException) {
            if (failure.sqlState != "23514") throw failure
            false
        } finally {
            connection.rollback(savepoint); connection.releaseSavepoint(savepoint)
        }
    }

    @Test
    fun tenantContextCannotReadOrMutateOtherStudiesOrganizationsOrUsers() = transaction { connection ->
        val allowed = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        seedTenantRows(connection, allowed, foreign)
        connection.createStatement().use { it.execute("SET LOCAL ROLE chronicle_app") }
        for ((table, countSql, mutations) in listOf(
            Triple("studies", "SELECT count(*) FROM studies", listOf(
                "UPDATE studies SET study_id = study_id WHERE study_id = ?", "DELETE FROM studies WHERE study_id = ?")),
            Triple("organizations", "SELECT count(*) FROM organizations", listOf(
                "UPDATE organizations SET organization_id = organization_id WHERE organization_id = ?",
                "DELETE FROM organizations WHERE organization_id = ?")),
            Triple("users", "SELECT count(*) FROM users", listOf(
                "UPDATE users SET user_id = user_id WHERE user_id = ?", "DELETE FROM users WHERE user_id = ?")),
        )) assertOnlyOwnTenantRow(connection, table, countSql, mutations, foreign)
        for ((table, sql) in listOf(
            "jwt_session_state" to "SELECT count(*) FROM jwt_session_state",
            "data_deletion_tombstones" to "SELECT count(*) FROM data_deletion_tombstones",
        )) {
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(table, 0, rows.getInt(1)) }
            }
        }
        assertStudyScopeCannotAuthorizeOrganizations(connection)
    }

    private fun assertOnlyOwnTenantRow(
        connection: Connection, table: String, countSql: String, mutations: List<String>, foreign: UUID,
    ) {
        connection.prepareStatement(countSql).use { statement ->
            statement.executeQuery().use { rows ->
                assertTrue(rows.next()); assertEquals(table, if (table == "users") 0 else 1, rows.getInt(1))
            }
        }
        for (sql in mutations) {
            connection.prepareStatement(sql).use { statement ->
                if (table == "users") statement.setString(1, "foreign-user") else statement.setObject(1, foreign)
                assertEquals(table, 0, statement.executeUpdate())
            }
        }
    }

    private fun assertStudyScopeCannotAuthorizeOrganizations(connection: Connection) {
        for (organizations in listOf("", "malformed", UUID.randomUUID().toString())) {
            connection.prepareStatement("SELECT set_config('app.authorized_organizations', ?, true)").use { statement ->
                statement.setString(1, organizations)
                statement.execute()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM organizations").use { rows ->
                    assertTrue(rows.next()); assertEquals("Study scope alone cannot authorize organizations", 0, rows.getInt(1))
                }
            }
        }
    }

    private fun seedTenantRows(connection: Connection, allowed: UUID, foreign: UUID) {
        for (id in listOf(allowed, foreign)) {
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'title')").use { statement ->
                statement.setObject(1, id); statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO organizations (organization_id, title) VALUES (?, 'title')").use { statement ->
                statement.setObject(1, id); statement.executeUpdate()
            }
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO users (user_id) VALUES ('scope-user'), ('foreign-user')")
        }
        connection.prepareStatement("""
            SELECT set_config('app.authorized_studies', ?, true),
                set_config('app.authorized_organizations', ?, true),
                set_config('app.current_user_id', 'scope-user', true), set_config('app.is_admin', 'false', true)
        """.trimIndent()).use { statement ->
            statement.setString(1, allowed.toString()); statement.setString(2, allowed.toString()); statement.execute()
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO jwt_session_state (state_group, state_key, state_value) VALUES ('global-state', 'protected', 1)")
        }
        connection.prepareStatement("""
            INSERT INTO data_deletion_tombstones (operation_id, study_ref, mode, registry_version, completed_at, proof_hash)
            VALUES (?, 'opaque-study', 'STUDY_ERASURE', 1, now(), 'proof')
        """.trimIndent()).use { statement ->
            statement.setObject(1, UUID.randomUUID()); statement.executeUpdate()
        }
    }

    @Test
    fun creatingAStudyAndOrganizationAuthorizesTheirOwnWritesInTheTransaction() = transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute("""
                SELECT set_config('app.current_user_id', 'scope-user', true), set_config('app.is_admin', 'false', true),
                    set_config('app.authorized_studies', '', true), set_config('app.authorized_organizations', '', true)
            """.trimIndent())
            statement.execute("SET LOCAL ROLE chronicle_app")
            val createdStudy = UUID.randomUUID()
            RLSConnectionCustomizer.authorizeCreatedStudy(connection, createdStudy)
            assertEquals(1, statement.executeUpdate("INSERT INTO studies (study_id, title) VALUES ('$createdStudy', 'new study')"))
            val organization = Organization(id = UUID.randomUUID(), title = "new organization")
            val service = ChronicleOrganizationService(
                mock(), mock(), mock(), mock(),
            )
            service.createOrganization(connection, Principal(
                PrincipalType.USER, "scope-user",
            ), organization)
            statement.executeQuery("SELECT count(*) FROM organizations WHERE organization_id = '${organization.id}'").use { rows ->
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1))
            }
        }
    }

    @Test
    fun anonymousMarkerCannotReadAUserWithTheSameIdentifier() = transaction { connection ->
        val anonymous = RLSRequestContext.anonymousContext
        val owner = "chronicle-anonymous"
        connection.prepareStatement("INSERT INTO users (user_id, user_data) VALUES (?, ?::jsonb)").use { statement ->
            statement.setString(1, owner)
            statement.setString(2, """{"id":"$owner","name":"Private synthetic profile"}""")
            statement.executeUpdate()
        }
        RLSConnectionSql.applyContext(connection, anonymous)
        connection.prepareStatement("""
            SELECT current_user, current_setting('app.is_admin', true),
                (SELECT count(*) FROM users WHERE user_id = ?),
                (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)
        """.trimIndent()).use { statement ->
            statement.setString(1, owner)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals("chronicle_app", rows.getString(1))
                assertEquals("false", rows.getString(2))
                assertEquals(false, rows.getBoolean(4))
                assertEquals("Anonymous SQL must not read a persisted user named like its context marker", 0, rows.getInt(3))
            }
        }
    }

    @Test
    fun ordinaryAdminWhoseSubjectMatchesWorkerCannotReadBlockedEnrollment() = transaction { connection ->
        val context = ordinaryAdminContext(RLSConnectionCustomizer.DELETION_WORKER_ID)
        assertTrue("The ordinary authenticated user has legitimate admin membership", context.isAdmin)
        assertTrue("Authenticated subjects never become trusted workers", !context.isDeletionWorker)
        assertEquals(RLSConnectionCustomizer.DELETION_WORKER_ID, context.principalId)
        assertBlockedEnrollmentHidden(connection, context)
    }

    private fun ordinaryAdminContext(subject: String): RLSConnectionContext {
        val authorization = org.mockito.Mockito.mock(com.openlattice.chronicle.authorization.AuthorizationManager::class.java)
        org.mockito.Mockito.`when`(authorization.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenAnswer { java.util.stream.Stream.empty<com.openlattice.chronicle.authorization.AclKey>() }
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext(subject = subject, admin = true)
        return try { RLSContextManager(authorization).getCurrentUserContext() }
        finally { com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext() }
    }

    private fun assertBlockedEnrollmentHidden(connection: Connection, context: RLSConnectionContext) {
        val study = UUID.randomUUID()
        val candidate = UUID.randomUUID()
        seed(connection, study, candidate)
        participant(connection, study, candidate, "blocked-worker-collision")
        connection.prepareStatement("""
            INSERT INTO data_deletion_operations
                (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version)
            VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'worker-collision-test', ?, 1)
        """.trimIndent()).use { statement ->
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, study)
            statement.setObject(3, UUID.randomUUID()); statement.executeUpdate()
        }
        assertQuarantinedRows(connection, ordinaryAdminContext("ordinary-admin-control"), study, 0)
        val worker = RLSRequestContext.withDeletionWorkerContext { requireNotNull(RLSRequestContext.current()) }
        assertQuarantinedRows(connection, worker, study, 1)
        assertQuarantinedRows(connection, context, study, 0)
    }

    private fun assertQuarantinedRows(connection: Connection, context: RLSConnectionContext, study: UUID, expected: Int) {
        assertTrue(context.isAdmin)
        assertEquals(expected == 1, context.isDeletionWorker)
        RLSConnectionSql.applyContext(connection, context)
        connection.prepareStatement("""
            SELECT current_user, current_setting('app.is_admin', true),
                (SELECT count(*) FROM study_participants WHERE study_id = ?), current_setting('app.is_deletion_worker', true)
        """.trimIndent()).use { statement ->
            statement.setObject(1, study)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals("chronicle_app", rows.getString(1))
                assertEquals("true", rows.getString(2))
                assertEquals((expected == 1).toString(), rows.getString(4))
                assertEquals("Ordinary authenticated admin subject must not grant deletion-worker visibility", expected, rows.getInt(3))
            }
        }
    }

    private fun titleMaximum(model: Class<*>): Int = Validation.buildDefaultValidatorFactory().use { factory ->
        val property = checkNotNull(factory.validator.getConstraintsForClass(model).getConstraintsForProperty("title")) {
            "${model.name}.title must declare its validation contract"
        }
        property.constraintDescriptors.map { it.annotation }.filterIsInstance<Size>().single().max
    }

    private fun seed(connection: Connection, study: UUID, candidate: UUID) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO studies (study_id, title) VALUES ('$study', 'title')")
            statement.executeUpdate("INSERT INTO candidates (candidate_id) VALUES ('$candidate')")
        }
    }

    private fun participant(connection: Connection, study: UUID, candidate: UUID, id: String): Int =
        connection.prepareStatement("""
            INSERT INTO study_participants (study_id, candidate_id, participant_id, participation_status)
            VALUES (?, ?, ?, 'ENROLLED')
        """.trimIndent()).use { statement ->
            statement.setObject(1, study); statement.setObject(2, candidate); statement.setString(3, id); statement.executeUpdate()
        }

    private fun rejected(connection: Connection, sqlState: String, action: () -> Unit) {
        val savepoint = connection.setSavepoint()
        try { assertEquals(sqlState, assertThrows(SQLException::class.java, action).sqlState) }
        finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint) }
    }

    private fun transaction(action: (Connection) -> Unit) {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try { action(connection) } finally { connection.rollback() }
        }
    }
}
