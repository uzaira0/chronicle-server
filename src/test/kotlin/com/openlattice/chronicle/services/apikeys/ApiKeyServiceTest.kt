package com.openlattice.chronicle.services.apikeys

import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.ArgumentCaptor
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.*

class ApiKeyServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var idGenerationService: HazelcastIdGenerationService
    private lateinit var auditingManager: AuditingManager
    private lateinit var service: ApiKeyService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet
    private lateinit var mockStatement: Statement

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
        storageResolver = Mockito.mock(StorageResolver::class.java)
        idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        auditingManager = Mockito.mock(AuditingManager::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)
        mockStatement = Mockito.mock(Statement::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.createStatement()).thenReturn(mockStatement)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)
        `when`(mockConnection.autoCommit).thenReturn(true)

        service = ApiKeyService(storageResolver, idGenerationService, auditingManager)
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- createApiKey tests ---

    @Test
    fun testCreateApiKeyReturnsResponse() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        `when`(idGenerationService.getNextId()).thenReturn(keyId)

        val request = ApiKeyCreateRequest(
            name = "Test Key",
            scope = ApiKeyScope.READ_ONLY,
            expiresInDays = 90
        )

        val result = service.createApiKey(studyId, "user-1", request)

        assertNotNull(result)
        assertEquals(keyId, result.keyId)
        assertTrue(result.rawKey.startsWith("ck_"))
        assertEquals("Test Key", result.info.name)
        assertEquals(ApiKeyScope.READ_ONLY, result.info.scope)
        assertEquals(studyId, result.info.studyId)
    }

    @Test
    fun testCreateApiKeyWithWriteScope() {
        val keyId = UUID.randomUUID()
        `when`(idGenerationService.getNextId()).thenReturn(keyId)

        val request = ApiKeyCreateRequest(
            name = "Write Key",
            scope = ApiKeyScope.WRITE,
            expiresInDays = 30
        )

        val result = service.createApiKey(UUID.randomUUID(), "admin", request)

        assertEquals(ApiKeyScope.WRITE, result.info.scope)
    }

    @Test
    fun testCreateApiKeyWithAdminScope() {
        val keyId = UUID.randomUUID()
        `when`(idGenerationService.getNextId()).thenReturn(keyId)

        val request = ApiKeyCreateRequest(
            name = "Admin Key",
            scope = ApiKeyScope.ADMIN,
            expiresInDays = 30
        )

        val result = service.createApiKey(UUID.randomUUID(), "superadmin", request)

        assertEquals(ApiKeyScope.ADMIN, result.info.scope)
    }

    @Test
    fun testCreateApiKeyGeneratesUniqueKeys() {
        `when`(idGenerationService.getNextId())
            .thenReturn(UUID.randomUUID())
            .thenReturn(UUID.randomUUID())

        val request = ApiKeyCreateRequest(name = "Key", scope = ApiKeyScope.READ_ONLY)

        val result1 = service.createApiKey(UUID.randomUUID(), "user", request)
        val result2 = service.createApiKey(UUID.randomUUID(), "user", request)

        assertNotEquals(result1.rawKey, result2.rawKey)
        assertNotEquals(result1.keyId, result2.keyId)
    }

    @Test
    fun testCreateApiKeyPrefixMatchesRawKey() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ApiKeyCreateRequest(name = "Key", scope = ApiKeyScope.READ_ONLY)
        val result = service.createApiKey(UUID.randomUUID(), "user", request)

        // Prefix should be first 8 chars of the raw part (after "ck_XXXXXXXX_")
        val prefix = result.info.prefix
        assertNotNull(prefix)
        assertEquals(8, prefix.length)
    }

    @Test
    fun testCreateApiKeyExpiresAtIsFuture() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ApiKeyCreateRequest(name = "Key", scope = ApiKeyScope.READ_ONLY, expiresInDays = 90)
        val result = service.createApiKey(UUID.randomUUID(), "user", request)

        assertTrue(result.info.expiresAt.isAfter(OffsetDateTime.now()))
    }

    // --- listApiKeys tests ---

    @Test
    fun testListApiKeysReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.listApiKeys(UUID.randomUUID())

        assertTrue(result.isEmpty())
    }

    @Test
    fun testListApiKeysSetsStudyIdParameter() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.listApiKeys(studyId)

        verify(mockPs).setObject(1, studyId)
    }

    // --- revokeApiKey tests ---

    @Test
    fun testRevokeApiKeySucceeds() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()

        service.revokeApiKey(studyId, keyId, "user-1")

        // Verify the revocation SQL was executed
        verify(mockPs).executeUpdate()
    }

    @Test(expected = IllegalStateException::class)
    fun testRevokeApiKeyThrowsWhenNotFound() {
        `when`(mockPs.executeUpdate()).thenReturn(0)

        service.revokeApiKey(UUID.randomUUID(), UUID.randomUUID(), "user-1")
    }

    // --- rotateApiKey tests ---

    @Test(expected = IllegalArgumentException::class)
    fun testRotateApiKeyThrowsWhenKeyNotFound() {
        // listApiKeys returns empty
        `when`(mockRs.next()).thenReturn(false)

        service.rotateApiKey(UUID.randomUUID(), UUID.randomUUID(), "user-1")
    }

    // --- authenticateApiKey tests ---

    @Test
    fun testAuthenticateApiKeyReturnsNullWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.authenticateApiKey("ck_invalid_key")

        assertNull(result)
    }

    @Test
    fun testAuthenticateApiKeyUpdatesUsageOnSuccess() {
        val keyId = UUID.randomUUID()
        val studyId = UUID.randomUUID()
        val now = OffsetDateTime.now()

        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject("key_id", UUID::class.java)).thenReturn(keyId)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getString("key_prefix")).thenReturn("abcdefgh")
        `when`(mockRs.getString("name")).thenReturn("Test Key")
        `when`(mockRs.getString("scope")).thenReturn(ApiKeyScope.READ_ONLY.name)
        `when`(mockRs.getObject("created_at", OffsetDateTime::class.java)).thenReturn(now)
        `when`(mockRs.getObject("expires_at", OffsetDateTime::class.java)).thenReturn(now.plusDays(90))
        `when`(mockRs.getObject("last_used_at", OffsetDateTime::class.java)).thenReturn(null)
        `when`(mockRs.getLong("usage_count")).thenReturn(0L)

        val result = service.authenticateApiKey("ck_test_rawkey")

        assertNotNull(result)
        assertEquals(keyId, result!!.keyId)
    }

    @Test
    fun withdrawalAuthenticationRejectsSupersededMobileCredentialsInSql() {
        `when`(mockRs.next()).thenReturn(false)
        val sql = ArgumentCaptor.forClass(String::class.java)

        assertNull(service.authenticateWithdrawalApiKey("ck_withdrawal_retry", UUID.randomUUID()))

        verify(mockConnection).prepareStatement(sql.capture())
        assertTrue(sql.value.contains("participant_id IS NOT NULL"))
        assertTrue(sql.value.contains("device_id IS NOT NULL"))
        assertTrue(sql.value.contains("mobile_withdrawal_requests"))
        assertTrue(sql.value.contains("request_id = ?"))
        assertTrue(sql.value.contains("candidate.revoked = false"))
        assertTrue(sql.value.contains("NOT EXISTS"))
        assertTrue(sql.value.contains("active.revoked = false"))
    }

    @Test
    fun firstWithdrawalClaimsParticipantInsideTheReceiptTransaction() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-claim"
        val deviceId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(mockConnection.autoCommit).thenReturn(false)
        `when`(mockRs.next()).thenReturn(true, false, true)
        `when`(mockRs.getBoolean("revoked")).thenReturn(false)
        `when`(mockRs.getString("participation_status")).thenReturn("ENROLLED")

        val intent = service.bindWithdrawalIntent(mockConnection, studyId, participantId, deviceId, keyId, requestId)

        assertFalse(intent.alreadyWithdrawn)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(mockConnection, Mockito.times(7)).prepareStatement(sql.capture())
        val statements = sql.allValues
        assertTrue(statements[0].contains("pg_advisory_xact_lock"))
        assertTrue(statements[0].contains("|| ':' ||"))
        assertTrue(statements[1].contains("FROM api_keys"))
        assertTrue(statements[2].contains("mobile_withdrawal_requests"))
        assertTrue(statements[3].contains("FROM study_participants"))
        assertTrue(statements[4].contains("INSERT INTO mobile_withdrawal_requests"))
        assertTrue(statements[5].contains("SET participation_status = 'NOT_ENROLLED'"))
        assertTrue(statements[6].contains("SET revoked = true"))
    }

    @Test
    fun losingWithdrawalPersistsReplayReceiptWithoutClaimingAnotherPurge() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-loser"
        val deviceId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(mockConnection.autoCommit).thenReturn(false)
        `when`(mockRs.next()).thenReturn(true, false, true)
        `when`(mockRs.getBoolean("revoked")).thenReturn(false)
        `when`(mockRs.getString("participation_status")).thenReturn("NOT_ENROLLED")

        val intent = service.bindWithdrawalIntent(mockConnection, studyId, participantId, deviceId, keyId, requestId)

        assertTrue(intent.alreadyWithdrawn)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(mockConnection, Mockito.times(6)).prepareStatement(sql.capture())
        assertTrue(sql.allValues.none { it.contains("SET participation_status = 'NOT_ENROLLED'") })
        assertTrue(sql.allValues.last().contains("SET revoked = true"))
    }

    @Test
    fun exactWithdrawalReceiptReplaySkipsStatusSnapshotAndReturnsOriginalOwnership() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-replay"
        val deviceId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(mockConnection.autoCommit).thenReturn(false)
        `when`(mockRs.next()).thenReturn(true, true)
        `when`(mockRs.getBoolean("revoked")).thenReturn(true)
        `when`(mockRs.getObject("request_id", UUID::class.java)).thenReturn(requestId)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getString("participant_id")).thenReturn(participantId)
        `when`(mockRs.getObject("device_id", UUID::class.java)).thenReturn(deviceId)
        `when`(mockRs.getBoolean("already_withdrawn")).thenReturn(false)

        val intent = service.bindWithdrawalIntent(mockConnection, studyId, participantId, deviceId, keyId, requestId)

        assertFalse(intent.alreadyWithdrawn)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(mockConnection, Mockito.times(4)).prepareStatement(sql.capture())
        assertTrue(sql.allValues.none { it.contains("FROM study_participants") })
        assertTrue(sql.allValues.last().contains("SET revoked = true"))
    }

    // --- Key format tests ---

    @Test
    fun testCreateApiKeyRawKeyHasExpectedFormat() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ApiKeyCreateRequest(name = "Format Test", scope = ApiKeyScope.READ_ONLY)
        val result = service.createApiKey(UUID.randomUUID(), "user", request)

        // Raw key format: "ck_<8-char-prefix>_<32-char-random>"
        assertTrue(result.rawKey.startsWith("ck_"))
        val parts = result.rawKey.split("_")
        assertEquals(3, parts.size)
        assertEquals("ck", parts[0])
        assertEquals(8, parts[1].length)
    }

    @Test
    fun testCreateApiKeyDefaultExpiration() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        val request = ApiKeyCreateRequest(name = "Default Exp", scope = ApiKeyScope.READ_ONLY)
        val result = service.createApiKey(UUID.randomUUID(), "user", request)

        // Default 90 days
        val expectedMinExpiry = OffsetDateTime.now().plusDays(89)
        assertTrue(result.info.expiresAt.isAfter(expectedMinExpiry))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateApiKeyRejectsBlankNameAtServiceLayer() {
        service.createApiKey(
            UUID.randomUUID(),
            "user",
            ApiKeyCreateRequest(name = " ", scope = ApiKeyScope.READ_ONLY)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateApiKeyRejectsOversizedNameAtServiceLayer() {
        service.createApiKey(
            UUID.randomUUID(),
            "user",
            ApiKeyCreateRequest(name = "x".repeat(256), scope = ApiKeyScope.READ_ONLY)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateApiKeyRejectsNonPositiveExpirationAtServiceLayer() {
        service.createApiKey(
            UUID.randomUUID(),
            "user",
            ApiKeyCreateRequest(name = "Key", scope = ApiKeyScope.READ_ONLY, expiresInDays = 0)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateApiKeyRejectsWriteExpirationOverNinetyDays() {
        service.createApiKey(
            UUID.randomUUID(),
            "user",
            ApiKeyCreateRequest(name = "Write Key", scope = ApiKeyScope.WRITE, expiresInDays = 91)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateApiKeyRejectsAdminExpirationOverThirtyDays() {
        service.createApiKey(
            UUID.randomUUID(),
            "user",
            ApiKeyCreateRequest(name = "Admin Key", scope = ApiKeyScope.ADMIN, expiresInDays = 31)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateMobileApiKeyRejectsExpirationOverOneYear() {
        service.createMobileApiKey(
            studyId = UUID.randomUUID(),
            participantId = "participant-1",
            deviceId = UUID.randomUUID(),
            expiresInDays = 366
        )
    }

    @Test
    fun proposedMobileKeyMustUseTheCompatibleHighEntropyFormat() {
        assertTrue(ApiKeyService.isValidProposedMobileApiKey("ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"))
        assertTrue(!ApiKeyService.isValidProposedMobileApiKey("ck_76543210_0123456789ABCDEFGHIJKLMNOPQRSTUV"))
        assertTrue(!ApiKeyService.isValidProposedMobileApiKey("ck_01234567_short"))
        assertTrue(!ApiKeyService.isValidProposedMobileApiKey("ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTU-"))
    }

    @Test
    fun proposedMobileKeyIsReturnedExactlyButNeverPersistedInPlaintext() {
        val studyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        `when`(idGenerationService.getNextId()).thenReturn(keyId)
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getBoolean(1)).thenReturn(true)

        val result = service.installMobileApiKey(studyId, "participant-1", deviceId, attemptId, proposed)

        assertEquals(proposed, result.rawKey)
        assertEquals(keyId, result.keyId)
        verify(mockPs, never()).setString(Mockito.anyInt(), Mockito.eq(proposed))
    }

    @Test
    fun lostResponseReplayReturnsTheAlreadyInstalledProposedKeyWithoutRotatingIt() {
        val studyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        stubExistingMobileKey(
            studyId,
            "participant-1",
            deviceId,
            keyId,
            checkNotNull(ApiKeyService.proposedMobileApiKeyHash(proposed)),
        )
        `when`(mockRs.next()).thenReturn(true, true)
        `when`(mockRs.getBoolean(1)).thenReturn(true)

        val result = service.installMobileApiKey(studyId, "participant-1", deviceId, attemptId, proposed)

        assertEquals(keyId, result.keyId)
        assertEquals(proposed, result.rawKey)
        verify(mockPs, never()).executeUpdate()
        verify(idGenerationService, never()).getNextId()
    }

    @Test
    fun freshInvitationCanReplaceAnOrphanedKeyForTheSameDevice() {
        val studyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val replacementKeyId = UUID.randomUUID()
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        stubExistingMobileKey(
            studyId,
            "participant-1",
            deviceId,
            UUID.randomUUID(),
            "f".repeat(64),
        )
        `when`(mockRs.next()).thenReturn(true, true)
        `when`(mockRs.getBoolean(1)).thenReturn(true)
        `when`(idGenerationService.getNextId()).thenReturn(replacementKeyId)

        val result = service.installMobileApiKey(studyId, "participant-1", deviceId, attemptId, proposed)

        assertEquals(replacementKeyId, result.keyId)
        assertEquals(proposed, result.rawKey)
        verify(mockPs, Mockito.times(2)).executeUpdate()
        verify(mockPs, never()).setString(Mockito.anyInt(), Mockito.eq(proposed))
    }

    @Test
    fun supersededEnrollmentAttemptCannotInstallItsProposedKey() {
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getBoolean(1)).thenReturn(false)

        try {
            service.installMobileApiKey(
                UUID.randomUUID(),
                "participant-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                proposed,
            )
            throw AssertionError("Expected the superseded attempt to be rejected")
        } catch (_: InvalidEnrollmentAttemptException) {
            // Expected: the receipt is no longer active at key-install time.
        }

        verify(mockPs, never()).executeUpdate()
        verify(mockPs, never()).setString(Mockito.anyInt(), Mockito.eq(proposed))
    }

    private fun stubExistingMobileKey(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
        keyHash: String,
    ) {
        val now = OffsetDateTime.now()
        `when`(mockRs.getString("key_hash")).thenReturn(keyHash)
        `when`(mockRs.getObject("key_id", UUID::class.java)).thenReturn(keyId)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getString("key_prefix")).thenReturn("01234567")
        `when`(mockRs.getString("name")).thenReturn("device-${deviceId.toString().take(8)}")
        `when`(mockRs.getString("scope")).thenReturn(ApiKeyScope.WRITE.name)
        `when`(mockRs.getObject("created_at", OffsetDateTime::class.java)).thenReturn(now)
        `when`(mockRs.getObject("expires_at", OffsetDateTime::class.java)).thenReturn(now.plusDays(365))
        `when`(mockRs.getObject("last_used_at", OffsetDateTime::class.java)).thenReturn(null)
        `when`(mockRs.getLong("usage_count")).thenReturn(0L)
        `when`(mockRs.getString("participant_id")).thenReturn(participantId)
        `when`(mockRs.getObject("device_id", UUID::class.java)).thenReturn(deviceId)
    }
}
