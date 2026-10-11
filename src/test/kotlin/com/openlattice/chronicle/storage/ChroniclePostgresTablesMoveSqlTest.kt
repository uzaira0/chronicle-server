package com.openlattice.chronicle.storage

import com.openlattice.chronicle.services.upload.UploadType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChroniclePostgresTablesMoveSqlTest {

    @Test
    fun globalClaimIsDeletionAwareAndOpportunistic() {
        val sql = ChroniclePostgresTables.getMoveSql(128, UploadType.Android)

        assertTrue(sql.contains("chronicle_participant_mutation_allowed"))
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"))
        assertFalse(sql.contains("candidate.study_id = ?"))
        assertFalse(sql.contains("candidate.participant_id = ?"))
    }

    @Test
    fun scopedClaimWaitsForTheExactSubject() {
        val sql = ChroniclePostgresTables.getScopedMoveSql(128, UploadType.Android)

        assertTrue(sql.contains("chronicle_participant_mutation_allowed"))
        assertTrue(sql.contains("candidate.study_id = ?"))
        assertTrue(sql.contains("candidate.participant_id = ?"))
        assertTrue(sql.contains("FOR UPDATE"))
        assertFalse(sql.contains("SKIP LOCKED"))
    }

    @Test
    fun claimsRejectNonPositiveBatchSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            ChroniclePostgresTables.getMoveSql(0, UploadType.Android)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChroniclePostgresTables.getScopedMoveSql(-1, UploadType.Android)
        }
    }

    @Test fun bothAndroidClaimsBoundBytesAndSurfaceOversizedRowsWithoutLoadingThem() {
        for (sql in listOf(ChroniclePostgresTables.getMoveSql(128, UploadType.Android),
            ChroniclePostgresTables.getScopedMoveSql(128, UploadType.Android))) {
            assertTrue(sql.contains("sum(payload_bytes) OVER"))
            assertTrue(sql.contains("claimed_bytes <= ${ChroniclePostgresTables.MAX_ANDROID_CLAIM_BYTES}"))
            assertTrue(sql.contains("OR claim_order = 1"))
            assertTrue(sql.contains("ELSE NULL END AS data"))
        }
    }

}
