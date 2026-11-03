package com.openlattice.chronicle.storage

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.IssuedCollectionDecision
import com.openlattice.chronicle.collection.IssuedCollectionDecisionState
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECLINED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UNAVAILABLE_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECISION_SEQ
import java.sql.Connection
import java.util.UUID

/** One module's decision from one acknowledgment, ordered by the phone's decision sequence. */
internal data class RecordedCollectionDecision(
    val module: CollectionModuleId,
    val decision: IssuedCollectionDecision,
    val seq: Long,
)

/** Each module's decision in force at [seq] (every decision when null): the last at or below it wins. */
internal fun List<RecordedCollectionDecision>.current(seq: Long? = null): Map<CollectionModuleId, IssuedCollectionDecision> =
    filter { seq == null || it.seq <= seq }.associate { it.module to it.decision }

/**
 * One evidence reader for upload authorization, reminders and participant form admission. A device's decisions come
 * from its live key in the phone's decision order; with no device, the participant's newest live key decides.
 */
internal class CollectionDecisionRepository(private val connection: Connection) {
    companion object {
        val SQL = """
            SELECT acknowledgment.acknowledged_modules, acknowledgment.declined_modules,
                   acknowledgment.unavailable_modules, acknowledgment.settings_version, acknowledgment.decision_seq
            FROM participant_collection_acknowledgment AS acknowledgment
            JOIN api_keys AS mobile_key ON mobile_key.key_id = acknowledgment.evidence_api_key_id
            WHERE mobile_key.study_id = ? AND mobile_key.participant_id = ?
              AND mobile_key.revoked = false AND mobile_key.expires_at > now()
              AND mobile_key.random_device_id = COALESCE(?::uuid, (
                  SELECT newest.random_device_id FROM api_keys AS newest
                  WHERE newest.study_id = mobile_key.study_id AND newest.participant_id = mobile_key.participant_id
                    AND newest.revoked = false AND newest.expires_at > now()
                  ORDER BY newest.created_at DESC, newest.key_id DESC LIMIT 1))
            ORDER BY acknowledgment.decision_seq, acknowledgment.recorded_at, acknowledgment.id
        """.trimIndent()
    }

    fun history(studyId: UUID, participantId: String, device: UUID? = null): List<RecordedCollectionDecision> =
        connection.prepareStatement(SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setObject(3, device)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val version = rows.getInt(SETTINGS_VERSION.name)
                    if (rows.wasNull()) continue
                    val accepted = decodeCollectionModules(rows.getString(ACKNOWLEDGED_MODULES.name))
                    val declined = decodeCollectionModules(rows.getString(DECLINED_MODULES.name))
                    val unavailable = decodeCollectionModules(rows.getString(UNAVAILABLE_MODULES.name))
                    require((accepted + declined + unavailable).size == accepted.size + declined.size + unavailable.size) {
                        "Collection acknowledgment contains contradictory module decisions"
                    }
                    require(unavailable.all(SensorCollectionModules::isSensorModule)) {
                        "Collection acknowledgment marks a non-sensor module unavailable"
                    }
                    val seq = rows.getLong(DECISION_SEQ.name)
                    val decisions = accepted.associateWith { IssuedCollectionDecisionState.ACCEPTED } +
                        declined.associateWith { IssuedCollectionDecisionState.DECLINED } +
                        unavailable.associateWith { IssuedCollectionDecisionState.UNAVAILABLE }
                    decisions.forEach { (module, state) ->
                        add(RecordedCollectionDecision(module, IssuedCollectionDecision(state, version), seq))
                    }
                }
            } }
        }
}

internal fun decodeCollectionModules(json: String?): Set<CollectionModuleId> =
    ObjectMappers.getJsonMapper().readValue<List<String>>(json ?: "[]").mapTo(linkedSetOf()) {
        requireNotNull(CollectionModuleId.fromIdOrNull(it)) { "Unknown collection module in receipt" }
    }
