package cases

import jakarta.servlet.http.HttpServletResponse

// Anything under src/test/ is ignored.
class IgnoredTest(private val response: HttpServletResponse) {
    fun a() = response.sendError(400, "Prose in a test is fine")
}
