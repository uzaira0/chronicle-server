package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthUploadAuthorizationTest {
    @Test fun queuedFamiliesRemainBoundToTheirAuthorizingRevisionAcrossScopeChanges() {
        val module = CollectionModuleId.HEALTH_CONNECT
        HealthConnectRecordType.entries.forEach { family ->
            val oldPolicy = CollectionModuleSetting(enabled = true, healthConnectRecordTypes = setOf(family))
            val old = AndroidDataCollectionSetting(modules = mapOf(module to oldPolicy), settingsVersion = 1)
            val latest = old.copy(settingsVersion = old.settingsVersion + 1, modules = mapOf(module to
                oldPolicy.copy(healthConnectRecordTypes = HealthConnectRecordType.entries.toSet() - family)))
            val decisions = mapOf(module to IssuedCollectionDecision(IssuedCollectionDecisionState.ACCEPTED, old.settingsVersion))
            val authorization = collectionUploadAuthorization(old, listOf(old, latest), decisions, setOf(module))
            assertEquals(setOf(module), authorization.acceptedModules)
            assertEquals(oldPolicy.healthConnectRecordTypes, authorization.healthConnectRecordTypes)
        }
    }
}
