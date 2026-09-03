package com.openlattice.chronicle.configuration

import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import java.net.URI

private const val CONFIG_FILE_NAME = "twilio.yaml"

@ReloadableConfiguration(uri = CONFIG_FILE_NAME)
public data class TwilioConfiguration(
    val enabled: Boolean = false,
    val sid: String = "",
    val token: String = "",
    val defaultFromPhone: String = "",
    val callbackBaseUrl: String = "",
) : Configuration {

    internal companion object {
        @JvmStatic
        public val key = SimpleConfigurationKey(CONFIG_FILE_NAME)
    }

    override fun getKey(): ConfigurationKey {
        return TwilioConfiguration.key
    }

    public fun validated(): TwilioConfiguration {
        if (!enabled) return this
        require(sid.isNotBlank()) { "Twilio sid is required when Twilio is enabled" }
        require(token.isNotBlank()) { "Twilio token is required when Twilio is enabled" }
        require(defaultFromPhone.isNotBlank()) { "Twilio defaultFromPhone is required when Twilio is enabled" }
        val origin = runCatching { URI(callbackBaseUrl) }.getOrNull()
        require(
            origin != null &&
                origin.scheme.equals("https", ignoreCase = true) &&
                !origin.host.isNullOrBlank() &&
                origin.userInfo == null && origin.query == null && origin.fragment == null &&
                (origin.path.isNullOrEmpty() || origin.path == "/"),
        ) { "Twilio callbackBaseUrl must be a canonical HTTPS root origin" }
        return copy(callbackBaseUrl = callbackBaseUrl.removeSuffix("/"))
    }
}
