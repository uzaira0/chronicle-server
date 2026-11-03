package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration

public const val STORAGE_CONFIGURATION: String = "storageConfiguration"

@ReloadableConfiguration(uri = "chronicle.yaml")
public data class ChronicleConfiguration(
    @param:JsonProperty(STORAGE_CONFIGURATION) val storageConfiguration: ChronicleStorageConfiguration = ChronicleStorageConfiguration()
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("chronicle.yaml")
    }

    override fun getKey(): ConfigurationKey {
        return ChronicleConfiguration.key
    }
}
