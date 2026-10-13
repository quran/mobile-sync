package com.quran.shared.auth.utils

private val sensitiveHeaderPattern = Regex(
    pattern = """(?im)^([ \t]*(?:->[ \t]*)?(?:Authorization|Cookie|Set-Cookie|x-auth-token):)[ \t]*.*$"""
)
private val sensitiveJsonFieldPattern = Regex(
    pattern = """(?i)("(?:access_token|id_token|id_token_hint|refresh_token|client_secret)"\s*:\s*")[^"]*(")"""
)

// Keys match only at a field start, so `grant_type=refresh_token` and `code_challenge` stay readable.
private val sensitiveFormFieldPattern = Regex(
    pattern = """(?i)(^|[?&\n])((?:token|code|code_verifier|refresh_token|access_token|id_token|id_token_hint|client_secret)=)[^&#\s]*"""
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
