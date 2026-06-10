package com.quran.shared.pipeline

enum class LogoutWarningType {
    REVOKE_TOKEN_FAILED,
    END_SESSION_FAILED
}

data class LogoutWarning(
    val type: LogoutWarningType,
    val message: String?
)

data class LogoutResult(
    val warnings: List<LogoutWarning> = emptyList()
)
