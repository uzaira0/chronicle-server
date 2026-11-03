package com.openlattice.chronicle.util.tests

import com.google.common.collect.ImmutableList
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.authorization.Ace
import com.openlattice.chronicle.authorization.AceValue
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Action
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.Role
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.organizations.OrganizationPrincipal
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.AppUsageSurveySetting
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyWithdrawalSetting
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.RandomUtils
import org.apache.commons.text.CharacterPredicates
import org.apache.commons.text.RandomStringGenerator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Suppress("DEPRECATION")
public class TestDataFactory private constructor() {
    // reason: test-data builder companion — a cohesive set of factory functions; splitting fragments fixtures
    @Suppress("TooManyFunctions")
    internal companion object {
        private val actions = Action.values()
        private val r = Random()
        private val permissions = Permission.values()
        private val securableObjectTypes = SecurableObjectType.values()
        private val studyFeatures = StudyFeature.values()
        private val studySettings = StudySettingType.values()
        private val allowedDigitsAndLetters = arrayOf(
            charArrayOf('a', 'z'), charArrayOf('A', 'Z'), charArrayOf('0', '9')
        )
        // reason: RandomStringGenerator.Builder.withinRange is a vararg API requiring spread; the array is tiny
        @Suppress("SpreadOperator")
        private val randomAlphaNumeric: RandomStringGenerator = org.apache.commons.text.RandomStringGenerator.Builder()
            .withinRange(*allowedDigitsAndLetters)
            .filteredBy(CharacterPredicates.LETTERS, CharacterPredicates.DIGITS)
            .build()

        public fun <T> randomSubset(values: Array<T>): Set<T> {
            return values.filter { r.nextBoolean() }.toSet()
        }

        public fun randomFeatures(): Map<StudyFeature, Any> {
            val numFeatures = 1 + r.nextInt(studyFeatures.size)
            return (0 until numFeatures).associate { studyFeatures[it] to mapOf<String, Any>() }
        }

        public fun randomSettings(): StudySettings {
            // Exclude Sensor settings from test data - sensor modifications require admin privileges
            val testableSettings = studySettings
            val numFeatures = 1 + r.nextInt(testableSettings.size)
            return StudySettings((0 until numFeatures).associate {
                testableSettings[it] to when (testableSettings[it]) {
                    StudySettingType.DataCollection -> CollectionDefaults.androidDataCollectionSetting()
                    StudySettingType.Notifications -> StudyNotificationSettings(
                        randomAlphanumeric(5),
                        randomAlphanumeric(5),
                        r.nextBoolean()
                    )
                    StudySettingType.TimeUseDiary -> TimeUseDiarySettings()
                    StudySettingType.Survey -> SurveySettings()
                    StudySettingType.ParticipantPolicy -> participantPolicy()
                    StudySettingType.AppUsageSurvey -> AppUsageSurveySetting()
                    StudySettingType.Withdrawal -> StudyWithdrawalSetting()
                }
            })

        }


        public fun participantPolicy(): StudyParticipantPolicy = StudyParticipantPolicy(
            responsibleInstitution = "Example Research Institute",
            serverOperator = "Example Research Institute",
            researchContact = "research@example.org",
            purpose = "Deterministic test study purpose",
            expectedDuration = "30 days",
            procedures = "Deterministic test procedures",
            foreseeableRisks = "Minimal test fixture risks",
            expectedBenefits = "No direct benefit",
            dataUseAndSharing = "Used only for deterministic tests",
            retentionAndDeletion = "Deleted after deterministic tests",
            privacyPolicyUrl = "https://example.org/privacy",
            withdrawalUrl = "https://example.org/withdrawal",
            consentDocumentUrl = "https://example.org/consent",
            version = "test-v1",
            effectiveAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )

        public fun randomAlphanumeric(length: Int): String {
            return randomAlphaNumeric.generate(length)
        }

        public fun aclKey(): AclKey {
            return AclKey(UUID.randomUUID(), UUID.randomUUID())
        }

        public fun role(): Role {
            return Role(
                Optional.of(UUID.randomUUID()),
                UUID.randomUUID(),
                rolePrincipal(),
                randomAlphanumeric(5),
                Optional.of<String>(randomAlphanumeric(5))
            )
        }

        public fun role(organizationId: UUID): Role {
            return Role(
                Optional.of(UUID.randomUUID()),
                organizationId,
                rolePrincipal(),
                randomAlphanumeric(5),
                Optional.of<String>(randomAlphanumeric(5))
            )
        }

        public fun rolePrincipal(): Principal {
            return Principal(
                PrincipalType.ROLE,
                randomAlphanumeric(5)
            )
        }

        public fun userPrincipal(): Principal {
            return Principal(PrincipalType.USER, randomAlphanumeric(10))
        }

        public fun ace(): Ace {
            return Ace(
                userPrincipal(),
                permissions()
            )
        }

        public fun aceValue(): AceValue {
            return AceValue(
                permissions(),
                securableObjectType()
            )
        }

        public fun acl(): Acl {
            return Acl(
                AclKey(UUID.randomUUID(), UUID.randomUUID()),
                ImmutableList.of(ace(), ace(), ace(), ace())
            )
        }

        public fun aclData(): AclData {
            return AclData(acl(), actions[r.nextInt(actions.size)])
        }

        @JvmStatic
        public fun permissions(): EnumSet<Permission> {
            return permissions
                .filter { r.nextBoolean() }
                .toCollection(EnumSet.noneOf(Permission::class.java))
        }

        public fun nonEmptyPermissions(): EnumSet<Permission> {
            var ps: EnumSet<Permission> = permissions()
            while (ps.isEmpty()) {
                ps = permissions()
            }
            return ps
        }

        public fun securableObjectType(): SecurableObjectType {
            return securableObjectTypes[r.nextInt(securableObjectTypes.size)]

        }

        public fun securablePrincipal(type: PrincipalType): SecurablePrincipal {
            val principal: Principal = when (type) {
                PrincipalType.ROLE -> rolePrincipal()
                PrincipalType.ORGANIZATION -> organizationPrincipal()
                PrincipalType.USER -> userPrincipal()
                else -> userPrincipal()
            }
            return SecurablePrincipal(
                AclKey(UUID.randomUUID()),
                principal,
                randomAlphanumeric(10),
                Optional.of<String>(randomAlphanumeric(10))
            )
        }

        public fun organizationPrincipal(): Principal {
            return Principal(
                PrincipalType.ORGANIZATION,
                randomAlphanumeric(
                    10
                )
            )
        }

        public fun securableOrganizationPrincipal(): OrganizationPrincipal {
            return OrganizationPrincipal(
                Optional.of(UUID.randomUUID()),
                organizationPrincipal(),
                randomAlphanumeric(5),
                Optional.of<String>(randomAlphanumeric(10))
            )
        }

        public fun candidate(): Candidate {
            return Candidate()
        }

        public fun participant(participationStatus: ParticipationStatus = ParticipationStatus.ENROLLED): Participant {
            return Participant(
                RandomStringUtils.randomAlphanumeric(8),
                candidate(),
                participationStatus
            )
        }


        public fun study(): Study {
            return Study(
                title = "This is a test study.",
                contact = "${RandomStringUtils.randomAlphabetic(5)}@openlattice.com",
                settings = randomSettings(),
                modules = randomFeatures()
            )
        }

        public fun androidDevice() : AndroidDevice {
            return AndroidDevice(
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5),
                randomAlphanumeric(5)
            )
        }

        public fun chronicleUsageEvents(studyId: UUID, participantId: String, count: Int = 10): ChronicleData {
            val usageEvents = (0 until count).map {
                ChronicleUsageEvent(
                    studyId,
                    participantId,
                    RandomStringUtils.randomAlphanumeric(5),
                    RandomStringUtils.randomAlphanumeric(5),
                    RandomUtils.nextInt(0,100),
                    OffsetDateTime.now(),
                    TimeZone.getDefault().id,
                    RandomStringUtils.randomAlphanumeric(5),
                    RandomStringUtils.randomAlphanumeric(5),
                    "com.example.Activity$it",
                    clientEventId = UUID.randomUUID(),
                )
            }

            return ChronicleData( usageEvents )
        }

        public fun participantStats(): ParticipantStats {
            return ParticipantStats(
                UUID.randomUUID(),
                RandomStringUtils.randomAlphanumeric(8),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                setOf(LocalDate.now()),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                setOf(LocalDate.now()),
                OffsetDateTime.now(),
                null,
                setOf(LocalDate.now()),
            )
        }

        public fun studyLimits(): StudyLimits {
            return StudyLimits(
                studyDuration = StudyDuration(years = 2),
                dataRetentionDuration = StudyDuration(days = 180),
            )
        }

        public fun timeUseDiaryResponses(count: Int = 3): List<TimeUseDiaryResponse> {
            return (0 until count).map {
                TimeUseDiaryResponse(
                    code = "code_${randomAlphanumeric(5)}",
                    question = "Question ${randomAlphanumeric(10)}?",
                    response = setOf(randomAlphanumeric(5), randomAlphanumeric(5)),
                    startDateTime = OffsetDateTime.now().minusHours(it.toLong() + 1),
                    endDateTime = OffsetDateTime.now().minusHours(it.toLong())
                )
            }
        }

        public fun organization(): Organization {
            return Organization(title = "Test Org ${randomAlphanumeric(8)}")
        }
    }
}
