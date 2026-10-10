package com.abdownloadmanager.shared.util

/**
 * The browser extensions (WebDM Integration builds) the app trusts. This is the ONE place to list them: it is used
 * for the native-messaging host manifest's "allowed_origins" (the browser only lets these extensions start the
 * host) and by the host itself, which refuses callers that aren't listed here.
 *
 * To trust another build (for example one published in a browser's extension store), add its ID (32 letters a-p)
 * to [ids], then rebuild and release the app. Entries that aren't valid IDs are ignored.
 */
object TrustedExtensions {
    /** unpacked GitHub release build (ID pinned by the manifest "key") */
    const val UNPACKED_ID = "aciclgnjjaijdpnkkgileppmbpldobjj"

    private val idPattern = Regex("^[a-p]{32}$")

    val ids: List<String> = listOf(UNPACKED_ID)
        .filter { idPattern.matches(it) }

    /** "chrome-extension://<id>/" for each trusted ID (the scheme Chromium browsers use) */
    val origins: List<String> = ids.map { "chrome-extension://$it/" }

    fun isTrustedOrigin(origin: String): Boolean {
        return origins.contains(origin.trimEnd('/') + "/")
    }
}
