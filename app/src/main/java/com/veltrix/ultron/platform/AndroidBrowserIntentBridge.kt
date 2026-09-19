package com.veltrix.ultron.platform

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import com.veltrix.ultron.devices.UniversalActionResult
import com.veltrix.ultron.devices.UniversalActionType
import com.veltrix.ultron.executor.HttpNavigationPolicy
import java.net.URI
import java.util.Locale

/** Native browser Intent transport for policy-approved web navigation. */
object AndroidBrowserIntentBridge {
    private const val CHROME_PACKAGE = "com.android.chrome"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun dispatch(rawUrl: String?): UniversalActionResult {
        val normalized = HttpNavigationPolicy.normalize(rawUrl)
            ?: return fail("Browser navigation requires HTTPS or loopback HTTP")
        val context = appContext
            ?: return fail("Android browser transport is not initialized")
        val parsed = runCatching { URI(normalized) }.getOrNull()
            ?: return fail("Browser URL could not be parsed")
        val host = parsed.host?.lowercase(Locale.ROOT)
            ?: return fail("Browser URL host is unavailable")
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            ?: return fail("Browser URL scheme is unavailable")

        return runCatching {
            val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val chromeIntent = Intent(baseIntent).setPackage(CHROME_PACKAGE)
            val usedChrome = runCatching {
                context.startActivity(chromeIntent)
                true
            }.getOrElse { error ->
                if (error !is ActivityNotFoundException) throw error
                context.startActivity(baseIntent)
                false
            }
            UniversalActionResult(
                accepted = true,
                action = UniversalActionType.BROWSER_NAVIGATE,
                message = if (usedChrome) "Chrome navigation requested" else "Browser navigation requested",
                evidence = mapOf(
                    "scheme" to scheme,
                    "host" to host,
                    "browser" to if (usedChrome) CHROME_PACKAGE else "system-default"
                )
            )
        }.getOrElse { error ->
            fail("Browser navigation failed: ${error.javaClass.simpleName}")
        }
    }

    private fun fail(message: String) = UniversalActionResult(
        accepted = false,
        action = UniversalActionType.BROWSER_NAVIGATE,
        message = message
    )
}
