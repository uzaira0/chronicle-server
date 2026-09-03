package com.openlattice.chronicle.util.tests

import com.openlattice.chronicle.sources.SourceDevice
import org.apache.commons.lang3.RandomStringUtils

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Suppress("DEPRECATION")
public open class TestSourceDevice(
    public val testDeviceLabel: String = RandomStringUtils.randomAlphanumeric(10)
) : SourceDevice
