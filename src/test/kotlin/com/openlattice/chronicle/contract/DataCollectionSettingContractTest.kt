package com.openlattice.chronicle.contract

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.study.StudySettingType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract-drift guard for the Phase 9A generalized Android data collection setting.
 *
 * Phase 9A does NOT add a new public route: the generalized [DataCollection] read is
 * served by the existing typed-settings endpoint
 * `GET /chronicle/v3/study/{studyId}/settings/type/{settingType}`, which already
 * returns the polymorphic `StudySetting` schema. The only OpenAPI change is the
 * additive documentation of the `AndroidDataCollectionSetting` schema and its nested
 * DTOs, so that `chronicle-web` generated types surface the new shape.
 *
 * This test fails if the spec drifts away from the model contracts in
 * `chronicle-models` — e.g. a [CollectionModuleId] is added but not documented, or
 * the schema is removed. It is the contract-drift gate required by refactor plan
 * §12.1 step 14; it complements `bun run check:api-types` on the web side.
 */
class DataCollectionSettingContractTest {

    private fun specText(): String {
        val candidates = listOf(
            File("../chronicle-api/chronicle.yaml"),
            File("chronicle-api/chronicle.yaml"),
        )
        val spec = candidates.firstOrNull { it.exists() }
            ?: error("OpenAPI spec not found (tried ${candidates.joinToString { it.absolutePath }})")
        return spec.readText()
    }

    @Test
    fun `spec documents the AndroidDataCollectionSetting schema`() {
        val spec = specText()
        listOf(
            "AndroidDataCollectionSetting:",
            "CollectionModuleSetting:",
            "CollectionCadence:",
            "BatteryPolicy:",
            "NetworkPolicy:",
            "CollectionModuleId:",
        ).forEach { schema ->
            assertTrue(
                "chronicle.yaml must document the '$schema' schema (Phase 9A additive DTO)",
                spec.contains(schema),
            )
        }
    }

    @Test
    fun `spec CollectionModuleId enum matches the model enum`() {
        val spec = specText()
        // Every module id declared in the model must appear in the spec enum so the
        // generated web types stay in sync with chronicle-models.
        CollectionModuleId.entries.forEach { moduleId ->
            assertTrue(
                "chronicle.yaml CollectionModuleId enum is missing '${moduleId.id}'",
                spec.contains(moduleId.id),
            )
        }
    }

    @Test
    fun `DataCollection setting type still exists in the model`() {
        // Pins the enum constant the generalized read keys off. If this is renamed or
        // removed, the controller branch and the spec documentation are both stale.
        assertTrue(
            "StudySettingType.DataCollection must exist",
            StudySettingType.entries.any { it == StudySettingType.DataCollection },
        )
    }

    @Test
    fun `no new public route was added for the generalized read`() {
        // Phase 9A design decision: reuse the existing typed-settings endpoint, do not
        // add a new route. Assert no `data-collection/settings` style route crept in.
        val spec = specText()
        val forbidden = listOf(
            "/settings/type/DataCollection:",
            "/data-collection-settings",
            "getAndroidDataCollectionSettings",
        )
        forbidden.forEach { route ->
            assertTrue(
                "Phase 9A must not add a new public route ('$route' found in spec)",
                !spec.contains(route),
            )
        }
    }
}
