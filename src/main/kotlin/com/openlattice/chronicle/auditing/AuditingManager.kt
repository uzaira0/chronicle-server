package com.openlattice.chronicle.auditing

public interface AuditingManager {

    public fun recordEvents(events: List<AuditableEvent>): Int

}
