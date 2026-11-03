package com.openlattice.chronicle.configuration

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier

class MobileApiSecurityConfigTest {

    @Test
    fun `public defaults keep shared secret signing and the internal bypass disabled`() {
        val configuration = MobileSecurityConfiguration()

        assertFalse(configuration.enabled)
        assertFalse(configuration.signingRequired)
        assertTrue(configuration.signingSecret.isBlank())
        assertTrue(configuration.internalWebSecret.isBlank())
    }

    @Test
    fun `enforced shared secret signing requires an internal web proxy secret`() {
        val failure = startupFailure(MobileSecurityConfiguration(
            enabled = true,
            signingSecret = "controlled-legacy-signing-secret-32-bytes!!",
            signingRequired = true,
            internalWebSecret = "",
        ))

        assertTrue(failure.message.orEmpty().contains("internal-web-secret is blank"))
    }

    @Test
    fun `a nonce ttl shorter than the timestamp window is rejected at startup`() {
        // A request captured within the ten minute timestamp window replays cleanly once its
        // one minute nonce entry has expired.
        val failure = startupFailure(MobileSecurityConfiguration(
            enabled = true,
            signingSecret = "controlled-legacy-signing-secret-32-bytes!!",
            signingRequired = true,
            internalWebSecret = "controlled-internal-web-secret-32-bytes!!!",
            maxRequestAgeMinutes = 10,
            nonceTtlMinutes = 1,
        ))

        assertTrue(failure.message.orEmpty().contains("nonce-ttl-minutes"))
    }

    private fun startupFailure(configuration: MobileSecurityConfiguration): IllegalStateException =
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(MobileSecurityConfiguration::class.java, Supplier {
                configuration.copy(publicBaseUrl = "https://chronicle.example")
            })
            context.registerBean(HazelcastInstance::class.java,
                Supplier { mock(HazelcastInstance::class.java, RETURNS_DEEP_STUBS) })
            context.registerBean(ParticipantFormAccessService::class.java,
                Supplier { mock(ParticipantFormAccessService::class.java) })
            context.registerBean(AuditService::class.java, Supplier { mock(AuditService::class.java) })
            context.registerBean(StudyService::class.java, Supplier { mock(StudyService::class.java) })
            context.registerBean(EnrollmentManager::class.java, Supplier { mock(EnrollmentManager::class.java) })
            context.registerBean(StudyLifecycleService::class.java, Supplier { mock(StudyLifecycleService::class.java) })
            context.register(MobileApiSecurityConfig::class.java)

            val failure = assertThrows(BeanCreationException::class.java) { context.refresh() }
            assertEquals("mobileApiSignatureFilter", failure.beanName)
            val cause = failure.mostSpecificCause
            assertTrue(cause.toString(), cause is IllegalStateException)
            cause as IllegalStateException
        }
}
