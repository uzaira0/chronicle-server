package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HIPAA-2028 W4 — covers TDE principal-key rotation-age monitoring added to
 * [SecretRotationService] (HIPAA §164.312(a)(2)(iv), encryption key management). The TDE
 * principal key is now a tracked secret on a yearly cadence; its rotation timestamp is stamped
 * into `secret_rotation_tracking` by scripts/rotate-tde-principal-key.sh and surfaced here.
 *
 * These exercise the pure age/threshold logic (no DB): [SecretRotationService.rotationDates] is
 * populated directly, the same reflection approach used by [EncryptionHealthServiceTest].
 */
class SecretRotationServiceTest {

    private fun service() = SecretRotationService(Mockito.mock(StorageResolver::class.java))

    private fun metricsPath(): Path =
        Path.of("build", "test-secret-rotation", UUID.randomUUID().toString(), "rotation.prom")

    private fun deleteMetricsFixture(path: Path) {
        Files.deleteIfExists(path)
        Files.deleteIfExists(path.resolveSibling("${path.fileName}.tmp"))
        Files.deleteIfExists(path.parent)
    }

    @Suppress("UNCHECKED_CAST")
    private fun rotationDates(service: SecretRotationService): ConcurrentHashMap<String, Instant> {
        val field = SecretRotationService::class.java.getDeclaredField("rotationDates")
        field.isAccessible = true
        return field.get(service) as ConcurrentHashMap<String, Instant>
    }

    @Test
    fun `tde principal key is a tracked secret`() {
        assertTrue(
            "TDE principal key must be monitored for rotation",
            "tde_principal_key" in SecretRotationService.TRACKED_SECRETS,
        )
    }

    @Test
    fun `tde principal key uses a yearly threshold, others the 90-day default`() {
        assertEquals(365L, SecretRotationService.maxAgeDaysFor("tde_principal_key"))
        assertEquals(
            SecretRotationService.ROTATION_MAX_AGE_DAYS,
            SecretRotationService.maxAgeDaysFor("jwt_signing_secret"),
        )
    }

    @Test
    fun `tde principal key tolerates a longer cadence than the 90-day default`() {
        val service = service()
        val twoHundredDaysAgo = Instant.now().minus(200, ChronoUnit.DAYS)
        rotationDates(service).apply {
            this["tde_principal_key"] = twoHundredDaysAgo
            this["jwt_signing_secret"] = twoHundredDaysAgo
        }

        val status = service.checkRotationStatus()
        assertFalse(
            "TDE key at 200 days is within its 365-day window",
            status.getValue("tde_principal_key").overdue,
        )
        assertTrue(
            "a 90-day secret at 200 days is overdue",
            status.getValue("jwt_signing_secret").overdue,
        )
    }

    @Test
    fun `tde principal key past a year is overdue`() {
        val service = service()
        rotationDates(service)["tde_principal_key"] = Instant.now().minus(400, ChronoUnit.DAYS)

        val tde = service.checkRotationStatus().getValue("tde_principal_key")
        assertTrue("TDE key at 400 days exceeds the 365-day window", tde.overdue)
        assertTrue(tde.ageDays >= 365L)
    }

    @Test
    fun `un-rotated tde principal key reports overdue with no last-rotated date`() {
        val service = service()
        rotationDates(service)["tde_principal_key"] = Instant.EPOCH

        val tde = service.checkRotationStatus().getValue("tde_principal_key")
        assertNull(tde.lastRotated)
        assertTrue(tde.overdue)
    }

    @Test
    fun `future rotation timestamp fails closed as unknown and overdue`() {
        val checkedAt = Instant.parse("2026-08-11T12:00:00Z")
        val service = SecretRotationService.forTest(
            Mockito.mock(StorageResolver::class.java),
            Clock.fixed(checkedAt, ZoneOffset.UTC),
            metricsPath(),
        )
        rotationDates(service)["jwt_signing_secret"] = checkedAt.plus(30, ChronoUnit.DAYS)

        val jwt = service.checkRotationStatus().getValue("jwt_signing_secret")

        assertNull("future ledger data must not be reported as a valid rotation", jwt.lastRotated)
        assertTrue("future ledger data must fail closed instead of producing a negative age", jwt.overdue)
        assertTrue(jwt.ageDays > SecretRotationService.ROTATION_MAX_AGE_DAYS)
    }

    @Test
    fun `secrets health surfaces the tde principal key with its yearly max age`() {
        val service = service()
        rotationDates(service)["tde_principal_key"] = Instant.now().minus(10, ChronoUnit.DAYS)

        val body = service.secretsHealth().body!!
        @Suppress("UNCHECKED_CAST")
        val secrets = body["secrets"] as List<Map<String, Any?>>
        val tde = secrets.first { it["name"] == "tde_principal_key" }
        assertEquals(365L, tde["max_age_days"])
        assertEquals(false, tde["overdue"])
    }

    @Test
    fun `each successful refresh replaces stale dates and derives api key age from usable keys`() {
        val checkedAt = Instant.parse("2026-08-11T12:00:00Z")
        val jwtRotatedAt = checkedAt.minus(10, ChronoUnit.DAYS)
        val misleadingApiLedgerDate = checkedAt.minus(1, ChronoUnit.DAYS)
        val oldestUsableApiKey = checkedAt.minus(120, ChronoUnit.DAYS)
        val metricsPath = metricsPath()
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val trackingFirst = Mockito.mock(Statement::class.java)
        val apiKeysFirst = Mockito.mock(Statement::class.java)
        val trackingSecond = Mockito.mock(Statement::class.java)
        val apiKeysSecond = Mockito.mock(Statement::class.java)
        val trackingFirstRows = Mockito.mock(ResultSet::class.java)
        val apiKeysFirstRows = Mockito.mock(ResultSet::class.java)
        val trackingSecondRows = Mockito.mock(ResultSet::class.java)
        val apiKeysSecondRows = Mockito.mock(ResultSet::class.java)

        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.createStatement())
            .thenReturn(trackingFirst, apiKeysFirst, trackingSecond, apiKeysSecond)
        Mockito.`when`(trackingFirst.executeQuery(Mockito.anyString())).thenReturn(trackingFirstRows)
        Mockito.`when`(apiKeysFirst.executeQuery(Mockito.anyString())).thenReturn(apiKeysFirstRows)
        Mockito.`when`(trackingSecond.executeQuery(Mockito.anyString())).thenReturn(trackingSecondRows)
        Mockito.`when`(apiKeysSecond.executeQuery(Mockito.anyString())).thenReturn(apiKeysSecondRows)
        Mockito.`when`(trackingFirstRows.next()).thenReturn(true, true, false)
        Mockito.`when`(trackingFirstRows.getString("secret_name"))
            .thenReturn("jwt_signing_secret", "api_keys")
        Mockito.`when`(trackingFirstRows.getTimestamp("last_rotated"))
            .thenReturn(Timestamp.from(jwtRotatedAt), Timestamp.from(misleadingApiLedgerDate))
        Mockito.`when`(apiKeysFirstRows.next()).thenReturn(true)
        Mockito.`when`(apiKeysFirstRows.getTimestamp("oldest_key"))
            .thenReturn(Timestamp.from(oldestUsableApiKey))
        Mockito.`when`(trackingSecondRows.next()).thenReturn(false)
        Mockito.`when`(apiKeysSecondRows.next()).thenReturn(true)
        Mockito.`when`(apiKeysSecondRows.getTimestamp("oldest_key")).thenReturn(null)

        val service = SecretRotationService.forTest(
            storageResolver,
            Clock.fixed(checkedAt, ZoneOffset.UTC),
            metricsPath,
        )
        try {
            service.onStartup()
            val first = service.checkRotationStatus()
            assertEquals(jwtRotatedAt, first.getValue("jwt_signing_secret").lastRotated)
            assertEquals(oldestUsableApiKey, first.getValue("api_keys").lastRotated)
            assertTrue(first.getValue("api_keys").overdue)

            service.periodicCheck()
            val second = service.checkRotationStatus()
            assertNull("a removed tracking row must not retain its stale value", second.getValue("jwt_signing_secret").lastRotated)
            assertNull("no usable API key must replace the previous snapshot", second.getValue("api_keys").lastRotated)
            assertEquals(true, service.secretsHealth().body?.get("refresh_ok"))
            assertEquals(true, service.secretsHealth().body?.get("metrics_write_ok"))
            assertTrue(Files.readString(metricsPath).contains("chronicle_secret_rotation_check_success 1"))
        } finally {
            deleteMetricsFixture(metricsPath)
        }
    }

    @Test
    fun `failed refresh clears stale green state and exposes only a normalized failure code`() {
        val checkedAt = Instant.parse("2026-08-11T12:00:00Z")
        val metricsPath = metricsPath()
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenThrow(IllegalStateException("database unavailable"))
        val service = SecretRotationService.forTest(
            storageResolver,
            Clock.fixed(checkedAt, ZoneOffset.UTC),
            metricsPath,
        )
        rotationDates(service)["jwt_signing_secret"] = checkedAt.minus(1, ChronoUnit.DAYS)

        try {
            service.onStartup()

            assertNull(service.checkRotationStatus().getValue("jwt_signing_secret").lastRotated)
            val health = service.secretsHealth().body!!
            assertEquals("WARN", health["status"])
            assertEquals("never", health["last_check"])
            assertEquals(false, health["refresh_ok"])
            assertEquals("IllegalStateException", health["refresh_error"])
            assertTrue(Files.readString(metricsPath).contains("chronicle_secret_rotation_check_success 0"))
        } finally {
            deleteMetricsFixture(metricsPath)
        }
    }
}
