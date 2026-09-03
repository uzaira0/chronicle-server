package com.openlattice.chronicle.services.studies

import com.geekbeast.postgres.PostgresArrays
import com.hazelcast.map.IMap
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Applies the durable study-erasure boundary to values loaded from the study
 * cache or directly from the study table.
 */
internal class QuarantineAwareStudyCache(
    private val studies: IMap<UUID, Study>,
    private val deletionGuard: StudyDeletionGuard,
) {
    fun get(studyId: UUID): Study? {
        val study = studies[studyId] ?: return null
        return filterVisible(listOf(study)).firstOrNull()
    }

    fun getAll(studyIds: Collection<UUID>): List<Study> {
        if (studyIds.isEmpty()) {
            return emptyList()
        }
        return filterVisible(studies.getAll(studyIds.toSet()).values)
    }

    fun filterVisible(candidates: Collection<Study>): List<Study> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val visibleStudyIds = filterVisibleStudyIds(candidates.mapTo(mutableSetOf()) { it.id }).toSet()
        return candidates.filter { it.id in visibleStudyIds }
    }

    fun filterVisibleStudyIds(candidates: Collection<UUID>): List<UUID> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val blockedStudyIds = deletionGuard.blockedStudyIds(candidates)
        blockedStudyIds.forEach(::evictBestEffort)
        return candidates.filterNot { it in blockedStudyIds }
    }

    fun isVisible(studyId: UUID): Boolean {
        return filterVisibleStudyIds(listOf(studyId)).isNotEmpty()
    }

    // reason: the durable guard remains authoritative when Hazelcast is
    // unavailable; eviction failure must not turn hidden data visible
    @Suppress("TooGenericExceptionCaught")
    private fun evictBestEffort(studyId: UUID) {
        try {
            studies.evict(studyId)
        } catch (cacheFailure: Exception) {
            logger.error(
                "Study {} is quarantined, but eviction from the study cache failed.",
                studyId,
                cacheFailure,
            )
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(QuarantineAwareStudyCache::class.java)
    }
}

/**
 * Batches study-erasure visibility checks so list endpoints pay one durable
 * lookup instead of one connection and query per study.
 */
internal class StudyDeletionGuard(
    private val storageResolver: StorageResolver,
) {
    fun blockedStudyIds(studyIds: Collection<UUID>): Set<UUID> {
        val candidates = studyIds.toSet()
        if (candidates.isEmpty()) {
            return emptySet()
        }
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(FIND_BLOCKED_STUDIES_SQL).use { statement ->
                statement.setArray(1, PostgresArrays.createUuidArray(connection, candidates))
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getObject(STUDY_ID.name, UUID::class.java))
                        }
                    }
                }
            }
        }
    }

    internal companion object {
        internal val FIND_BLOCKED_STUDIES_SQL = """
            SELECT candidate.study_id
            FROM unnest(?::uuid[]) AS candidate(study_id)
            WHERE NOT public.chronicle_participant_data_visible(candidate.study_id, '')
               OR EXISTS (
                    SELECT 1
                    FROM data_deletion_operations operation
                    WHERE operation.study_id = candidate.study_id
                      AND operation.mode = 'STUDY_ERASURE'
                      AND operation.status = 'COMPLETED'
               )
        """.trimIndent()
    }
}
