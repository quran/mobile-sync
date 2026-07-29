package com.quran.shared.auth.utils

private val sensitiveHeaderPattern = Regex(
    pattern = """(?im)^(\s*(?:->\s*)?(?:Authorization|Cookie|Set-Cookie|x-auth-token):)\s*.*$"""
)
private val sensitiveJsonFieldPattern = Regex(
    pattern = """(?i)("(?:access_token|id_token|refresh_token)"\s*:\s*")[^"]*(")"""
)
private val sensitiveFormFieldPattern = Regex(
    pattern = """(?i)(^|[&\n])((?:token|code|code_verifier)=)[^&\r\n]*"""
)

internal fun redactSensitiveAuthData(message: String): String {
    val redactedHeaders = sensitiveHeaderPattern.replace(message) {
        "${it.groupValues[1]} ***"
    }
    val redactedJson = sensitiveJsonFieldPattern.replace(redactedHeaders) {
        "${it.groupValues[1]}***${it.groupValues[2]}"
    }
    return sensitiveFormFieldPattern.replace(redactedJson) {
        "${it.groupValues[1]}${it.groupValues[2]}***"
    }
}
