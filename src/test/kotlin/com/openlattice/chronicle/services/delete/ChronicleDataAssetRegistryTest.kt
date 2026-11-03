package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.contract.ChronicleContractTestSchema

import com.openlattice.chronicle.controllers.TestSecurityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.PreparedStatement

class ChronicleDataAssetRegistryTest {
    @Test
    fun processingPoliciesCoverEveryRegisteredResearchDataAsset() {
        val expected = ChronicleDataAssetRegistry.participantAssets.filter { it.processingData }.map { it.tableName }.toSet()
        val actual = ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT tablename FROM pg_policies WHERE schemaname = 'public'
                    AND policyname LIKE 'processing_block_%' AND permissive = 'RESTRICTIVE' AND cmd = 'SELECT'
                """.trimIndent()).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun purgeObservationExpressionsMatchPersistedColumns() {
        com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            ChronicleDataAssetRegistry.participantAssets.forEach { asset ->
                val captureTime = asset.captureTimeSql ?: return@forEach
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT $captureTime FROM ${asset.tableName} LIMIT 0").use { rows ->
                        assertEquals("${asset.id} must compare observation instants", "timestamptz", rows.metaData.getColumnTypeName(1))
                    }
                }
            }
        }
    }

    @Before
    fun authenticate() {
        TestSecurityUtils.setupSecurityContext()
    }

    @After
    fun clearAuthentication() {
        TestSecurityUtils.clearSecurityContext()
    }

    

    @Test
    fun accessArtifactsAreVerifiedBeforeTheirCascadingParent() {
        val ids = ChronicleDataAssetRegistry.participantAssets.map { it.id }

        assertTrue(ids.indexOf("participant-form-receipts") < ids.indexOf("participant-form-access-codes"))
        assertTrue(ids.indexOf("participant-form-sessions") < ids.indexOf("participant-form-access-codes"))
    }

    @Test
    fun deletionQuarantinePolicyCoversEveryRegisteredAsset() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_policy
                    WHERE polrelid = ?::regclass AND polname = ? AND NOT polpermissive AND polcmd = 'r')
            """.trimIndent()).use { statement ->
                ChronicleDataAssetRegistry.participantAssets.filterNot {
                    it.participantScope == ParticipantScope.BLOCK_TOKEN_COLUMN
                }.forEach { asset ->
                    assertQuarantinePolicy(statement, asset.tableName)
                }
            }
        }
    }

    private fun assertQuarantinePolicy(statement: PreparedStatement, tableName: String) {
        statement.setString(1, "public.$tableName")
        statement.setString(2, "deletion_quarantine_$tableName")
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            assertTrue("Missing restrictive quarantine on $tableName", rows.getBoolean(1))
        }
    }

    @Test
    fun everyRegisteredParticipantTableExceptJobsHasBothMutationGuards() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid = ?::regclass AND NOT tgisinternal
                  AND tgname IN ('deletion_mutation_guard_insert', 'deletion_mutation_guard_update')
            """.trimIndent()).use { statement ->
                ChronicleDataAssetRegistry.participantAssets.filterNot {
                    it.tableName == "jobs" || it.participantScope == ParticipantScope.BLOCK_TOKEN_COLUMN
                }.forEach { asset ->
                    assertMutationGuards(statement, asset.tableName)
                }
            }
        }
    }

    private fun assertMutationGuards(statement: PreparedStatement, tableName: String) {
        statement.setString(1, "public.$tableName")
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            assertEquals("Mutation guards on $tableName", 2, rows.getInt(1))
        }
    }

    @Test
    fun everyMigrationTableWithAParticipantIdIsDeletedOrDeliberatelyRetained() {
        val retained = setOf("api_keys", "mobile_withdrawal_requests", "data_deletion_operations", "study_participants",
            "participant_collection_acknowledgment")
        val registered = (ChronicleDataAssetRegistry.participantAssets + ChronicleDataAssetRegistry.withdrawalAssets)
            .map { it.tableName }.toSet()
        val tables = ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT table_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND column_name = 'participant_id'
                      AND table_name IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public')
                """.trimIndent()).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertEquals(emptySet<String>(), tables - registered - retained)
    }

    @Test
    fun kotlinDefinedParticipantTablesAreDeletedOrDeliberatelyRetained() {
        val retained = setOf("study_participants", "api_keys", "participant_collection_acknowledgment")
        val registered = (ChronicleDataAssetRegistry.participantAssets + ChronicleDataAssetRegistry.withdrawalAssets)
            .map { it.tableName }.toSet()
        val tables = listOf(
            com.openlattice.chronicle.storage.ChroniclePostgresTables::class.java,
            com.openlattice.chronicle.storage.PostgresEventTables::class.java,
        ).flatMap { owner -> owner.fields.mapNotNull { field ->
            field.get(null) as? com.geekbeast.postgres.PostgresTableDefinition
        } }.filter { table -> table.columns.any { it.name == "participant_id" } }
            .map { it.name.lowercase() }.toSet()
        assertTrue("Kotlin inventory must include devices", "devices" in tables)
        assertEquals(emptySet<String>(), tables - registered - retained)
    }
}
