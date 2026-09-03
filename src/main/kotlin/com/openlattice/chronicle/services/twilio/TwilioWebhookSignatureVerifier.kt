package com.openlattice.chronicle.services.twilio

import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.notifications.NotificationApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.SortedMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public open class TwilioWebhookSignatureVerifier(
    private val twilioConfiguration: TwilioConfiguration,
) {
    private val callbackUrl: String =
        "${twilioConfiguration.callbackBaseUrl}${NotificationApi.BASE}${NotificationApi.STATUS_PATH}"

    public fun isCurrentRequestValid(): Boolean {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            ?: return false
        return isValid(request)
    }

    public fun isValid(request: HttpServletRequest): Boolean {
        if (!twilioConfiguration.enabled) return false
        val signature = request.getHeader(TWILIO_SIGNATURE_HEADER)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val params = request.parameterMap.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        return isValid(callbackUrl, params, signature, twilioConfiguration.token)
    }

    public companion object {
        public const val TWILIO_SIGNATURE_HEADER: String = "X-Twilio-Signature"

        public fun isValid(
            url: String,
            params: Map<String, String>,
            signature: String,
            authToken: String,
        ): Boolean {
            val expected = computeSignature(url, params.toSortedMap(), authToken)
            return MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                signature.toByteArray(StandardCharsets.US_ASCII)
            )
        }

        public fun computeSignature(
            url: String,
            params: SortedMap<String, String>,
            authToken: String,
        ): String {
            val signedPayload = buildString {
                append(url)
                params.forEach { (name, value) ->
                    append(name)
                    append(value)
                }
            }
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(authToken.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal(signedPayload.toByteArray(StandardCharsets.UTF_8)))
        }
    }
}
