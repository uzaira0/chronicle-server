package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.notifications.ParticipantNotification
import java.sql.Connection
import java.util.*

/**
 * @author Todd Bergman <todd@openlattice.com>
 */

public interface NotificationManager {
    public fun sendNotifications(studyId : UUID, participantNotificationList :List<ParticipantNotification>)
    public fun updateNotificationStatus(messageSid: String, status :String)
    public fun sendNotifications(
        connection: Connection,
        studyId: UUID,
        participantNotifications: List<ParticipantNotification>
    ): Int

    public fun sendResearcherNotifications(
        connection: Connection,
        studyId: UUID,
        researcherNotifications: List<ResearcherNotification>,
        html: Boolean =  false,
        principal: Principal =  Principals.getCurrentUser()
    ): Int
}
