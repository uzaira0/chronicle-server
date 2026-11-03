package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.CreateReceiptFilter
import com.openlattice.chronicle.filters.RLSContextFilter
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.EnumSet
import java.util.UUID
import java.util.stream.Stream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/** Real request filters and receipt SQL; key and ACL lookup stores are external seams. */
class ReceiptCredentialIsolationTest : RLSConnectionFixture() {
    private enum class Credential { USER, API_KEY }

    @Test fun apiKeyReceiptCannotBeReadBySameSubjectJwt() = assertCrossRead(Credential.API_KEY, Credential.USER)
    @Test fun jwtReceiptCannotBeReadBySameTextApiKey() = assertCrossRead(Credential.USER, Credential.API_KEY)
    @Test fun apiKeyReceiptCannotBeReplayedBySameSubjectJwt() = assertCrossReplay(Credential.API_KEY, Credential.USER)
    @Test fun jwtReceiptCannotBeReplayedBySameTextApiKey() = assertCrossReplay(Credential.USER, Credential.API_KEY)

    @Test fun sameCredentialReceiptsReplayWithoutAnotherCreate() {
        for (kind in Credential.entries) {
            val fixture = fixture()
            val created = fixture.create(kind)
            val replay = fixture.create(kind)
            assertEquals(201, created.status)
            assertEquals(created.contentAsString, replay.contentAsString)
            assertEquals("true", replay.getHeader(CreateReceiptFilter.REPLAYED_HEADER))
            assertEquals(1, fixture.actions)
        }
    }

    @Test fun legacyRawReceiptCannotBeClaimedByEitherCredential() {
        val fixture = fixture()
        val ciphertext = legacyCiphertext(fixture.owner, fixture.route, "legacy-intent")
        hds.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO researcher_create_receipts
                    (principal_id, route, idempotency_key, request_hash, response_status, response_type, sealed_response)
                VALUES (?, ?, 'legacy-intent', ?, 201, 'application/json', ?)
            """.trimIndent()).use { statement ->
                statement.setString(1, fixture.owner); statement.setString(2, fixture.route)
                statement.setBytes(3, MessageDigest.getInstance("SHA-256").digest(BODY.toByteArray()))
                statement.setString(4, ciphertext); statement.executeUpdate()
            }
        }
        for (kind in Credential.entries) {
            val response = fixture.create(kind, "legacy-intent")
            assertEquals("Ambiguous historical ownership must fail closed", 409, response.status)
            assertEquals(0, fixture.actions)
            assertLegacyReceiptUnchanged(ciphertext)
        }
    }

    private fun assertLegacyReceiptUnchanged(ciphertext: String) {
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT owner_kind, sealed_response FROM researcher_create_receipts").use { rows ->
                    assertTrue(rows.next()); assertEquals("LEGACY", rows.getString(1))
                    assertEquals(ciphertext, rows.getString(2)); assertFalse(rows.next())
                }
            }
        }
    }

    @Test fun receiptOwnerKindIsAppliedClearedAndRestored() {
        val fixture = fixture()
        for (kind in Credential.entries) {
            fixture.inRequest(kind) {
                fixture.wrapped.connection.use { connection ->
                    assertEquals(kind.name, currentSetting(connection, "app.receipt_owner_kind"))
                    connection.autoCommit = false
                    RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
                        assertEquals("", currentSetting(connection, "app.receipt_owner_kind"))
                    }
                    assertEquals(kind.name, currentSetting(connection, "app.receipt_owner_kind"))
                    RLSConnectionCustomizer.withWithdrawalEnrollmentContext(connection) {
                        assertEquals("", currentSetting(connection, "app.receipt_owner_kind"))
                    }
                    assertEquals(kind.name, currentSetting(connection, "app.receipt_owner_kind"))
                    connection.rollback(); connection.autoCommit = true
                }
            }
            hds.connection.use { connection ->
                assertEquals("", currentSetting(connection, "app.receipt_owner_kind"))
            }
            SecurityContextHolder.clearContext()
            fixture.wrapped.connection.use { connection ->
                assertEquals("", currentSetting(connection, "app.receipt_owner_kind"))
            }
        }
    }

    private fun assertCrossRead(createdBy: Credential, other: Credential) {
        val fixture = fixture()
        assertEquals(201, fixture.create(createdBy).status)
        fixture.inRequest(createdBy) { assertEquals(1, fixture.receiptCount()) }
        fixture.inRequest(other) {
            assertEquals("Equal raw principal text must not cross credential domains", 0, fixture.receiptCount())
        }
    }

    private fun assertCrossReplay(createdBy: Credential, other: Credential) {
        val fixture = fixture()
        val created = fixture.create(createdBy)
        assertEquals(201, created.status)
        assertEquals("Another credential domain cannot claim this intent", 409, fixture.create(other).status)
        assertEquals(1, fixture.actions)
        val replay = fixture.create(createdBy)
        assertEquals(created.contentAsString, replay.contentAsString)
        assertEquals("true", replay.getHeader(CreateReceiptFilter.REPLAYED_HEADER))
        assertEquals(1, fixture.actions)
        assertEquals("A live permitted credential keeps normal creates", 201, fixture.create(other, "different-intent").status)
        assertEquals(2, fixture.actions)
    }

    private fun fixture(): ReceiptFixture {
        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        return ReceiptFixture(hds)
    }

    private fun legacyCiphertext(owner: String, route: String, intent: String): String {
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = MessageDigest.getInstance("SHA-256").digest(SECRET.toByteArray())
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$owner\u0000$route\u0000$intent".toByteArray())
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal("\"historical-result\"".toByteArray()))
    }

    private class ReceiptFixture(hds: HikariDataSource) {
        private val study = UUID.randomUUID()
        private val keyId = UUID.randomUUID()
        val owner = "apikey:$keyId"
        val route = "/v3/survey/$study/questionnaire"
        val wrapped = RLSDataSources.wrapUncachedRequestScoped(hds)
        var actions = 0
        private val authorization = Mockito.mock(AuthorizationManager::class.java)
        private val keyFilter: ApiKeyAuthenticationFilter
        private val rlsFilter: RLSContextFilter
        private val receipts: CreateReceiptFilter

        init {
            assertEquals(owner, ChronicleAuthUser(id = owner).toChronicleUserProfile().id)
            TestSecurityUtils.initPrincipalsIfNeeded(owner, false)
            Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
            Mockito.`when`(authorization.getAuthorizedObjectsOfType(
                any<Set<Principal>>(), any<SecurableObjectType>(), any<EnumSet<Permission>>(),
            )).thenAnswer { Stream.empty<AclKey>() }
            val storage = Mockito.mock(StorageResolver::class.java)
            Mockito.`when`(storage.getPlatformStorage()).thenReturn(wrapped)
            val keys = Mockito.mock(ApiKeyService::class.java)
            val now = OffsetDateTime.now()
            Mockito.`when`(keys.authenticateApiKey(RAW_KEY)).thenReturn(ApiKeyInfo(
                keyId, study, "probe000", "probe", ApiKeyScope.WRITE, now, now.plusDays(1), createdBy = "creator",
            ))
            keyFilter = ApiKeyAuthenticationFilter(keys, authorization) { setOf(Principal(PrincipalType.USER, "creator")) }
            rlsFilter = RLSContextFilter(RLSContextManager(authorization))
            receipts = CreateReceiptFilter(storage,
                StudyAuthorizationService(authorization, Mockito.mock(AuditingManager::class.java))) { SECRET }
        }

        fun create(kind: Credential, intent: String = "same-intent"): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/chronicle$route").apply {
                addHeader("Idempotency-Key", intent); setContent(BODY.toByteArray())
            }
            val response = MockHttpServletResponse()
            invoke(kind, request, response, FilterChain { req, res ->
                receipts.doFilter(req, res, FilterChain { _, actionResponse ->
                    actions++
                    (actionResponse as HttpServletResponse).apply {
                        status = 201; contentType = "application/json"; writer.write("\"result-$actions\"")
                    }
                })
            })
            return response
        }

        fun inRequest(kind: Credential, action: () -> Unit) {
            val response = MockHttpServletResponse()
            invoke(kind, MockHttpServletRequest("GET", "/chronicle/v3/study/$study"), response, FilterChain { _, _ ->
                val context = requireNotNull(RLSRequestContext.current())
                assertEquals(owner, context.principalId)
                assertFalse(context.isAdmin); assertFalse(context.isDeletionWorker)
                action()
            })
            assertEquals(200, response.status)
        }

        fun receiptCount(): Int = wrapped.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM researcher_create_receipts").use { rows ->
                    assertTrue(rows.next()); rows.getInt(1)
                }
            }
        }

        private fun invoke(kind: Credential, request: MockHttpServletRequest, response: MockHttpServletResponse, chain: FilterChain) {
            SecurityContextHolder.clearContext()
            if (kind == Credential.USER) {
                TestSecurityUtils.authenticate(owner)
                rlsFilter.doFilter(request, response, chain)
            } else {
                request.addHeader("X-Api-Key", RAW_KEY)
                keyFilter.doFilter(request, response, FilterChain { req, res -> rlsFilter.doFilter(req, res, chain) })
            }
            assertEquals(null, RLSRequestContext.current())
        }
    }

    private companion object {
        const val RAW_KEY = "synthetic-receipt-probe-key"
        const val SECRET = "synthetic-receipt-secret-over-32-characters"
        const val BODY = "{\"title\":\"same questionnaire intent\"}"
    }
}
