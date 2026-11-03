package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.controllers.ReviewerEnrollmentController
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer

internal fun configureChronicleAuthorization(http: HttpSecurity) {
    http.authorizeHttpRequests { auth ->
        auth
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

            // The historical /datastore/* servlet alias is intentionally retired. Keep an
            // explicit deny here as defense in depth if a container mapping regresses.
            .requestMatchers("/datastore/**").denyAll()
            .requestMatchers(
                "/chronicle/v1", "/chronicle/v1/**", "/chronicle/v2", "/chronicle/v2/**",
                "/chronicle/study", "/chronicle/study/**",
            ).denyAll()

        configureDeviceRoutes(auth)
        configureParticipantFormRoutes(auth)
        configureAuthenticationRoutes(auth)
        auth
            // Liveness and dependency-aware readiness are reached through
            // the existing /chronicle/* servlet mapping. No other internal
            // health endpoints are public.
            .requestMatchers(
                HttpMethod.GET,
                "/chronicle/internal/health/live",
                "/chronicle/internal/health/ready",
            ).permitAll()

            // Metrics are authenticated by MetricsAuthenticationFilter and
            // additionally restricted by edge routing and network policy.
            .requestMatchers("/prometheus/**").permitAll()

            // Everything else requires authentication
            .requestMatchers("/chronicle/**").authenticated()
    }
}

private fun configureDeviceRoutes(auth: AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry) {
    auth
        // v4 mobile enrollment — bootstrap step that issues the per-device API key.
        // Subsequent v4 uploads fall under /chronicle/** authenticated() and require X-Api-Key.
        .requestMatchers(
            HttpMethod.GET,
            "/chronicle/v4/study/*/participant/*/enrollment-preview",
        ).permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v4/study/*/participant/*/enroll").permitAll()
        // Reusable Play Console access is accepted only on this exact endpoint by
        // MobileApiSignatureFilter, which converts it to a configured study scope.
        .requestMatchers(HttpMethod.POST, ReviewerEnrollmentController.PATH).permitAll()
        // DataCollection: the device reads its per-module collection settings as an
        // unauthenticated GET (mirrors AndroidSensor). The AndroidDataCollectionSetting
        // DTO carries no apiKey/secret/participantId/PHI by construction, so — like the
        // sensor types above — it is safe to expose with only a study UUID; only specific
        // non-sensitive type endpoints are public.
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/type/DataCollection").permitAll()

        // iOS writes intentionally fall through to /chronicle/** authenticated().
        // The client persists the enrollment-issued device key in Keychain and
        // ApiKeyAuthenticationFilter binds it to study, participant, and device.

}

private fun configureParticipantFormRoutes(auth: AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry) {
    auth
        // Participant access-code exchange is public but one-time and fail-closed in its service.
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/participant-access/exchange").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/participant-access/session").permitAll()

        // Participant form routes are anonymous only after ParticipantFormAccessFilter
        // verifies a scoped, unexpired HttpOnly capability session. Researcher routes in
        // the same controllers continue to require normal authentication and ACL checks.
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/time-use-diary/*/participant/*").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/time-use-diary/*/participant/*").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/time-use-diary/*/settings").permitAll()

        // Survey endpoints (participant-facing, no auth)
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/participant/*/app-usage").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/app-usage").permitAll()
        // The survey page's list of ended days, behind the same participant session.
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/participant/*/app-usage/days").permitAll()
        // Study-scoped survey variant flag (DAILY/HOURLY) — non-sensitive config the
        // participant survey page reads to pick its form; no participant/PHI in the path.
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/app-usage-frequency").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/participant/*/device-usage").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/device-usage").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/questionnaire/*").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/questionnaire/*").permitAll()
}

private fun configureAuthenticationRoutes(auth: AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry) {
    auth
        // Auth cookie management — permitAll() alone is NOT sufficient because the
        // BearerTokenAuthenticationFilter runs first. ChronicleCookieOrBearerTokenResolver
        // must also return null for these paths. See auth-endpoint-routing.md.
        .requestMatchers(HttpMethod.GET, ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH).permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/auth/oidc/login").permitAll()
        .requestMatchers(HttpMethod.GET, "/chronicle/v3/auth/oidc/callback").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/set-cookie").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/testing-login").permitAll()
        // The dashboard login itself must be reachable before anyone is
        // authenticated; the controller verifies the configured bcrypt password and
        // fails closed when none is set.
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/dashboard-login").permitAll()
        .requestMatchers(HttpMethod.POST, "/v3/auth/dashboard-login").permitAll()
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/logout").permitAll()
        .requestMatchers(HttpMethod.GET, "/v3/auth/oidc/login").permitAll()
        .requestMatchers(HttpMethod.GET, "/v3/auth/oidc/callback").permitAll()

        // Twilio webhook callback
        .requestMatchers(HttpMethod.POST, "/chronicle/v3/notification/status").permitAll()

        // JWKS endpoint (public key publication for RS256 mode)
        .requestMatchers(HttpMethod.GET, "/chronicle/.well-known/jwks.json").permitAll()
        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
}
