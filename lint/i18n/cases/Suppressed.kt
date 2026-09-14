package cases

import jakarta.servlet.http.HttpServletResponse

// Suppression semantics: an ignore directive on its own line above the statement is honored,
// with or without a rule id. A -- reason after the rule id is honored, and so is a same-line
// trailing ignore comment (ast-grep >= 0.45.2).
class Suppressed(private val response: HttpServletResponse) {
    fun a() {
        // ast-grep-ignore: server-i18n-response-literal
        response.sendError(400, "Invalid query string")
        // ast-grep-ignore
        response.sendError(400, "Blanket ignore also works")
        // ast-grep-ignore: server-i18n-response-literal -- reason text
        response.sendError(400, "Still reported")
        response.sendError(400, "Same-line ignore is honored") // ast-grep-ignore: server-i18n-response-literal
    }
}
