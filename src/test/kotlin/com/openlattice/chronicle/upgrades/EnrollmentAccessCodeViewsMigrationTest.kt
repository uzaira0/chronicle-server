package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.seedCandidate

import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSConnectionSql
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrollmentAccessCodeViewsMigrationTest {
    @Test
    fun viewsSelectEnrollmentEvidenceAndKeepApplicationStudyIsolation() {
        ChronicleContractTestSchema.prodPostgresContainer("enrollment_code_views").use { postgres ->
            postgres.start()
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            postgres.createConnection("").use { connection ->
                val visible = seedStudy(connection)
                val hidden = seedStudy(connection)
                val pending = seedCode(connection, visible)
                val expiredInvitation = seedCode(connection, visible, Code(invitationExpired = true))
                val expiredReplay = seedCode(connection, visible, Code(replayExpired = true))
                val revoked = seedCode(connection, visible, Code(revoked = true))
                val acknowledged = seedCode(connection, visible)
                acknowledge(connection, visible, acknowledged, ConsentTrigger.ENROLLMENT)
                val toggled = seedCode(connection, visible)
                acknowledge(connection, visible, toggled, ConsentTrigger.PARTICIPANT_TOGGLE)
                val unbound = seedCode(connection, visible, Code(bound = false))
                val exchangedOnly = seedCode(connection, visible, Code(bound = false, exchanged = true))
                val browser = seedCode(connection, visible, Code(kind = ParticipantFormKind.PORTAL, bound = false, exchanged = true))
                val hiddenPending = seedCode(connection, hidden)
                val hiddenExchanged = seedCode(connection, hidden, Code(replayExpired = true))
                val pendingIds = setOf(pending, expiredInvitation, toggled)
                val exchangedIds = pendingIds + setOf(expiredReplay, revoked, acknowledged, exchangedOnly)
                assertViews(connection, pendingIds + hiddenPending, exchangedIds + hiddenPending + hiddenExchanged)
                assertEquals(setOf(unbound, browser), allIds(connection) - exchangedIds - hiddenPending - hiddenExchanged)
                RLSConnectionSql.applyContext(connection, RLSConnectionContext("view-test", setOf(visible), isAdmin = false))
                connection.createStatement().use { it.execute("SET ROLE chronicle_app") }
                try {
                    assertViews(connection, pendingIds, exchangedIds)
                    RLSConnectionSql.applyContext(connection, RLSConnectionContext("view-test", emptySet(), isAdmin = false))
                    assertViews(connection, emptySet(), emptySet())
                } finally {
                    RLSConnectionSql.clear(connection)
                }
            }
        }
    }

    private fun seedStudy(connection: Connection): UUID = UUID.randomUUID().also { studyId ->
        connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'view fixture')").use {
            it.setObject(1, studyId)
            it.executeUpdate()
        }
        connection.prepareStatement("""
            INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
            VALUES (?, 'view-subject', ?, ?)
        """.trimIndent()).use {
            it.setObject(1, studyId)
            it.setObject(2, seedCandidate(connection))
            it.setString(3, ParticipationStatus.NOT_ENROLLED.name)
            it.executeUpdate()
        }
    }

    private data class Code(
        val kind: ParticipantFormKind = ParticipantFormKind.ENROLLMENT,
        val bound: Boolean = true,
        val exchanged: Boolean = bound,
        val invitationExpired: Boolean = false,
        val replayExpired: Boolean = false,
        val revoked: Boolean = false,
    )

    private fun seedCode(
        connection: Connection,
        studyId: UUID,
        code: Code = Code(),
    ): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        connection.prepareStatement("""
            INSERT INTO participant_form_access_codes
                (access_code_id, token_hash, study_id, participant_id, form_kind, issuer_type, issued_by,
                 expires_at, exchanged_at, revoked_at, enrollment_attempt_id, enrollment_random_device_id,
                 enrollment_replay_expires_at, enrollment_source_device_hash, enrollment_manifest_digest,
                 enrollment_request_hash, enrollment_proposed_key_hash)
            VALUES (?, ?, ?, 'view-subject', ?, ?, 'view-test', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use {
            it.setObject(1, id)
            it.setBytes(2, id.toString().toByteArray())
            it.setObject(3, studyId)
            it.setString(4, code.kind.name)
            it.setString(5, ParticipantAccessCodeIssuerType.DEVICE.name)
            it.setObject(6, if (code.invitationExpired) now.minusHours(1) else now.plusHours(1))
            it.setObject(7, if (code.exchanged) now.minusHours(2) else null)
            it.setObject(8, if (code.revoked) now else null)
            it.setObject(9, if (code.bound) UUID.randomUUID() else null)
            it.setObject(10, if (code.bound) UUID.randomUUID() else null)
            it.setObject(11, if (!code.bound) null else if (code.replayExpired) now.minusHours(1) else now.plusHours(1))
            for (index in 12..15) it.setString(index, if (code.bound) "a".repeat(64) else null)
            assertEquals(1, it.executeUpdate())
        }
        return id
    }

    private fun acknowledge(connection: Connection, studyId: UUID, codeId: UUID, trigger: ConsentTrigger) {
        connection.prepareStatement("""
            INSERT INTO participant_collection_acknowledgment
                (id, study_id, participant_id, source_random_device_id, acknowledged_modules, acknowledged_at,
                 settings_version, disclosure_version, manifest_digest, evidence_access_code_id,
                 evidence_api_key_id, collection_trigger, decision_seq)
            VALUES (?, ?, 'view-subject', 'view-device', '[]', now(), 1, 'v1', ?, ?, ?, ?, 1)
        """.trimIndent()).use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, studyId)
            it.setString(3, "a".repeat(64))
            it.setObject(4, codeId)
            it.setObject(5, UUID.randomUUID())
            it.setString(6, trigger.name)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun assertViews(connection: Connection, pending: Set<UUID>, exchanged: Set<UUID>) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT access_code_id FROM pending_enrollment_access_codes").use { rows ->
                assertEquals(pending, buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) })
            }
            statement.executeQuery("SELECT access_code_id FROM exchanged_enrollment_access_codes").use { rows ->
                assertEquals(exchanged, buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) })
            }
        }
    }

    private fun allIds(connection: Connection): Set<UUID> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT access_code_id FROM participant_form_access_codes").use { rows ->
            buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
        }
    }
}
