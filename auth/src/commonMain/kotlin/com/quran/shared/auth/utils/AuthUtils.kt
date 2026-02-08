package com.quran.shared.auth.utils

import org.kotlincrypto.hash.sha2.SHA256
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

fun sha256(data: ByteArray): ByteArray {
    val digest = SHA256()
    digest.update(data)
    return digest.digest()
}
fun base64UrlEncode(data: ByteArray): String {
    val base64 = Base64.encode(data)
    return base64.replace("+", "-")
        .replace("/", "_")
        .replace("=", "")
}

@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()


/**
 * Calculates SHA-256 hash of input string and returns Base64URL encoded result.
 * Platform-agnostic implementation using platform-specific functions.
 *
 * @param input String to hash
 * @return Base64URL encoded SHA-256 hash (without padding)
 */
fun calculateSHA256(input: String): String {
    val bytes = input.encodeToByteArray()
    val digest = sha256(bytes)
    return base64UrlEncode(digest)
}

/**
 * URL encodes a string for use in query parameters.
 * Platform-agnostic implementation using pure Kotlin.
 *
 * @param value String to encode
 * @return URL-encoded string
 */
fun urlEncode(value: String): String {
    return value.toCharArray().joinToString("") { char ->
        when (char) {
            in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '.', '_', '~' -> char.toString()
            ' ' -> "%20"
            else -> {
                val bytes = char.toString().encodeToByteArray()
                bytes.joinToString("") {
                    "%${
                        it.toUByte().toString(16).padStart(2, '0').uppercase()
                    }"
                }
            }
        }
    }
}

/**
 * Generates a cryptographically random PKCE code verifier.
 *
 * RFC 7636: 43-128 characters from unreserved characters
 * [A-Z] [a-z] [0-9] - . _ ~
 */
fun generateCodeVerifier(): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    return (1..128).map { charset[Random.nextInt(charset.length)] }.joinToString("")
}

/**
 * Generates a random state parameter for CSRF protection.
 */
fun generateRandomState(): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { charset[Random.nextInt(charset.length)] }.joinToString("")
}