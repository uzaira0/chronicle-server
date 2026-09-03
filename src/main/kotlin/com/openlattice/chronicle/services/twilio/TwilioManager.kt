package com.openlattice.chronicle.services.twilio

import com.openlattice.chronicle.services.notifications.Notification
import com.twilio.type.PhoneNumber
import java.util.*

/**
 * @author Todd Bergman <todd@openlattice.com>
 */

public interface TwilioManager {
    public fun sendNotifications(notifications: List<Notification>): List<Notification>
    public fun getStudyPhoneNumber(studyId: UUID): PhoneNumber
}
