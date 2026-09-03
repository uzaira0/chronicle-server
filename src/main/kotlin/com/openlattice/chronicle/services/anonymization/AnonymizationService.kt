package com.openlattice.chronicle.services.anonymization

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.anonymization.AnonymizationConfig
import com.openlattice.chronicle.anonymization.DateGeneralization
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public open class AnonymizationService(
    private val storageResolver: StorageResolver
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(AnonymizationService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        private const val GET_CONFIG_SQL = """
            SELECT config FROM study_anonymization_config WHERE study_id = ?
        """

        private const val UPSERT_CONFIG_SQL = """
            INSERT INTO study_anonymization_config (study_id, config, updated_at)
            VALUES (?, ?::jsonb, now())
            ON CONFLICT (study_id) DO UPDATE
                SET config = EXCLUDED.config, updated_at = now()
        """

        private const val GET_PSEUDONYM_SQL = """
            SELECT pseudonym FROM participant_pseudonyms WHERE study_id = ? AND participant_id = ?
        """

        private const val INSERT_PSEUDONYM_SQL = """
            INSERT INTO participant_pseudonyms (study_id, participant_id, pseudonym)
            VALUES (?, ?, ?)
            ON CONFLICT (study_id, participant_id) DO NOTHING
        """

        // H-5: Per-study random salt for HMAC instead of using public study UUID
        private const val GET_SALT_SQL = """
            SELECT anonymization_salt FROM study_anonymization_config WHERE study_id = ?
        """

        private const val UPSERT_SALT_SQL = """
            INSERT INTO study_anonymization_config (study_id, anonymization_salt, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (study_id) DO UPDATE SET
                anonymization_salt = COALESCE(study_anonymization_config.anonymization_salt, EXCLUDED.anonymization_salt)
        """

        private val secureRandom = SecureRandom()
    }

    public fun getConfig(studyId: UUID): AnonymizationConfig {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_CONFIG_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                return if (rs.next()) {
                    mapper.readValue(rs.getString("config"), AnonymizationConfig::class.java)
                } else {
                    AnonymizationConfig()
                }
            }
        }
    }

    public fun updateConfig(studyId: UUID, config: AnonymizationConfig): AnonymizationConfig {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(UPSERT_CONFIG_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, mapper.writeValueAsString(config))
                ps.executeUpdate()
            }
        }
        logger.info("Anonymization config updated for study {}", studyId)
        return config
    }

    public fun pseudonymize(studyId: UUID, participantId: String): String {
        // Check existing pseudonym
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_PSEUDONYM_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                val rs = ps.executeQuery()
                if (rs.next()) return rs.getString("pseudonym")
            }

            // Generate new pseudonym via HMAC
            val pseudonym = generatePseudonym(studyId, participantId)
            connection.prepareStatement(INSERT_PSEUDONYM_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.setString(3, pseudonym)
                ps.executeUpdate()
            }
            return pseudonym
        }
    }

    public fun anonymizeRow(
        row: Map<String, Any>,
        studyId: UUID,
        config: AnonymizationConfig
    ): Map<String, Any> {
        val result = row.toMutableMap()

        // Pseudonymize participant IDs
        if (config.pseudonymizeParticipantIds) {
            val pid = result["participant_id"]
            if (pid is String) {
                result["participant_id"] = pseudonymize(studyId, pid)
            }
        }

        // Redact fields
        for (field in config.redactedFields) {
            if (result.containsKey(field)) {
                result[field] = "[REDACTED]"
            }
        }

        // Generalize dates
        if (config.dateGeneralization != DateGeneralization.NONE) {
            for ((key, value) in result) {
                if (value is OffsetDateTime) {
                    result[key] = generalizeDate(value, config.dateGeneralization)
                }
            }
        }

        return result
    }

    private fun generalizeDate(date: OffsetDateTime, level: DateGeneralization): OffsetDateTime {
        return when (level) {
            DateGeneralization.NONE -> date
            DateGeneralization.DAY -> date.truncatedTo(ChronoUnit.DAYS)
            DateGeneralization.WEEK -> date.truncatedTo(ChronoUnit.DAYS)
                .minusDays(date.dayOfWeek.value.toLong() - 1)
            DateGeneralization.MONTH -> date.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        }
    }

    /**
     * H-5: Generate pseudonym using a per-study random salt (not the public study UUID).
     */
    private fun generatePseudonym(studyId: UUID, participantId: String): String {
        val salt = getOrCreateStudySalt(studyId)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val hash = mac.doFinal(participantId.toByteArray(Charsets.UTF_8))
        return "P-" + hash.take(8).joinToString("") { "%02x".format(it) }
    }

    // reason: per-study salt read-or-create over nested JDBC use{} scopes with an early return of
    // the existing salt; extracting would break the connection/statement resource lifecycle and
    // the non-local return
    @Suppress("NestedBlockDepth")
    private fun getOrCreateStudySalt(studyId: UUID): ByteArray {
        storageResolver.getPlatformStorage().connection.use { connection ->
            // Try to get existing salt
            connection.prepareStatement(GET_SALT_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val salt = rs.getBytes("anonymization_salt")
                    if (salt != null && salt.isNotEmpty()) return salt
                }
            }

            // Generate new 256-bit random salt
            val newSalt = ByteArray(32)
            secureRandom.nextBytes(newSalt)

            connection.prepareStatement(UPSERT_SALT_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setBytes(2, newSalt)
                ps.executeUpdate()
            }

            return newSalt
        }
    }
}
