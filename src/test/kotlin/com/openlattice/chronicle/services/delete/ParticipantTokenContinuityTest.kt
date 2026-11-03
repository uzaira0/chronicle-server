package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.bootstrapConnection
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.sql.SQLException

class ParticipantTokenContinuityTest {
    companion object {
        @BeforeClass @JvmStatic fun start() = RestoreContinuityFixture.start()
        @AfterClass @JvmStatic fun stop() = RestoreContinuityFixture.stop()
    }

    private val fixture = RestoreContinuityFixture()
    @After fun clean() = fixture.cleanFixture()

    @Test fun `restore rejects another token key generation before consuming authorities`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            fixture.createContinuitySchema(connection)
            fixture.sealContinuityCheckpoint(connection)
            assertEquals(fixture.checkpointId, fixture.checkpointRepository.readValidated(connection).checkpointId)
            connection.createStatement().use { statement ->
                try {
                    statement.executeUpdate("UPDATE public.chronicle_participant_token_key SET key_bytes = public.gen_random_bytes(32)")
                    val failure = assertThrows(SQLException::class.java) {
                        fixture.checkpointRepository.readValidated(connection)
                    }
                    assertTrue(failure.message.orEmpty().contains("Participant token key differs from protected restore checkpoint"))
                    assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
                    assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
                } finally {
                    statement.executeUpdate("UPDATE public.chronicle_participant_token_key SET key_bytes = " +
                        "(SELECT key_bytes FROM chronicle_restore_continuity.participant_token_key)")
                }
            }
        }
    }
}
