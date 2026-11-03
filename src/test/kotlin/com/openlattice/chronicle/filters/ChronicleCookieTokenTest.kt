package com.openlattice.chronicle.filters

import jakarta.servlet.http.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest

class ChronicleCookieTokenTest {
    @Test fun onlyTheCurrentCookieAndAuthorizationHeaderAuthenticate() {
        val resolver = ChronicleCookieOrBearerTokenResolver()
        val request = MockHttpServletRequest("GET", "/chronicle/v3/study").apply {
            setCookies(Cookie("authorization", "Bearer retired"), Cookie("ol_csrf_token", "csrf"))
            addHeader(ChronicleCookieOrBearerTokenResolver.CSRF_HEADER, "csrf")
        }
        assertNull(resolver.resolve(request))
        request.setCookies(Cookie("chronicle_auth", "current"), Cookie("ol_csrf_token", "csrf"))
        assertEquals("current", resolver.resolve(request))
        request.addHeader("Authorization", "Bearer header")
        assertEquals("header", resolver.resolve(request))
    }
}
