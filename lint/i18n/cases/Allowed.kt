package cases

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException

// Allowed shapes for server-i18n-response-literal: Messages lookups, variables, interpolation-only
// strings, non-response exceptions, developer diagnostics. Nothing here may fire.
class Allowed(private val response: HttpServletResponse, private val detail: String, private val payload: Any) {
    fun a(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("study.not_found"))
    fun b(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, Messages.format("study.config_missing", detail))
    fun c(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$detail")
    fun d(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    fun e(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("k") + detail)
    fun f(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)
    fun g() = response.sendError(401)
    fun h() = response.sendError(401, Messages.get("auth.api_key_invalid"))
    fun i() = ResponseEntity.ok(payload)
    fun j() = ResponseEntity.status(HttpStatus.FORBIDDEN).body(payload)
    fun k() = ResponseEntity(payload, HttpStatus.OK)
    fun l(): Nothing = throw IllegalStateException("Developer failure text is not a response")
    fun m() { require(detail.isNotEmpty()) { "Developer invariant text" } }
    fun n(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "")
    fun o(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "42")
    val audit = "English audit text is written to the audit log, not shown"
    fun p() = ApiError(status = 409, error = "Conflict", message = Messages.get("error.conflict"), errors = null)
    fun q() = ApiError(status = 404, error = "Not Found", message = detail, errors = null)
}

class ApiError(val status: Int, val error: String, val message: String, val errors: List<String>?)
object Messages {
    fun get(key: String): String = key
    fun format(key: String, vararg args: Any): String = key
}
