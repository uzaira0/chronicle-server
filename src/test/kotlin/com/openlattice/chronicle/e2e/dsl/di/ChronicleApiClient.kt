package com.openlattice.chronicle.e2e.dsl.di

import com.openlattice.chronicle.client.ChronicleClient

interface ChronicleApiClient {
    fun clientFor(userId: String): ChronicleClient
}
