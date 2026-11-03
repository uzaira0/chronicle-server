package com.openlattice.chronicle.configuration

import jakarta.servlet.Filter

/** Request defenses in the order used by the security chain. */
public data class RequestHardeningFilters(val ordered: List<Filter>)
