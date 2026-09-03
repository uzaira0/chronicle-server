package com.openlattice.chronicle.services.twilio

import com.openlattice.chronicle.configuration.TwilioConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.TreeMap

class TwilioWebhookSignatureVerifierTest {

    private val config = TwilioConfiguration(
        enabled = true,
        sid = "AC123",
        token = "secret-token",
        defaultFromPhone = "+15551234567",
        callbackBaseUrl = "https://study.example.org"
    )

    @Test
    fun testValidSignatureAccepted() {
        val verifier = TwilioWebhookSignatureVerifier(config)
        val request = signedRequest(
            mapOf(
                "MessageSid" to "SM123",
                "MessageStatus" to "delivered"
            )
        )

        assertTrue(verifier.isValid(request))
    }

    @Test
    fun testMissingSignatureRejected() {
        val verifier = TwilioWebhookSignatureVerifier(config)
        val request = MockHttpServletRequest("POST", "/chronicle/v3/notification/status")
        request.addParameter("MessageSid", "SM123")

        assertFalse(verifier.isValid(request))
    }

    @Test
    fun testTamperedParameterRejected() {
        val verifier = TwilioWebhookSignatureVerifier(config)
        val request = signedRequest(mapOf("MessageSid" to "SM123", "MessageStatus" to "delivered"))
        request.setParameter("MessageStatus", "failed")

        assertFalse(verifier.isValid(request))
    }

    private fun signedRequest(params: Map<String, String>): MockHttpServletRequest {
        val request = MockHttpServletRequest("POST", "/chronicle/v3/notification/status")
        params.forEach { (name, value) -> request.addParameter(name, value) }
        val signature = TwilioWebhookSignatureVerifier.computeSignature(
            "https://study.example.org/chronicle/v3/notification/status",
            TreeMap(params),
            config.token
        )
        request.addHeader(TwilioWebhookSignatureVerifier.TWILIO_SIGNATURE_HEADER, signature)
        return request
    }
}
