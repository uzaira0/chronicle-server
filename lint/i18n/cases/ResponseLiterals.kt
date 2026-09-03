package cases

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException

// Cases for server-i18n-response-literal. A `FIRE:` marker names the finding expected on that
// line; unmarked lines must be clean.
class ResponseLiterals(private val response: HttpServletResponse, private val missing: String, private val detail: String) {
    fun a(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found") // FIRE: server-i18n-response-literal
    fun b(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "Configuration is missing $missing") // FIRE: server-i18n-response-literal
    fun c(e: Exception): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body", e) // FIRE: server-i18n-response-literal
    fun d(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Prefix: " + detail) // FIRE: server-i18n-response-literal
    fun e() = response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or expired API key") // FIRE: server-i18n-response-literal
    fun f() = response.sendError(401, "Numeric status literal") // FIRE: server-i18n-response-literal
    fun g() = ApiError(status = 400, error = "Bad Request", message = "Validation failed", errors = null) // FIRE: server-i18n-response-literal
    fun g2() = ApiError(status = 400, error = "Bad Request", message = "Prefix: " + detail, errors = null) // FIRE: server-i18n-response-literal
    fun h() = reject("Export range too large") // FIRE: server-i18n-response-literal
    fun i() = ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied") // FIRE: server-i18n-response-literal
    fun j() = ResponseEntity("Not found here", HttpStatus.NOT_FOUND) // FIRE: server-i18n-response-literal
    fun k(): Nothing = throw ResponseStatusException( // FIRE: server-i18n-response-literal
        HttpStatus.NOT_FOUND,
        // a comment inside the call does not hide it
        "Split across lines",
    )
    val l = ResponseStatusException(HttpStatus.GONE, "Assigned, not thrown") // FIRE: server-i18n-response-literal
    fun m() = ResponseEntity // FIRE: server-i18n-response-literal
        .status(HttpStatus.FORBIDDEN)
        .body("Chained across lines")
}

fun reject(message: String): Nothing = throw IllegalArgumentException(message)
