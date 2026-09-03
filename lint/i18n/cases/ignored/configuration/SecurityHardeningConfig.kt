package cases.configuration

import jakarta.servlet.http.HttpServletResponse

// configuration/SecurityHardeningConfig.kt is excluded by path: protocol rejections only.
class SecurityHardeningConfig(private val response: HttpServletResponse) {
    fun a() = response.sendError(400, "Invalid request URI")
}
