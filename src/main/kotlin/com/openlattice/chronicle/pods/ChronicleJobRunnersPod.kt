package com.openlattice.chronicle.pods

import com.openlattice.chronicle.services.notifications.NotificationJobRunner
import com.openlattice.chronicle.services.twilio.TwilioService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Configuration
public open class ChronicleJobRunnersPod {
        @Bean
        public fun notificationJobRunner(twilioService: TwilioService) : NotificationJobRunner {
                return NotificationJobRunner(twilioService)
        }
}
