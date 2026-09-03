package com.openlattice.chronicle.util

import java.util.UUID

/**
 * Converts a client-provided source device identifier string into a deterministic UUID.
 *
 * This replaces the old pattern of storing source_device_id (e.g. Android SSAID) as plaintext
 * in the database. The same input string always produces the same UUID, so device dedup still
 * works via the (study_id, device_id) primary key, but no real hardware identifier is persisted.
 */
public object DeviceIdUtils {
    /**
     * Derives a deterministic device UUID from a study, participant, and client-provided device identifier.
     * Uses UUID v3 (MD5 name-based, via UUID.nameUUIDFromBytes) to ensure the same inputs always produce the same UUID.
     */
    @JvmStatic
    public fun deriveDeviceId(studyId: UUID, participantId: String, sourceDeviceId: String): UUID {
        val nameBytes = "$studyId:$participantId:$sourceDeviceId".toByteArray(Charsets.UTF_8)
        // Deterministic, non-secret database dedup key; changing the algorithm would break
        // existing device identity joins.
        return UUID.nameUUIDFromBytes(nameBytes) // nosemgrep: chronicle-uuid-from-string
    }
}
