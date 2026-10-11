package com.openlattice.chronicle.auditing

import java.sql.Connection

public interface AuditingManager {

    public fun recordEvents(events: List<AuditableEvent>): Int

    public fun recordEvents(connection: Connection, events: List<AuditableEvent>): Int {
        check(events.isEmpty()) { "Auditing manager does not support transactional staging" }
        return 0
    }

}
