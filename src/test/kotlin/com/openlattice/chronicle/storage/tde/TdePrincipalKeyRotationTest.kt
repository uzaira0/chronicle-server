package com.openlattice.chronicle.storage.tde

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection

/**
 * TDE principal-key ROTATION integration test.
 *
 * The claim is: the Chronicle TDE principal key can be rotated **online** — the data
 * stays encrypted (`pg_tde_is_encrypted()` true before AND after) and stays readable (no
 * re-encryption / no downtime). `scripts/rotate-tde-principal-key.sh` automates exactly this
 * sequence; this test exercises the **same SQL the script runs** against a real Percona `pg_tde`
 * server, so the rotation procedure is proven, not just lint-checked.
 *
 * Fidelity:
 *  - Uses the production image from [ChronicleContractTestSchema] with the
 *    `shared_preload_libraries=pg_tde` server flag and the file key provider — i.e. the same setup
 *    `docker/init-db-encryption.sh` performs in `file` mode (dev/staging keyring).
 *  - Creates the principal key + an encrypted (`tde_heap`) table holding a PHI-like payload,
 *    then rotates the principal key with the verbatim
 *    `pg_tde_create_key_using_database_key_provider` + `pg_tde_set_key_using_database_key_provider`
 *    pair from the rotation script, and re-reads.
 *
 * Container startup and readiness are part of this integration test's contract.
 */
class TdePrincipalKeyRotationTest {

    companion object {
        private const val KEY_PROVIDER = "chronicle-file-vault"
        private const val PRINCIPAL_KEY = "chronicle-principal-key"
        private const val KEYRING_FILE = "/tmp/chronicle-test-keyring.per"

        private const val ENCRYPTED_TABLE = "tde_rotation_phi"
        private const val PHI_PAYLOAD = "phi-sensor-batch-0xDEADBEEF"

        private var postgres: PostgreSQLContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun startContainer() {
            val container = ChronicleContractTestSchema.prodPostgresContainer("tde_rotation", preload = "pg_tde")
            postgres = container
            container.start()
            ChronicleContractTestSchema.waitForQueryReady(container)
        }

        @AfterClass
        @JvmStatic
        fun stopContainer() {
            postgres?.stop()
        }


    }

    private fun datasource(): HikariDataSource {
        val pg = requireNotNull(postgres) { "container not started" }
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 2
                minimumIdle = 1
            },
        )
    }

    /** Mirrors docker/init-db-encryption.sh `setup_file_key_provider` + table creation. */
    private fun initTdeAndSeed(c: Connection) {
        c.createStatement().use { st ->
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_tde")
            st.execute(
                "SELECT pg_tde_add_database_key_provider_file('$KEY_PROVIDER', '$KEYRING_FILE')",
            )
            st.execute(
                "SELECT pg_tde_create_key_using_database_key_provider('$PRINCIPAL_KEY', '$KEY_PROVIDER')",
            )
            st.execute(
                "SELECT pg_tde_set_key_using_database_key_provider('$PRINCIPAL_KEY', '$KEY_PROVIDER')",
            )
            st.execute(
                "CREATE TABLE $ENCRYPTED_TABLE (id INT PRIMARY KEY, secret TEXT) USING tde_heap",
            )
            st.execute("INSERT INTO $ENCRYPTED_TABLE VALUES (1, '$PHI_PAYLOAD')")
        }
    }

    private fun isEncrypted(c: Connection): Boolean =
        c.createStatement().use { st ->
            st.executeQuery("SELECT pg_tde_is_encrypted('$ENCRYPTED_TABLE'::regclass)").use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun readSecret(c: Connection): String? =
        c.createStatement().use { st ->
            st.executeQuery("SELECT secret FROM $ENCRYPTED_TABLE WHERE id = 1").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    private fun currentPrincipalKeyName(c: Connection): String? =
        c.createStatement().use { st ->
            st.executeQuery("SELECT key_name FROM pg_tde_key_info()").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    /**
     * Rotate exactly as scripts/rotate-tde-principal-key.sh does: create a NEW timestamped key
     * version under the active provider, then promote it to principal (re-wraps internal keys).
     */
    private fun rotatePrincipalKey(c: Connection, newKeyName: String) {
        c.prepareStatement(
            "SELECT pg_tde_create_key_using_database_key_provider(?, ?)",
        ).use { statement ->
            statement.setString(1, newKeyName)
            statement.setString(2, KEY_PROVIDER)
            statement.execute()
        }
        c.prepareStatement(
            "SELECT pg_tde_set_key_using_database_key_provider(?, ?)",
        ).use { statement ->
            statement.setString(1, newKeyName)
            statement.setString(2, KEY_PROVIDER)
            statement.execute()
        }
    }

    @Test
    fun `principal key rotates online — data stays encrypted and readable pre and post`() {
        val rotatedKeyName = "$PRINCIPAL_KEY-rot-" + System.currentTimeMillis()

        // Phase 1: init TDE, seed PHI, capture PRE-rotation state, rotate.
        datasource().use { ds ->
            ds.connection.use { c ->
                initTdeAndSeed(c)

                // PRE-rotation: table encrypted, payload readable, principal key is the original.
                assertTrue("table must be TDE-encrypted before rotation", isEncrypted(c))
                assertEquals(PHI_PAYLOAD, readSecret(c))
                assertEquals(PRINCIPAL_KEY, currentPrincipalKeyName(c))

                // ROTATE (the script's create+set pair).
                rotatePrincipalKey(c, rotatedKeyName)

                // POST-rotation, same session: still encrypted, SAME payload, key advanced.
                assertTrue("table must remain TDE-encrypted after rotation", isEncrypted(c))
                assertEquals(
                    "PHI payload must survive principal-key rotation unchanged",
                    PHI_PAYLOAD,
                    readSecret(c),
                )
                assertEquals(
                    "principal key must advance to the rotated version",
                    rotatedKeyName,
                    currentPrincipalKeyName(c),
                )
            }
        }

        // Phase 2: a BRAND-NEW pool/connection must transparently decrypt under the re-wrapped
        // internal keys — proving the rotation is durable, not merely visible to the rotating
        // session.
        datasource().use { ds ->
            ds.connection.use { c ->
                assertTrue("encryption must persist for a fresh connection", isEncrypted(c))
                assertEquals(
                    "fresh connection must still read the PHI payload after rotation",
                    PHI_PAYLOAD,
                    readSecret(c),
                )
                assertEquals(rotatedKeyName, currentPrincipalKeyName(c))
            }
        }
    }
}
