package com.quran.shared.auth.utils

import com.quran.shared.logging.installHttpLogging
import io.ktor.client.HttpClientConfig

internal fun HttpClientConfig<*>.installAuthHttpLogging() {
    installHttpLogging(tag = "AuthHttp", redact = ::redactSensitiveAuthData)
}
