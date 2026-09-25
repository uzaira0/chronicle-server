package com.openlattice.chronicle.configuration

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.time.Duration

internal val DEFAULT_OUTBOUND_TIMEOUT: Duration = Duration.ofSeconds(5)

/**
 * A RestTemplate whose connect and read both give up after [timeout]. A default RestTemplate uses the
 * JDK defaults, which never time out, so an identity provider that accepts the connection and
 * never answers would hold the request thread (and its pooled DB connection) indefinitely.
 */
internal fun boundedRestTemplate(timeout: Duration = DEFAULT_OUTBOUND_TIMEOUT): RestTemplate =
    RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeout)
            setReadTimeout(timeout)
        },
    )
