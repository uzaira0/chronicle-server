package com.openlattice.chronicle.services.enrollment

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.controllers.validatePlayCollectionPolicy
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeScope
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptBinding
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.study.EnrollmentManifest
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.util.DeviceIdUtils
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.UUID

/** Builds and digest-binds the authoritative disclosure shown before device enrollment. */
public open class EnrollmentManifestService(
    private val studyService: StudyService,
    private val participantFormAccessService: ParticipantFormAccessService,
    configuredPublicOrigin: String,
    private val lockedStudyLoader: (Connection, UUID) -> Study = ::loadLockedStudy,
) {
    private val publicOrigin: String = canonicalHttpsRootOrigin(configuredPublicOrigin)

    public open fun getPreview(
        studyId: UUID,
        participantId: String,
        enrollmentCode: String,
    ): EnrollmentPreviewResponse {
        val scope = participantFormAccessService.resolveEnrollmentAccessCode(
            enrollmentCode,
            studyId,
            participantId,
        ) ?: throw invalidEnrollmentCredential()
        return previewFor(scope)
    }

    /**
     * Binds a public invitation to one canonical request. Exact retries are authorized by the
     * durable receipt; any changed header, device body, disclosure, or proposed key is rejected.
     */
    @Suppress("LongParameterList")
    public open fun authorizeEnrollmentAttempt(
        studyId: UUID,
        participantId: String,
        enrollmentCode: String,
        acceptedManifestDigest: String,
        enrollmentAttemptId: String,
        sourceDeviceId: String,
        sourceDevice: SourceDevice,
        proposedApiKey: String,
    ): Boolean {
        val parsedAttempt = parseEnrollmentAttempt(
            acceptedManifestDigest,
            enrollmentAttemptId,
            sourceDeviceId,
            proposedApiKey,
        ) ?: return false
        val scope = participantFormAccessService.resolveEnrollmentAccessCodeForRequest(
            enrollmentCode,
            studyId,
            participantId,
        ) ?: return false
        val acceptedPreview = previewFor(scope)
        if (!secureDigestEquals(acceptedPreview.manifestDigest, acceptedManifestDigest)) return false
        val acceptedEvidence = consentEvidence(acceptedPreview)
        val binding = EnrollmentAttemptBinding(
            attemptId = parsedAttempt.attemptId,
            studyId = studyId,
            participantId = participantId,
            sourceDeviceHash = EnrollmentAttemptDigest.sha256Text(sourceDeviceId),
            deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId),
            manifestDigest = acceptedManifestDigest,
            requestHash = EnrollmentAttemptDigest.canonicalSourceDeviceHash(sourceDevice),
            proposedApiKeyHash = parsedAttempt.proposedKeyHash,
            enrollmentSettingsVersion = acceptedEvidence.settingsVersion,
            enrollmentDisclosureVersion = acceptedEvidence.disclosureVersion,
            enrollmentEnabledModules = acceptedEvidence.enabledModules,
            enrollmentRequiredModules = acceptedEvidence.requiredModules,
        )
        return participantFormAccessService.authorizeEnrollmentAttempt(
            enrollmentCode,
            studyId,
            participantId,
            binding,
        ) { connection, scope ->
            val lockedPreview = previewFor(scope, lockedStudyLoader(connection, scope.studyId))
            secureDigestEquals(lockedPreview.manifestDigest, acceptedManifestDigest) &&
                consentEvidence(lockedPreview) == acceptedEvidence
        }
    }

    private fun parseEnrollmentAttempt(
        acceptedManifestDigest: String,
        enrollmentAttemptId: String,
        sourceDeviceId: String,
        proposedApiKey: String,
    ): ParsedEnrollmentAttempt? {
        if (!EnrollmentManifestDigest.isDigest(acceptedManifestDigest) || sourceDeviceId.isBlank()) return null
        val attemptId = EnrollmentAttemptDigest.parseCanonicalUuid(enrollmentAttemptId) ?: return null
        val proposedKeyHash = ApiKeyService.proposedMobileApiKeyHash(proposedApiKey) ?: return null
        return ParsedEnrollmentAttempt(attemptId, proposedKeyHash)
    }

    private fun previewFor(scope: EnrollmentAccessCodeScope): EnrollmentPreviewResponse =
        previewFor(scope, studyService.getStudy(scope.studyId))

    private fun previewFor(scope: EnrollmentAccessCodeScope, study: Study): EnrollmentPreviewResponse {
        val policy = (study.settings[StudySettingType.ParticipantPolicy] as? StudyParticipantPolicy)
            ?.validate()
            ?: throw incompleteEnrollmentConfiguration("participant policy")
        val collectionSettings = study.settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
            ?: throw incompleteEnrollmentConfiguration("versioned DataCollection settings")
        validatePlayCollectionPolicy(collectionSettings)
        val manifest = EnrollmentManifest(
            serverOrigin = publicOrigin,
            studyId = scope.studyId,
            participantId = scope.participantId,
            studyTitle = study.title,
            studyDescription = study.description,
            participantPolicy = policy,
            collectionSettings = collectionSettings,
            settingsVersion = collectionSettings.settingsVersion,
            issuedAt = scope.issuedAt,
            expiresAt = scope.expiresAt,
        )
        return EnrollmentPreviewResponse(manifest, EnrollmentManifestDigest.compute(manifest))
    }

    private fun consentEvidence(preview: EnrollmentPreviewResponse): EnrollmentConsentEvidence {
        val collectionSettings = preview.manifest.collectionSettings
        val modules = collectionSettings.effectiveModules()
        return EnrollmentConsentEvidence(
            settingsVersion = preview.manifest.settingsVersion,
            disclosureVersion = preview.manifest.participantPolicy.version,
            enabledModules = collectionSettings.effectiveEnabledModuleIds(),
            requiredModules = modules.filterValues { it.enabled && it.required }.keys,
        )
    }

    private fun secureDigestEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(StandardCharsets.US_ASCII),
        second.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun invalidEnrollmentCredential(): ResponseStatusException =
        ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.enrollment.credentialInvalid"))

    private fun incompleteEnrollmentConfiguration(missing: String): ResponseStatusException =
        ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.format("error.enrollment.configurationMissing", missing),
        )

    private fun canonicalHttpsRootOrigin(configured: String): String {
        val parsed = runCatching { URI(configured.trim()) }.getOrNull()
        require(
            parsed != null &&
                parsed.isAbsolute &&
                parsed.scheme.equals("https", ignoreCase = true) &&
                !parsed.host.isNullOrBlank() &&
                parsed.userInfo == null &&
                (parsed.path.isNullOrEmpty() || parsed.path == "/") &&
                (parsed.port == -1 || parsed.port in 1..65535) &&
                parsed.query == null &&
                parsed.fragment == null,
        ) {
            "Chronicle public base URL must be an HTTPS root origin without credentials, path, query, or fragment"
        }
        return URI(
            "https",
            null,
            parsed.host.lowercase(),
            parsed.port,
            null,
            null,
            null,
        ).toASCIIString()
    }
}

private data class EnrollmentConsentEvidence(
    val settingsVersion: Int,
    val disclosureVersion: String,
    val enabledModules: Set<CollectionModuleId>,
    val requiredModules: Set<CollectionModuleId>,
)

private data class ParsedEnrollmentAttempt(
    val attemptId: UUID,
    val proposedKeyHash: String,
)

internal object EnrollmentAttemptDigest {
    private val canonicalUuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )
    @Suppress("DEPRECATION")
    private val mapper = ObjectMappers.getJsonMapper().copy().apply {
        configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

    fun parseCanonicalUuid(value: String): UUID? =
        value.takeIf(canonicalUuid::matches)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun sha256Text(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

    fun canonicalSourceDeviceHash(sourceDevice: SourceDevice): String = sha256(mapper.writeValueAsBytes(sourceDevice))

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
}

internal fun loadLockedStudy(connection: Connection, studyId: UUID): Study {
    // ResultSetAdapters.study expects the organization_ids projection used by StudyService queries.
    // Enrollment manifests do not consume organization membership, so project a typed empty set while
    // retaining a row lock on the authoritative studies row.
    val sql = """
        SELECT studies.*, ARRAY[]::uuid[] AS organization_ids
        FROM studies
        WHERE study_id = ?
        FOR SHARE OF studies
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.enrollment.configurationUnavailable"))
            }
            ResultSetAdapters.study(resultSet)
        }
    }
}

/** Canonical JSON SHA-256 contract; clients store and echo this server-issued digest. */
internal object EnrollmentManifestDigest {
    private val digestPattern = Regex("^[0-9a-f]{64}$")
    @Suppress("DEPRECATION")
    private val mapper = ObjectMappers.getJsonMapper().copy().apply {
        configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

    fun compute(manifest: EnrollmentManifest): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonicalizeSets(manifest))),
    )

    fun isDigest(value: String): Boolean = digestPattern.matches(value)

    /** Jackson preserves Set iteration order, so normalize only fields whose contract is explicitly set-valued. */
    private fun canonicalizeSets(manifest: EnrollmentManifest): EnrollmentManifest {
        val canonicalModules = manifest.collectionSettings.modules.entries
            .sortedBy { (moduleId, _) -> moduleId.id }
            .associate { (moduleId, moduleSetting) ->
                val canonicalHealthTypes = moduleSetting.healthConnectRecordTypes
                    .sortedBy { recordType -> recordType.id }
                    .toCollection(LinkedHashSet())
                val canonicalSensorPolicy = moduleSetting.sensorPolicy?.let { policy ->
                    policy.copy(
                        sensors = policy.sensors
                            .sortedBy { sensor -> sensor.name }
                            .toCollection(LinkedHashSet()),
                    )
                }
                moduleId to moduleSetting.copy(
                    healthConnectRecordTypes = canonicalHealthTypes,
                    sensorPolicy = canonicalSensorPolicy,
                )
            }
        return manifest.copy(
            collectionSettings = manifest.collectionSettings.copy(modules = canonicalModules),
        )
    }
}
