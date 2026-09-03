package com.openlattice.chronicle.security

import com.openlattice.chronicle.pods.ChronicleServerServicesPod
import com.openlattice.chronicle.pods.servlet.ChronicleServerSecurityPod
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.beans.factory.annotation.Value

class MfaSecureDefaultTest {

    @Test
    fun missingMfaPropertyDefaultsToEnforced() {
        val field = ChronicleServerSecurityPod::class.java.getDeclaredField("requireMfa")
        val propertyBinding = field.getAnnotation(Value::class.java)

        assertEquals("\${chronicle.security.require-mfa:true}", propertyBinding.value)
    }

    @Test
    fun refreshTokensShareTheSecureMfaDefault() {
        val field = ChronicleServerServicesPod::class.java.getDeclaredField("requireMfa")
        val propertyBinding = field.getAnnotation(Value::class.java)

        assertEquals("\${chronicle.security.require-mfa:true}", propertyBinding.value)
    }

    @Test
    fun approvedAcrAllowlistDefaultsToEmpty() {
        val field = ChronicleServerSecurityPod::class.java
            .getDeclaredField("approvedMfaAcrValues")
        val propertyBinding = field.getAnnotation(Value::class.java)

        assertEquals(
            "\${chronicle.security.approved-mfa-acr-values:}",
            propertyBinding.value,
        )
    }
}
