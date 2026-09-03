package cases.configuration

import jakarta.servlet.http.HttpServletResponse

// configuration/ParameterPollutionFilter.kt is excluded by path: protocol rejections only.
class ParameterPollutionFilter(private val response: HttpServletResponse) {
    fun a() = response.sendError(400, "Invalid request URI")
}
