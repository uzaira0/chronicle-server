// reason: intentional package layout — bean lives under com.openlattice.ioc.providers while the
// file is colocated with the chronicle providers; moving the file would break the build/git history
@file:Suppress("InvalidPackageDeclaration")

package com.openlattice.ioc.providers

import com.openlattice.chronicle.providers.LateInitProvider
import com.openlattice.chronicle.providers.OnUseLateInitProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Configuration
public open class LateInitProvidersPod {
    @Bean
    public fun lateInitProvider() : LateInitProvider = OnUseLateInitProvider()
}
