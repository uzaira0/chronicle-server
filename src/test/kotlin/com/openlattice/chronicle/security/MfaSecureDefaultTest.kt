package com.openlattice.chronicle.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.ChronicleOidcConfiguration
import com.openlattice.chronicle.configuration.RequestHardeningFilters
import com.openlattice.chronicle.configuration.SecurityHardeningConfig
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.pods.AuthenticationServicesPod
import com.openlattice.chronicle.pods.REQUIRE_MFA_PROPERTY
import com.openlattice.chronicle.pods.servlet.ChronicleServerSecurityPod
import com.openlattice.chronicle.pods.servlet.METRICS_PASSWORD_PROPERTY
import com.openlattice.chronicle.pods.servlet.SecurityFilterRegistrationsPod
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.servlet.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.jwt.JwtValidationException
import java.time.Instant

class MfaSecureDefaultTest {
    private val client = ChronicleJwtClientConfiguration(
        issuer = "https://mfa-default.test",
        audience = "chronicle",
        secret = "mfa-default-test-signing-secret-0123456789",
    )

    @Test
    fun missingMfaPropertyDefaultsToEnforced() = withAuthContext { context ->
        val decoder = context.autowireCapableBeanFactory
            .createBean(ChronicleServerSecurityPod::class.java).jwtDecoder()

        assertThrows(JwtValidationException::class.java) { decoder.decode(token()) }
        assertEquals("researcher", decoder.decode(token(amr = listOf("mfa"))).subject)
    }

    @Test
    fun approvedAcrAllowlistDefaultsToEmpty() = withAuthContext { context ->
        val decoder = context.autowireCapableBeanFactory
            .createBean(ChronicleServerSecurityPod::class.java).jwtDecoder()

        assertThrows(JwtValidationException::class.java) {
            decoder.decode(token(acr = "https://refeds.org/profile/mfa"))
        }
        assertEquals("researcher", decoder.decode(token(amr = listOf("pwd", "otp"))).subject)
    }

    @Test
    fun mfaWithOnlyThePasswordLoginStopsStartupNamingTheSetting() = withAuthContext(auth = passwordLogin()) { context ->
        val failure = assertThrows(BeanCreationException::class.java) {
            context.autowireCapableBeanFactory.createBean(ChronicleServerSecurityPod::class.java)
        }
        assertTrue(failure.mostSpecificCause.message.orEmpty().contains("REQUIRE_MFA=false"))
    }

    @Test
    fun passwordLoginStartsWhenMfaIsOffOrAnIdentityProviderIsConfigured() {
        withAuthContext(mapOf(REQUIRE_MFA_PROPERTY to "false"), passwordLogin()) { context ->
            context.autowireCapableBeanFactory.createBean(ChronicleServerSecurityPod::class.java)
        }
        withAuthContext(auth = passwordLogin().copy(oidc = ChronicleOidcConfiguration(enabled = true))) { context ->
            context.autowireCapableBeanFactory.createBean(ChronicleServerSecurityPod::class.java)
        }
    }

    private fun passwordLogin() = ChronicleAuthConfiguration(
        configurations = setOf(client),
        dashboardPasswordHash = "\$2a\$10\$" + "x".repeat(53),
    )

    private fun token(amr: List<String> = emptyList(), acr: String? = null): String = JWT.create()
        .withSubject("researcher")
        .withIssuer(client.issuer)
        .withAudience(client.audience)
        .withExpiresAt(Instant.now().plusSeconds(600))
        .withArrayClaim("amr", amr.toTypedArray())
        .withClaim("acr", acr)
        .sign(Algorithm.HMAC256(client.secret))

    private fun withAuthContext(
        properties: Map<String, String> = emptyMap(),
        auth: ChronicleAuthConfiguration = ChronicleAuthConfiguration(configurations = setOf(client)),
        check: (AnnotationConfigApplicationContext) -> Unit,
    ) {
        AnnotationConfigApplicationContext().use { context ->
            context.environment = MockEnvironment().apply {
                setProperty(METRICS_PASSWORD_PROPERTY, "mfa-metrics-fixture-" + "x".repeat(32))
                properties.forEach { (key, value) -> setProperty(key, value) }
            }
            val factory = context.beanFactory
            factory.registerSingleton("chronicleAuthConfiguration", auth)
            factory.registerSingleton("storageResolver", Mockito.mock(StorageResolver::class.java, Mockito.RETURNS_DEEP_STUBS))
            factory.registerSingleton("hazelcast", Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS))
            factory.registerSingleton("parameterPollutionFilter", Mockito.mock(Filter::class.java))
            for (type in listOf(
                SecurityHardeningConfig::class.java,
                RequestHardeningFilters::class.java,
                StudyAuthorizationService::class.java,
                AuditService::class.java,
                CreatorPrincipalResolver::class.java,
                ParticipantFormAccessService::class.java,
                HazelcastIdGenerationService::class.java,
                AuditingManager::class.java,
            )) {
                factory.registerSingleton(type.simpleName, Mockito.mock(type))
            }
            context.register(AuthenticationServicesPod::class.java, SecurityFilterRegistrationsPod::class.java)
            context.addBeanFactoryPostProcessor { beans ->
                beans.beanDefinitionNames.forEach { beans.getBeanDefinition(it).isLazyInit = true }
            }
            context.refresh()
            check(context)
        }
    }
}
