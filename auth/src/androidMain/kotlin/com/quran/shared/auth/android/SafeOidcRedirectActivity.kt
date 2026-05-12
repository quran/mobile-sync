package com.quran.shared.auth.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import io.ktor.http.Url
import org.publicvalue.multiplatform.oidc.appsupport.HandleRedirectActivity

/**
 * Exported OAuth redirect entry point that isolates browser callbacks from the OIDC library's
 * internal browser-launch activity.
 *
 * Android apps must expose this activity for the configured OAuth redirect scheme and host. The
 * upstream [HandleRedirectActivity] remains non-exported because it accepts internal launch extras
 * that can open arbitrary URLs when supplied by another app.
 */
class SafeOidcRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(source: Intent?) {
        val redirectUri = SafeOidcRedirectActivityContract.sanitizeRedirectUri(
            source = source,
            isRegisteredRedirect = ::isRegisteredRedirectUri
        )

        if (redirectUri != null) {
            try {
                startActivity(
                    SafeOidcRedirectActivityContract.createForwardIntent(
                        context = this,
                        redirectUri = redirectUri
                    )
                )
            } catch (_: ActivityNotFoundException) {
                // Reject safely if the upstream OIDC activity is unavailable in a broken manifest.
            } catch (_: SecurityException) {
                // Reject safely if another manifest rule unexpectedly blocks the internal handoff.
            }
        }

        finish()
    }

    private fun isRegisteredRedirectUri(uri: Uri): Boolean {
        val validationIntent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(packageName)
        }

        return packageManager.queryIntentActivitiesCompat(
            intent = validationIntent,
            flags = PackageManager.MATCH_DEFAULT_ONLY
        ).any { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            activityInfo.packageName == packageName &&
                activityInfo.name == SafeOidcRedirectActivity::class.java.name
        }
    }
}

/**
 * Validation and forwarding helpers for [SafeOidcRedirectActivity].
 *
 * The helpers are kept side-effect free except for [createForwardIntent] so host tests can prove the
 * exported proxy never forwards attacker-controlled extras, flags, selectors, clip data, or
 * categories to the upstream OIDC activity.
 */
internal object SafeOidcRedirectActivityContract {
    /**
     * Returns the redirect URI only when it is safe to hand to the upstream OIDC activity.
     *
     * @param source incoming external activity intent.
     * @param isRegisteredRedirect verifies that the URI matches this app's exported redirect
     * activity filter, including scheme and host configured by the manifest.
     * @return the original URI when valid, or null when the redirect must be rejected.
     */
    fun sanitizeRedirectUri(
        source: Intent?,
        isRegisteredRedirect: (Uri) -> Boolean
    ): Uri? {
        val redirectUri = source?.data ?: return null
        if (source.action != Intent.ACTION_VIEW) return null
        if (!isParsableByOidc(redirectUri)) return null
        if (!isRegisteredRedirect(redirectUri)) return null

        return redirectUri
    }

    /**
     * Creates the only intent allowed to reach [HandleRedirectActivity] from the exported proxy.
     *
     * @param context context used only to resolve the explicit component.
     * @param redirectUri sanitized OAuth redirect URI to pass as intent data.
     * @return a fresh explicit intent with no extras, flags, selector, clip data, action, or
     * categories.
     */
    fun createForwardIntent(context: Context, redirectUri: Uri): Intent {
        return Intent(context, HandleRedirectActivity::class.java).setData(redirectUri)
    }

    private fun isParsableByOidc(redirectUri: Uri): Boolean {
        return try {
            Url(redirectUri.toString())
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun PackageManager.queryIntentActivitiesCompat(
    intent: Intent,
    flags: Int
): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags)
    }
}
