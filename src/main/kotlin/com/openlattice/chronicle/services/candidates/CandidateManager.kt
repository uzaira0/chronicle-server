package com.openlattice.chronicle.services.candidates

import com.openlattice.chronicle.candidates.Candidate
import java.sql.Connection
import java.util.UUID

public interface CandidateManager {
    public fun exists(connection: Connection, candidateId: UUID): Boolean
    public fun getCandidate(candidateId: UUID): Candidate
    public fun getCandidates(candidateIds: Set<UUID>): Iterable<Candidate>
    public fun registerCandidate(connection: Connection, candidate: Candidate): UUID
}
