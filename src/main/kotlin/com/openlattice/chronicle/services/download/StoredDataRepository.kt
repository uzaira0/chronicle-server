package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresTableDefinition
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.stereotype.Repository
import java.util.UUID

/** Study-scoped existence probes use the same storage binding as the export reader. */
@Repository
public class StoredDataRepository(private val storageResolver: StorageResolver) {
    public fun hasRows(studyId: UUID, table: PostgresTableDefinition, eventStorage: Boolean = false): Boolean {
        val storage = if (eventStorage) storageResolver.resolveAndGetFlavor(studyId).dataSource else storageResolver.getPlatformReadStorage()
        return BasePostgresIterable(StreamingReadTransaction(
            storage, "SELECT EXISTS (SELECT 1 FROM ${table.name} WHERE study_id = ?)", fetchSize = 1,
        ) { DataDownloadService.bindDownloadScope(it, studyId, emptySet(), eventStorage = eventStorage) }) {
            it.getBoolean(1)
        }.single()
    }

}
