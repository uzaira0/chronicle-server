package com.openlattice.chronicle.services.jobs

import com.openlattice.chronicle.auditing.AuditingComponent
import java.sql.Connection
import java.util.*

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public interface JobManager : AuditingComponent {
    public fun createJob(connection: Connection, job: ChronicleJob): UUID
    public fun createJobs(connection: Connection, jobs: Iterable<ChronicleJob>): Iterable<UUID>
    public fun getJob(jobId: UUID): ChronicleJob
    public fun getJobs(jobIds: Collection<UUID>): Map<UUID, ChronicleJob>
    public fun lockAndGetNextJob(connection: Connection): ChronicleJob?
    public fun unlockJob( jobId: UUID)
}
