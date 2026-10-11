package com.openlattice.chronicle.util

private val CONTENT_CODING_SUFFIX = Regex("""(?:--(?:gzip|br|zstd|deflate)|-(?:gzip|br|zstd))$""")

/** Compressing layers can append a content coding to the application's revision. */
internal fun normalizeEntityTag(value: String): String = value.trim().removePrefix("W/").trim().trim('"')
    .replace(CONTENT_CODING_SUFFIX, "")
