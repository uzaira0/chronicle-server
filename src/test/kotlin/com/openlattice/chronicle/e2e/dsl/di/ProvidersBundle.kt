package com.openlattice.chronicle.e2e.dsl.di

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.util.e2eTitle
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
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
            val uploadService = context.getBean(AppDataUploadService::class.java)

            val authProvider = TestingTokenAuthProvider()
            return ProvidersBundle(
                auth = authProvider,
                api = RetrofitChronicleApiClient(authProvider),
                data = DefaultTestDataProvider(),
                flusher = InProcessFlusher(uploadService),
            )
        }
    }
}

class TestingTokenAuthProvider : AuthProvider {
    override fun tokenFor(userId: String): String {
        return ChronicleServerTests.activeFixtureToken(Principal(PrincipalType.USER, userId))
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
    // Upload stats persist through JDBC; PostgreSQL timestamps have microsecond precision.
    override fun usageEvents(studyId: UUID, participantId: String, count: Int): ChronicleData =
        ChronicleData(TestDataFactory.chronicleUsageEvents(studyId, participantId, count)
            .filterIsInstance<com.openlattice.chronicle.android.ChronicleUsageEvent>()
            .map { it.copy(timestamp = it.timestamp.truncatedTo(java.time.temporal.ChronoUnit.MICROS)) })
}

class InProcessFlusher(private val uploadService: AppDataUploadService) : DataPipelineFlusher {
    override fun flush(studyId: UUID, participantId: String) =
        uploadService.moveToEventStorage(studyId, participantId)
}
