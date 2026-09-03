package example

import com.openlattice.chronicle.sources.SourceDevice

/** Test-only subtype outside Chronicle's deserialization allowlist. */
class UntrustedSourceDevice : SourceDevice
