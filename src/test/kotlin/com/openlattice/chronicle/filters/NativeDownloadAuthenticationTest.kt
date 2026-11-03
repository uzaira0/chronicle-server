package com.openlattice.chronicle.filters

import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.generated.BrowserDownloadContracts
import com.openlattice.chronicle.study.StudyApi
import jakarta.servlet.http.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

class NativeDownloadAuthenticationTest {
    private val resolver = ChronicleCookieOrBearerTokenResolver()
    private val path = StudyApi.CONTROLLER + "/${UUID.randomUUID()}" + StudyApi.PARTICIPANTS_PATH + StudyApi.DATA_PATH

    @Test
    fun nativeFormPostRequiresTheCookieMatchingBodyToken() {
        val request = MockHttpServletRequest("POST", path).apply {
            contentType = "application/x-www-form-urlencoded"
            setCookies(Cookie(AuthTokenController.AUTH_COOKIE_NAME, "session"),
                Cookie(AuthTokenController.CSRF_COOKIE_NAME, "csrf"))
        }
        assertNull(resolver.resolve(request))
        request.addParameter(BrowserDownloadContracts.BROWSER_DOWNLOAD_CSRF_FIELD, "wrong")
        assertNull(resolver.resolve(request))
        request.setParameter(BrowserDownloadContracts.BROWSER_DOWNLOAD_CSRF_FIELD, "csrf")
        assertEquals("session", resolver.resolve(request))
        request.method = "GET"
        assertNull(resolver.resolve(request))
    }
}
