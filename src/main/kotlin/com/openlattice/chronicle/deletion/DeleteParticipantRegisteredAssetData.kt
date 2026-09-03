package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.delete.ChronicleDataAssetRegistry
import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import java.util.UUID

public data class DeleteParticipantRegisteredAssetData(
    override val studyId: UUID,
    override val participantIds: Collection<String>,
    val assetId: String,
) : ChronicleParticipantJobDefinition {
    init {
        val asset = ChronicleDataAssetRegistry.participantAsset(assetId)
        require(!asset.handledByDedicatedJob) { "Asset must use its dedicated deletion job" }
    }
}
