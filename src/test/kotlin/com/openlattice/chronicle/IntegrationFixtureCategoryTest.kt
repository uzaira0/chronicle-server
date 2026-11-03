package com.openlattice.chronicle

import com.openlattice.chronicle.authorization.PagingSecurableObjectsTest
import com.openlattice.chronicle.filters.ApiKeyMixedCredentialsTests
import com.openlattice.chronicle.filters.JwtBlocklistFilterTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Categories.CategoryFilter
import org.junit.runner.Description

class IntegrationFixtureCategoryTest {
    @Test
    fun fixtureCategoryCoversDirectAndIndirectChildrenAndKeepsUnitTests() {
        val filter = CategoryFilter.exclude(ChronicleServerTests::class.java)
        for (fixture in listOf(
            ChronicleServerTests::class.java,
            ApiKeyMixedCredentialsTests::class.java,
            PagingSecurableObjectsTest::class.java,
        )) {
            assertFalse(fixture.simpleName, filter.shouldRun(Description.createSuiteDescription(fixture)))
        }
        assertTrue(filter.shouldRun(Description.createSuiteDescription(JwtBlocklistFilterTest::class.java)))
    }
}
