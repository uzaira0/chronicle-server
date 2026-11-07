package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration

public const val STORAGE_CONFIGURATION: String = "storageConfiguration"
public const val WEBHOOKS: String = "webhooks"

/**
 * Study webhooks: outbound HTTPS calls to a study's own server. A deployment whose containers have
 * no route off the host (the self-host bundle, selfhost/config/chronicle.yaml.template) turns them
 * off; registration is then refused with that reason and nothing is ever delivered.
 */
public data class WebhookConfiguration(
    @param:JsonProperty("enabled") val enabled: Boolean = true,
)

@ReloadableConfiguration(uri = "chronicle.yaml")
public data class ChronicleConfiguration(
    @param:JsonProperty(STORAGE_CONFIGURATION) val storageConfiguration: ChronicleStorageConfiguration = ChronicleStorageConfiguration(),
    @param:JsonProperty(WEBHOOKS) val webhooks: WebhookConfiguration = WebhookConfiguration(),
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("chronicle.yaml")
    }

    override fun getKey(): ConfigurationKey {
        return ChronicleConfiguration.key
    }
}
