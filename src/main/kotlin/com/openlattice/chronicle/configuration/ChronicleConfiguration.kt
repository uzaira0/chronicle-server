package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration

public const val BUCKET_NAME: String = "bucketName"
public const val REGION_NAME: String = "regionName"
public const val TIME_TO_LIVE: String = "timeToLive"
public const val ACCESS_KEY_ID: String = "accessKeyId"
public const val SECRET_ACCESS_KEY: String = "secretAccessKey"
public const val STORAGE_CONFIGURATION: String = "storageConfiguration"

@ReloadableConfiguration(uri = "chronicle.yaml")
public data class ChronicleConfiguration(
    @param:JsonProperty(BUCKET_NAME) val bucketName: String,
    @param:JsonProperty(REGION_NAME) val regionName: String,
    @param:JsonProperty(TIME_TO_LIVE) val timeToLive: Long,
    @param:JsonProperty(ACCESS_KEY_ID) val accessKeyId: String,
    @param:JsonProperty(SECRET_ACCESS_KEY) val secretAccessKey: String,
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
