package com.openlattice.chronicle.services.candidates

import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.security.InvalidParameterException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Mock-based unit tests for [CandidateService]. Mocks JDBC + dependency managers so
 * the count/exists/register paths are exercised without Postgres or Hazelcast,
 * letting PIT count mutant kills (the integration test [CandidateApiTests] is
 * excluded from the minion).
 */
class CandidateServiceMutationTest {

    private val storageResolver: StorageResolver = mock()
    private val authorizationService: AuthorizationManager = mock()
    private val idGenerationService: HazelcastIdGenerationService = mock()

    private val service = CandidateService(storageResolver, authorizationService, idGenerationService)

    @After
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticate() {
        // The 3-arg constructor marks the token authenticated=true (trusted).
        val auth = UsernamePasswordAuthenticationToken(
            "registrar-1", "creds", listOf(SimpleGrantedAuthority("USER|registrar-1"))
        )
        SecurityContextHolder.getContext().authentication = auth
    }

    /** Wires connection.prepareStatement(sql) -> ps -> executeQuery() -> rs returning a single count row. */
    private fun connectionReturningCount(count: Long): Connection {
        val rs: ResultSet = mock()
        whenever(rs.next()).doReturn(true, false)
        whenever(rs.getLong("count")).doReturn(count)

        val ps: PreparedStatement = mock()
        whenever(ps.executeQuery()).doReturn(rs)

        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(ps)
        return connection
    }

    // ---- countCandidates / exists ----

    @Test
    fun `exists is true when count is positive`() {
        val connection = connectionReturningCount(1L)
        assertTrue(service.exists(connection, UUID.randomUUID()))
    }

    @Test
    fun `exists is false when count is zero`() {
        val connection = connectionReturningCount(0L)
        assertFalse(service.exists(connection, UUID.randomUUID()))
    }

    @Test
    fun `exists binds the candidate id as the first parameter`() {
        val candidateId = UUID.randomUUID()
        val rs: ResultSet = mock()
        whenever(rs.next()).doReturn(true)
        whenever(rs.getLong("count")).doReturn(3L)
        val ps: PreparedStatement = mock()
        whenever(ps.executeQuery()).doReturn(rs)
        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(ps)

        assertTrue(service.exists(connection, candidateId))

        verify(ps).setObject(1, candidateId)
        verify(ps).executeQuery()
    }

    @Test
    fun `exists returns false when no count row is returned`() {
        val rs: ResultSet = mock()
        whenever(rs.next()).doReturn(false) // empty result set -> count defaults to 0
        val ps: PreparedStatement = mock()
        whenever(ps.executeQuery()).doReturn(rs)
        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(ps)

        assertFalse(service.exists(connection, UUID.randomUUID()))
    }

    // ---- registerCandidate ----

    @Test
    fun `registerCandidate with uninitialized id assigns a new id, inserts, and creates ACL`() {
        authenticate()
        val newId = UUID.randomUUID()
        whenever(idGenerationService.getNextId()).doReturn(newId)

        val insertPs: PreparedStatement = mock()
        whenever(insertPs.executeUpdate()).doReturn(1)
        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(insertPs)

        val candidate = Candidate(id = IdConstants.UNINITIALIZED.id)
        val result = service.registerCandidate(connection, candidate)

        assertEquals(newId, result)
        assertEquals(newId, candidate.id)
        // inserted the freshly-minted id
        verify(insertPs).setObject(1, newId)
        verify(insertPs).executeUpdate()
        // created an unnamed securable object of type Candidate keyed by the new id
        val connCaptor = argumentCaptor<Connection>()
        val aclCaptor = argumentCaptor<AclKey>()
        val typeCaptor = argumentCaptor<SecurableObjectType>()
        verify(authorizationService).createUnnamedSecurableObject(
            connCaptor.capture(),
            aclCaptor.capture(),
            any(),
            any(),
            typeCaptor.capture(),
            any(),
        )
        assertEquals(connection, connCaptor.firstValue)
        assertEquals(AclKey(newId), aclCaptor.firstValue)
        assertEquals(SecurableObjectType.Candidate, typeCaptor.firstValue)
    }

    @Test
    fun `registerCandidate with existing id that already exists returns that id without inserting`() {
        val existingId = UUID.randomUUID()
        val connection = connectionReturningCount(1L) // exists() -> true

        val candidate = Candidate(id = existingId)
        val result = service.registerCandidate(connection, candidate)

        assertEquals(existingId, result)
        verify(idGenerationService, never()).getNextId()
        verifyNoInteractions(authorizationService)
    }

    @Test
    fun `registerCandidate with existing id that does not exist throws InvalidParameterException`() {
        val unknownId = UUID.randomUUID()
        val connection = connectionReturningCount(0L) // exists() -> false

        val candidate = Candidate(id = unknownId)
        assertThrows(InvalidParameterException::class.java) {
            service.registerCandidate(connection, candidate)
        }
        verify(idGenerationService, never()).getNextId()
    }
}
