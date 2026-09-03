package com.openlattice.chronicle.util

/**
 * Pagination defaults and limits to prevent bulk data exfiltration.
 * HIPAA §164.312(a)(1) — Access controls must limit data exposure.
 */
public object PaginationDefaults {
    public const val DEFAULT_PAGE_SIZE: Int = 100
    public const val MAX_PAGE_SIZE: Int = 500
    public const val DEFAULT_OFFSET: Int = 0
    public const val MAX_BULK_IDS: Int = 1000

    /**
     * Clamp the requested page size to the allowed maximum.
     */
    public fun clampLimit(requested: Int): Int = requested.coerceIn(1, MAX_PAGE_SIZE)

    /**
     * Ensure offset is non-negative.
     */
    public fun clampOffset(requested: Int): Int = maxOf(0, requested)
}
