package com.openlattice.chronicle.services.studies

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard reads participants and their stats page by page (LIMIT/OFFSET). Without an
 * ORDER BY, Postgres may return rows in a different order per page, so offsets can skip or
 * repeat participants. Both paged queries must sort on the (study_id, participant_id) key.
 */
class StudyServicePagingSqlTest {
    private val orderedPage = Regex("""ORDER BY\s+participant_id\s+LIMIT \? OFFSET \?""")

    @Test
    fun `participant pages are ordered by participant id`() {
        assertTrue(orderedPage.containsMatchIn(StudyService.SELECT_STUDY_PARTICIPANTS_SQL))
    }

    @Test
    fun `participant stats pages are ordered by participant id`() {
        assertTrue(orderedPage.containsMatchIn(StudyService.GET_STUDY_PARTICIPANT_STATS))
    }
}
