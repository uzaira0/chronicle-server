package com.openlattice.chronicle.util

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer

/** Match decoded segments while retaining encoded separators inside their original segment. */
internal fun HttpServletRequest.parsedPath(): PathContainer? =
    requestURI.parsedPath()

internal fun String.parsedPath(): PathContainer? =
    runCatching { PathContainer.parsePath(this) }.getOrNull()

internal fun PathContainer.matchingSegments(): List<String> =
    elements().filterIsInstance<PathContainer.PathSegment>().map { it.valueToMatch() }
