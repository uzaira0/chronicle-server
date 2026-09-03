package com.openlattice.chronicle.e2e.dsl.di

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.util.e2eTitle
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.users.ConfiguredUserListingService
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.springframework.context.ApplicationContext
import java.util.UUID

class ProvidersBundle(
    val auth: AuthProvider,
    val api: ChronicleApiClient,
    val data: TestDataProvider,
    val flusher: DataPipelineFlusher,
) {
    companion object {
        fun fromSpringContext(context: ApplicationContext): ProvidersBundle {
            val userService = context.getBean(ConfiguredUserListingService::class.java)
            val uploadService = context.getBean(AppDataUploadService::class.java)

            val authProvider = TestingTokenAuthProvider(userService)
            return ProvidersBundle(
                auth = authProvider,
                api = RetrofitChronicleApiClient(authProvider),
                data = DefaultTestDataProvider(),
                flusher = InProcessFlusher(uploadService),
            )
        }
    }
}

class TestingTokenAuthProvider(
    private val userService: ConfiguredUserListingService,
) : AuthProvider {
    override fun tokenFor(userId: String): String {
        return userService.jwtTokens.getValue(userId).first()
    }
}

class RetrofitChronicleApiClient(private val auth: AuthProvider) : ChronicleApiClient {
    override fun clientFor(userId: String): ChronicleClient = ChronicleClient { auth.tokenFor(userId) }
}

class DefaultTestDataProvider : TestDataProvider {
    override fun study(tag: String): Study = Study(
        title = e2eTitle(tag),
        contact = "e2e@openlattice.com",
    )
    override fun participant(): Participant = TestDataFactory.participant()
    override fun androidDevice(): AndroidDevice = TestDataFactory.androidDevice()
    override fun usageEvents(studyId: UUID, participantId: String, count: Int): ChronicleData =
        TestDataFactory.chronicleUsageEvents(studyId, participantId, count)
}

class InProcessFlusher(private val uploadService: AppDataUploadService) : DataPipelineFlusher {
    override fun flush(studyId: UUID, participantId: String) =
        uploadService.moveToEventStorage(studyId, participantId)
}
