package com.openlattice.chronicle.e2e.dsl.di

interface AuthProvider {
    fun tokenFor(userId: String): String
}
