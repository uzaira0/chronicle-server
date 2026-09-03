package com.openlattice.chronicle.e2e.dsl.util

import java.util.UUID

fun e2eTitle(tag: String): String = "E2E-$tag-${UUID.randomUUID()}"
