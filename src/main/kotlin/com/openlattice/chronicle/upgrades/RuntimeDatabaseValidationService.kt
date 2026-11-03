package com.openlattice.chronicle.upgrades

import com.geekbeast.hazelcast.PreHazelcastUpgradeService
import com.openlattice.chronicle.services.delete.RestoreCheckpointRepository
import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler
import com.openlattice.chronicle.storage.StorageResolver

/** The backend verifies its restricted login and refuses an unfinished owner restore. */
public class RuntimeDatabaseValidationService(private val storageResolver: StorageResolver) : PreHazelcastUpgradeService {
    override fun runUpgrade() {
        storageResolver.databaseRoles.validate()
        val checkpoint = RestoreCheckpointRepository(
            storageResolver.getPlatformStorage(), RestoreContinuityReconciler.CONTRACT_VERSION,
        )
        check(!checkpoint.exists()) { "Run the one-shot migrate service to finish restore continuity before starting the backend" }
    }
}
