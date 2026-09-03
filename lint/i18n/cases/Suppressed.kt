package cases

import jakarta.servlet.http.HttpServletResponse

// Suppression semantics: an ignore directive on its own line above the statement is honored,
// with or without a rule id. Trailing text after the rule id breaks it, and a same-line
// trailing comment is not consulted.
class Suppressed(private val response: HttpServletResponse) {
    fun a() {
        // ast-grep-ignore: server-i18n-response-literal
        response.sendError(400, "Invalid query string")
        // ast-grep-ignore
        response.sendError(400, "Blanket ignore also works")
        // ast-grep-ignore: server-i18n-response-literal -- reason text breaks it FIRE: unused-suppression
        response.sendError(400, "Still reported") // FIRE: server-i18n-response-literal
        response.sendError(400, "Same-line ignore is not honored") // ast-grep-ignore: server-i18n-response-literal FIRE: server-i18n-response-literal, unused-suppression
    }
}
