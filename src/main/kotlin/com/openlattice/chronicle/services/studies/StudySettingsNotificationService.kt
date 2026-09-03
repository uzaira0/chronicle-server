package com.openlattice.chronicle.services.studies

import org.slf4j.LoggerFactory
import java.util.*

/**
 * Records that study settings changed.
 *
 * Android clients refresh settings through their periodic and upload-piggyback
 * synchronization paths. A standalone deployment may intentionally have no external
 * push provider.
 */
public open class StudySettingsNotificationService {

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudySettingsNotificationService::class.java)
    }

    public open fun notifySettingsChanged(studyId: UUID) {
        logger.debug(
            "Study settings changed for {}; devices will refresh through scheduled synchronization",
            studyId,
        )
    }
}
