package com.openlattice.chronicle.storage

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.withConsentEpochs
import com.openlattice.chronicle.collection.collectionSettingsForFeatures
import com.openlattice.chronicle.services.studies.StudyDeletionGuard
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Public collection configuration and its exact study settings revision, read from one row. */
internal data class DeviceCollectionSettingsSnapshot(val setting: AndroidDataCollectionSetting, val revision: Long)

/** Revision evidence retains choices and features; every policy consumer receives the same read projection. */
internal class CollectionSettingsRepository(private val connection: Connection) {
    private companion object {
        private val logger = LoggerFactory.getLogger(CollectionSettingsRepository::class.java)
    }

    fun latest(studyId: UUID): AndroidDataCollectionSetting? = connection.prepareStatement(
        "SELECT settings, modules FROM studies WHERE study_id = ?" + if (connection.isReadOnly) "" else " FOR SHARE",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val settings = readStudySettings(rows) ?: return@use null
            collectionSettingsForFeatures(settings, featureKeys(rows.getString("modules")))[StudySettingType.DataCollection]
                as? AndroidDataCollectionSetting
        }
    }

    /** A ledger that lags the current projection forces re-consent for the diverged modules instead of failing every phone. */
    fun forDevice(studyId: UUID): AndroidDataCollectionSetting? = latest(studyId)?.let { current -> consentProjection(studyId, current) }

    /** This public projection never returns a full study or bypasses its durable erasure boundary. */
    fun forDeviceSnapshot(studyId: UUID): DeviceCollectionSettingsSnapshot? = connection.prepareStatement(
        """
        SELECT settings, modules, settings_revision FROM studies
        WHERE study_id = ? AND NOT ${StudyDeletionGuard.blockedStudySql("studies.study_id")}
        """.trimIndent() + if (connection.isReadOnly) "" else " FOR SHARE",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val settings = readStudySettings(rows) ?: StudySettings(emptyMap())
            val storedCollection = settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
            val effective = collectionSettingsForFeatures(
                StudySettings(settings + (StudySettingType.DataCollection to settings.androidDataCollection())),
                featureKeys(rows.getString("modules")),
            ).androidDataCollection()
            val revision = rows.getLong("settings_revision")
            val setting = if (storedCollection == null) effective else consentProjection(studyId, effective)
            DeviceCollectionSettingsSnapshot(setting, revision)
        }
    }

    private fun readStudySettings(rows: ResultSet): StudySettings? = rows.getString("settings")?.let {
        ObjectMappers.getJsonMapper().readValue<StudySettings>(it)
    }

    private fun consentProjection(studyId: UUID, current: AndroidDataCollectionSetting): AndroidDataCollectionSetting {
        val revisions = through(studyId, current.settingsVersion)
        if (revisions.lastOrNull() != current) logger.warn("Settings ledger of {} lags its current collection settings",
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"))
        return current.withConsentEpochs(revisions)
    }

    fun through(studyId: UUID, latestVersion: Int): List<AndroidDataCollectionSetting> = connection.prepareStatement(
        "SELECT setting, study_modules FROM data_collection_settings_revisions " +
            "WHERE study_id = ? AND settings_version <= ? ORDER BY settings_version",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.setInt(2, latestVersion)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(effectiveRevision(rows)) } }
    }

    fun issued(studyId: UUID, version: Int): AndroidDataCollectionSetting = connection.prepareStatement(
        "SELECT setting, study_modules FROM data_collection_settings_revisions WHERE study_id = ? AND settings_version = ?",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.setInt(2, version)
        statement.executeQuery().use { rows ->
            require(rows.next()) { "settingsVersion was not issued by the immutable server ledger" }
            val issued = effectiveRevision(rows)
            require(issued.settingsVersion == version) { "Immutable settings ledger revision does not match settingsVersion" }
            require(!rows.next()) { "settingsVersion has ambiguous immutable server evidence" }
            issued
        }
    }

    private fun effectiveRevision(rows: ResultSet): AndroidDataCollectionSetting {
        val setting = ObjectMappers.getJsonMapper().readValue<AndroidDataCollectionSetting>(rows.getString("setting"))
        return collectionSettingsForFeatures(
            StudySettings(mapOf(StudySettingType.DataCollection to setting)), featureKeys(rows.getString("study_modules")),
        ).androidDataCollection()
    }

    private fun featureKeys(json: String?): Set<String> =
        ObjectMappers.getJsonMapper().readTree(json ?: "{}").fieldNames().asSequence().toSet()
}
