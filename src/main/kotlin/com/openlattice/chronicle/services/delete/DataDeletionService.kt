package com.openlattice.chronicle.services.delete

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.data.ChronicleDeleteType
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import org.springframework.stereotype.Service
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
// DEPRECATED: This service has empty method bodies, no callers, and is effectively dead code.
// The actual data deletion logic lives in:
//   - StudyController.destroyStudy() / deleteStudyParticipants() — creates background deletion jobs
//   - ParticipantPurgeService.executePurge() — covers all 10 DATA_TABLES for per-participant purge
// This class should be removed once study-level deletion is brought to parity with ParticipantPurgeService.
@Service
public open class DataDeletionService(
    private val enrollmentManager: EnrollmentManager
) : DataDeletionManager {

    @Timed
    override fun deleteParticipantData(
        organizationId: UUID,
        studyId: UUID,
        participantId: String,
        chronicleDeleteType: ChronicleDeleteType,
    ) {

    }

    /**
     * This services assumes that appropriate security checks have already been enforced at controller level.
     */
    @Timed
    override fun deleteStudyData(
        organizationId: UUID,
        studyId: UUID,
        chronicleDeleteType: ChronicleDeleteType,
    ) {

        // ensure study exists
        check(enrollmentManager.studyExists(studyId)) {
            "Study $studyId in organization $organizationId does not exist."
        }

    }


}
