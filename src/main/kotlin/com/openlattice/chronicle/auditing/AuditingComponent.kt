/*
 * Copyright (C) 2019. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.chronicle.auditing

import com.codahale.metrics.annotation.Timed
import java.sql.Connection
import org.slf4j.LoggerFactory

/**
 *
 * This class makes it easy for other classes to implement auditing by passing a instance of the auditable event class
 * with the appropriate data configured.
 */

public interface AuditingComponent {

    public companion object {
        public const val MAX_ENTITY_KEY_IDS_PER_EVENT: Int = 100
        private val logger = LoggerFactory.getLogger(AuditingComponent::class.java)
    }

    public val auditingManager: AuditingManager


    /** Failure to record a diagnostic event must not replace the original response. */
    public fun recordEventBestEffort(event: () -> AuditableEvent): Boolean = try {
        recordEvent(event())
        true
    } catch (expected: Exception) {
        logger.warn("Failed to record best-effort audit event: {}", expected.javaClass.name)
        false
    }

    @Timed
    public fun recordEvent(event: AuditableEvent): Int {
        return recordEvents(listOf(event))
    }

    public fun recordEvent(connection: Connection, event: AuditableEvent): Int =
        auditingManager.recordEvents(connection, listOf(event))

    public fun recordReadEvent(event: AuditableEvent): Int = recordReadEvents(listOf(event))

    public fun recordReadEvents(events: List<AuditableEvent>): Int =
        auditingManager.recordObservations(AuditObservation.Read(events))

    public fun recordCommittedEvent(event: AuditableEvent): Int = recordCommittedEvents(listOf(event))

    public fun recordCommittedEvents(events: List<AuditableEvent>): Int =
        auditingManager.recordObservations(AuditObservation.CommittedEffect(events))

    public fun recordIntentEvent(event: AuditableEvent): Int =
        auditingManager.recordObservations(AuditObservation.Intent(listOf(event)))

    @Timed
    public fun recordEvents(events: List<AuditableEvent>): Int {
        return if (events.isEmpty()) 0 else auditingManager.recordEvents(events)
    }
}
