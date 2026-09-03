package com.openlattice.chronicle.storage.tde

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.time.Duration

/**
 * W4 — TDE principal-key ROTATION integration test (HIPAA §164.312(a)(2)(iv) — key management).
 *
 * The compliance claim is: the Chronicle TDE principal key can be rotated **online** — the data
 * stays encrypted (`pg_tde_is_encrypted()` true before AND after) and stays readable (no
 * re-encryption / no downtime). `scripts/rotate-tde-principal-key.sh` automates exactly this
 * sequence; this test exercises the **same SQL the script runs** against a real Percona `pg_tde`
 * server, so the rotation procedure is proven, not just lint-checked.
 *
 * Fidelity:
 *  - Uses the EXACT production image (`percona/percona-distribution-postgresql:18.4-5`) with the
 *    `shared_preload_libraries=pg_tde` server flag and the file key provider — i.e. the same setup
 *    `docker/init-db-encryption.sh` performs in `file` mode (dev/staging keyring).
 *  - Creates the principal key + an encrypted (`tde_heap`) table holding a PHI-like payload,
 *    then rotates the principal key with the verbatim
 *    `pg_tde_create_key_using_database_key_provider` + `pg_tde_set_key_using_database_key_provider`
 *    pair from the rotation script, and re-reads.
 *
 * If the Percona image is not pullable in the runner (offline CI), the test SKIPS via JUnit
 * `Assume` rather than failing — the design's documented staging-smoke fallback applies there.
 * Locally the image IS present, so this runs for real.
 */
class TdePrincipalKeyRotationTest {

    companion object {
        // Production TDE image — must carry pg_tde. Plain postgres images cannot run this test.
        // Single-sourced from the contract schema so the pin cannot drift from the other suites.
        private const val PERCONA_PG_TDE_IMAGE =
            com.openlattice.chronicle.contract.ChronicleContractTestSchema.PROD_POSTGRES_IMAGE

        private const val KEY_PROVIDER = "chronicle-file-vault"
        private const val PRINCIPAL_KEY = "chronicle-principal-key"
        private const val KEYRING_FILE = "/tmp/chronicle-test-keyring.per"

        private const val ENCRYPTED_TABLE = "tde_rotation_phi"
        private const val PHI_PAYLOAD = "phi-sensor-batch-0xDEADBEEF"

        private var postgres: PostgreSQLContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun startContainer() {
            // Skip (not fail) if the pg_tde image cannot be obtained in this environment.
            try {
                val image = DockerImageName.parse(PERCONA_PG_TDE_IMAGE)
                    .asCompatibleSubstituteFor("postgres")
                val c = PostgreSQLContainer(image)
                    // pg_tde must be preloaded at server start; this mirrors the compose `-c` flag.
                    .withCommand("postgres", "-c", "shared_preload_libraries=pg_tde")
                    // The Percona image redirects server logs to a logging collector, so the
                    // default "ready to accept connections" log-wait never matches. Wait on the
                    // listening port instead (with a generous timeout for the pg_tde init).
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))
                c.start()
                // forListeningPort opens before the server accepts SQL ("the database system is
                // starting up"). Poll pg_isready until it actually accepts queries.
                waitForQueryReady(c)
                postgres = c
            } catch (e: Throwable) {
                // Image unavailable / docker not present → documented skip, not a red build.
                assumeNoException(
                    "Percona pg_tde image ($PERCONA_PG_TDE_IMAGE) unavailable — TDE rotation " +
                        "is verified by the staging smoke test instead (see W4-tde-key-rotation.md).",
                    e,
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun stopContainer() {
            postgres?.stop()
        }

        /** Poll pg_isready over the unix socket until the server accepts queries (not just listens). */
        private fun waitForQueryReady(c: PostgreSQLContainer<*>) {
            repeat(60) {
                val r = c.execInContainer("pg_isready", "-U", c.username, "-d", c.databaseName)
                if (r.exitCode == 0) return
                Thread.sleep(1000)
            }
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
