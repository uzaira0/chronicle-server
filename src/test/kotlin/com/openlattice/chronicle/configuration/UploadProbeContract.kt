package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.controllers.RequestErrorResponses
import com.openlattice.chronicle.util.API_KEY_HEADER
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Generated inputs let live probes use the same bound and rejection code as the server. */
internal data class MobileProbeHeaders(
    val randomDeviceId: String = MobileApiSignatureFilter.RANDOM_DEVICE_ID_HEADER,
    val apiKey: String = API_KEY_HEADER,
    val enrollmentCode: String = MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE,
    val manifestDigest: String = MobileApiSignatureFilter.HEADER_MANIFEST_DIGEST,
    val enrollmentAttemptId: String = MobileApiSignatureFilter.HEADER_ENROLLMENT_ATTEMPT_ID,
    val proposedApiKey: String = MobileApiSignatureFilter.HEADER_PROPOSED_API_KEY,
    val signature: String = MobileApiSignatureFilter.HEADER_SIGNATURE,
    val timestamp: String = MobileApiSignatureFilter.HEADER_TIMESTAMP,
    val nonce: String = MobileApiSignatureFilter.HEADER_NONCE,
)

internal data class UploadProbeContract(
    val maxNestingDepth: Int,
    val constraintErrorCode: String,
    val mobileHeaders: MobileProbeHeaders = MobileProbeHeaders(),
) {
    companion object {
        fun current(): UploadProbeContract = UploadProbeContract(
            JacksonSecurityConfig().secureObjectMapper().factory.streamReadConstraints().maxNestingDepth,
            RequestErrorResponses.JSON_CONSTRAINT_EXCEEDED,
        )

        /** Generated outputs, relative to the chronicle-server project directory. */
        const val CONTRACT_PATH = "src/test/resources/upload-probe-contract.json"
        const val MOBILE_HEADERS_PATH = "../chronicle-web/e2e/dsl/mobile-headers.json"

        /** Run by `generateUploadProbeContract`, whose working directory is the project directory. */
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.isEmpty()) { "No arguments: writes $CONTRACT_PATH and $MOBILE_HEADERS_PATH" }
            val mapper = JacksonSecurityConfig().secureObjectMapper()
            File(CONTRACT_PATH).writeText(mapper.writeValueAsString(current()) + "\n")
            File(MOBILE_HEADERS_PATH).writeText(mapper.writeValueAsString(current().mobileHeaders) + "\n")
        }
    }
}

class UploadProbeContractTest {
    @Test
    fun generatedProbeInputsMatchProductionConstraints() {
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        val saved = mapper.readTree(File(UploadProbeContract.CONTRACT_PATH))
        assertEquals(mapper.readTree(mapper.writeValueAsString(UploadProbeContract.current())), saved)
    }
}
