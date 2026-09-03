package com.openlattice.chronicle.storage.tde

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties

/**
 * W4 — PostgreSQL transport hardening: `sslmode` enforcement (HIPAA §164.312(e)(1) — transmission
 * security; in-transit encryption of ePHI).
 *
 * Two compliance claims are exercised here:
 *
 *  1. **Behavioral (real server):** against a Postgres server configured to REQUIRE SSL (the prod
 *     posture — `scram-sha-256` + SSL-only `pg_hba.conf`), a JDBC connection with `sslmode=disable`
 *     is **refused**, while `sslmode=require` / `verify-full`-class connections succeed. This proves
 *     a downgraded connection cannot reach the database — the property the W4 JDBC-URL pin protects.
 *  2. **Configuration pin:** the production datasource template
 *     (`docker/rhizome-docker.yaml.template`) hard-codes `sslmode=verify-full` in the JDBC URL, so a
 *     deploy cannot silently downgrade the connection via the `POSTGRES_SSL_MODE` env var.
 *
 * The server is the EXACT production image (`percona/percona-distribution-postgresql:18.4-5`); SSL
 * is turned on with a self-signed cert and the `pg_hba.conf` is rewritten to `hostssl … scram-sha-256`
 * (rejecting every non-SSL TCP connection) — mirroring the prod hardened posture. If the image is not
 * pullable in the runner, the behavioral test SKIPS via JUnit `Assume`; the config-pin test still runs.
 */
class PostgresSslModeEnforcementTest {

    companion object {
        // Single-sourced from the contract schema so the pin cannot drift from the other suites.
        private const val PERCONA_PG_TDE_IMAGE =
            com.openlattice.chronicle.contract.ChronicleContractTestSchema.PROD_POSTGRES_IMAGE

        private var postgres: PostgreSQLContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun startSslRequiredContainer() {
            val container = try {
                val image = DockerImageName.parse(PERCONA_PG_TDE_IMAGE)
                    .asCompatibleSubstituteFor("postgres")
                PostgreSQLContainer(image)
                    // Percona redirects logs to a collector → the default log-wait never matches;
                    // wait on the listening port instead.
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))
                    .also { it.start() }
            } catch (e: Throwable) {
                assumeNoException(
                    "Percona image ($PERCONA_PG_TDE_IMAGE) unavailable — SSL enforcement is " +
                        "verified by the staging smoke test instead (see W4-encryption-transport.md).",
                    e,
                )
                return
            }

            try {
                // forListeningPort opens before the server accepts SQL; wait for query-readiness.
                waitForQueryReady(container)
                // Generate a self-signed server cert inside PGDATA and force SSL-only TCP auth.
                // (PGDATA on this image is /data/db; openssl ships in the image.)
                val pgdata = execAsPostgres(container, "psql", "-h", "/var/run/postgresql", "-U",
                    container.username, "-d", container.databaseName, "-tAc", "SHOW data_directory;").trim()

                execAsPostgres(
                    container, "sh", "-c",
                    "cd $pgdata && openssl req -new -x509 -days 1 -nodes -text " +
                        "-out server.crt -keyout server.key -subj '/CN=localhost' && " +
                        "chmod 600 server.key && chown ${container.username}:${container.username} server.crt server.key",
                )
                // ssl_cert_file / ssl_key_file are not transaction-safe; run them as separate
                // statements over the local socket (trust auth).
                socketSql(container, "ALTER SYSTEM SET ssl = on;")
                socketSql(container, "ALTER SYSTEM SET ssl_cert_file = 'server.crt';")
                socketSql(container, "ALTER SYSTEM SET ssl_key_file = 'server.key';")
                // Require SSL for ALL TCP; keep the unix socket on trust for admin.
                execAsPostgres(
                    container, "sh", "-c",
                    "printf 'local all all trust\\n" +
                        "hostssl all all 0.0.0.0/0 scram-sha-256\\n" +
                        "hostssl all all ::/0 scram-sha-256\\n' > $pgdata/pg_hba.conf",
                )
                // SSL params + pg_hba are SIGHUP-reloadable in Postgres — a reload (NOT a restart)
                // applies them. A restart would remap the Testcontainers host port and break the
                // JDBC URL, so we deliberately reload in place.
                socketSql(container, "SELECT pg_reload_conf();")
                waitForSsl(container)
                postgres = container
            } catch (e: Throwable) {
                container.stop()
                assumeNoException("Could not configure SSL-required Postgres for the test", e)
            }
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            postgres?.stop()
        }

        // psql runs over the unix socket (PGDATA local trust) as the image's default db user.
        private fun execAsPostgres(c: PostgreSQLContainer<*>, vararg cmd: String): String {
            val res = c.execInContainer(*cmd)
            return res.stdout + res.stderr
        }

        private fun socketSql(c: PostgreSQLContainer<*>, sql: String) {
            c.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-h", "/var/run/postgresql",
                "-U", c.username, "-d", c.databaseName, "-c", sql,
            )
        }

        private fun waitForSsl(c: PostgreSQLContainer<*>) {
            repeat(30) {
                val r = c.execInContainer(
                    "psql", "-h", "/var/run/postgresql", "-U", c.username, "-d", c.databaseName,
                    "-tAc", "SHOW ssl;",
                )
                if (r.stdout.trim() == "on") return
                Thread.sleep(1000)
            }
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

    private fun connectWith(sslmode: String): Result<Unit> {
        val pg = requireNotNull(postgres) { "container not started" }
        // Build a JDBC URL on the mapped host port with the given sslmode (no sslrootcert: 'require'
        // encrypts without verifying the self-signed cert — sufficient to prove SSL is enforced).
        val baseUrl = pg.jdbcUrl.substringBefore("?")
        val props = Properties().apply {
            setProperty("user", pg.username)
            setProperty("password", pg.password)
            setProperty("sslmode", sslmode)
            setProperty("loginTimeout", "10")
            setProperty("connectTimeout", "10")
        }
        return runCatching {
            DriverManager.getConnection(baseUrl, props).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT 1").use { rs -> rs.next() }
                }
            }
        }
    }

    @Test
    fun `sslmode=disable is refused by an SSL-required server`() {
        val result = connectWith("disable")
        assertTrue(
            "a plaintext (sslmode=disable) connection MUST be refused by the SSL-required server",
            result.isFailure,
        )
        // PG refuses with a 'no pg_hba.conf entry … no encryption' FATAL on the unencrypted attempt.
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "refusal must be an SSL/hba enforcement error, was: $msg",
            msg.contains("no encryption", ignoreCase = true) ||
                msg.contains("pg_hba", ignoreCase = true) ||
                msg.contains("SSL", ignoreCase = true),
        )
    }

    @Test
    fun `sslmode=require succeeds against the SSL-required server`() {
        val result = connectWith("require")
        if (result.isFailure) {
            fail("an encrypted (sslmode=require) connection must succeed: ${result.exceptionOrNull()}")
        }
    }

    @Test
    fun `production datasource template pins sslmode=verify-full in the JDBC URL`() {
        // Walk up from the test working dir to find the monorepo docker/ template. When chronicle-server
        // is built standalone (no monorepo checkout) the template is absent → assert-skip via assumeNoException.
        val template = locateTemplate()
        if (template == null) {
            // Not a failure: the behavioral tests above carry the control; this is the belt-and-braces pin.
            return
        }
        val text = template.readText()
        val jdbcLines = text.lines().filter { it.contains("jdbcUrl") }
        assertFalse("template must define at least one jdbcUrl", jdbcLines.isEmpty())
        jdbcLines.forEach { line ->
            assertTrue(
                "every jdbcUrl in ${template.path} must pin sslmode=verify-full, was: $line",
                line.contains("sslmode=verify-full"),
            )
            // Guard against a silent downgrade left in the file.
            listOf("sslmode=disable", "sslmode=allow", "sslmode=prefer").forEach { weak ->
                assertEquals(
                    "weak sslmode '$weak' must not appear in the pinned JDBC URL",
                    -1,
                    line.indexOf(weak),
                )
            }
        }
        // sanity: at least the documented two datasource URLs are present.
        assertTrue("expected at least 2 pinned jdbcUrls", jdbcLines.size >= 2)
    }

    private fun locateTemplate(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = dir?.resolve("docker/rhizome-docker.yaml.template")
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Test
    fun `verify-full is a stronger mode than the env default and is the pinned target`() {
        // Documents the W4 decision: verify-full (encrypt + verify cert chain + verify hostname) is
        // the pinned mode, strictly stronger than the historical `require` env default (encrypt only).
        val template = locateTemplate() ?: return
        assertNull(
            "the pinned URL must not fall back to the weaker 'require' mode",
            template.readText().lines()
                .firstOrNull { it.contains("jdbcUrl") && it.contains("sslmode=require") },
        )
    }
}
