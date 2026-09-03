package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.authorization.processors.AuthorizationEntryProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.AbstractMap
import java.util.EnumSet
import java.util.UUID

class AceExpirationTest {

    private val aceKey = AceKey(
        AclKey(UUID.randomUUID()),
        Principal(PrincipalType.USER, "expiration-test-user")
    )
    private val permissions = EnumSet.of(Permission.READ, Permission.WRITE)
    private val expiration = OffsetDateTime.parse("2030-01-01T12:00:00Z")

    @Test
    fun aceIsActiveOnlyBeforeItsExpirationInstant() {
        val aceValue = aceValue(expiration)

        assertTrue(aceValue.isActiveAt(expiration.minusNanos(1)))
        assertFalse(aceValue.isActiveAt(expiration))
        assertFalse(aceValue.isActiveAt(expiration.plusNanos(1)))
    }

    @Test
    fun equalInstantWithDifferentOffsetIsStillExpired() {
        val sameInstantAtDifferentOffset = expiration.withOffsetSameInstant(
            java.time.ZoneOffset.ofHours(-6)
        )
        val predicate = ActiveAcePredicate(sameInstantAtDifferentOffset)

        assertFalse(predicate.apply(AbstractMap.SimpleEntry(aceKey, aceValue(expiration))))
    }

    @Test
    fun permanentPredicateRejectsFiniteFutureExpiration() {
        val predicate = PermanentAcePredicate()
        val permanent = AceValue(
            EnumSet.of(Permission.OWNER),
            SecurableObjectType.Study,
            OffsetDateTime.MAX,
        )
        val finite = AceValue(
            EnumSet.of(Permission.OWNER),
            SecurableObjectType.Study,
            OffsetDateTime.now().plusYears(100),
        )

        assertTrue(predicate.apply(AbstractMap.SimpleEntry(aceKey, permanent)))
        assertFalse(predicate.apply(AbstractMap.SimpleEntry(aceKey, finite)))
    }

    @Test
    fun authorizationEntryProcessorDeniesAtExpirationBoundary() {
        val entry = AbstractMap.SimpleEntry<AceKey, AceValue?>(aceKey, aceValue(expiration))

        val beforeExpiration = AuthorizationEntryProcessor(expiration.minusNanos(1)).process(entry)
        val atExpiration = AuthorizationEntryProcessor(expiration).process(entry)

        assertTrue(beforeExpiration.containsAll(permissions))
        assertTrue(atExpiration.isEmpty())
    }

    @Test
    fun authorizationEntryProcessorDeniesMissingAce() {
        val entry = AbstractMap.SimpleEntry<AceKey, AceValue?>(aceKey, null)

        assertTrue(AuthorizationEntryProcessor(expiration).process(entry).isEmpty())
    }

    private fun aceValue(expirationDate: OffsetDateTime): AceValue {
        return AceValue(
            EnumSet.copyOf(permissions),
            SecurableObjectType.Study,
            expirationDate
        )
    }
}
