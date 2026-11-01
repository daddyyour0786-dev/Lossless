package com.lossless.app.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 */
object JamInvite {

    const val ORIGIN = "https://daddyyour0786-dev.github.io"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/invite/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val rest = text.removePrefix(prefix)
        // /invite/<CODE> (path) and /invite/?code=<CODE> (query) both count. The
        // path shape is what a verified Android App Link matches; the query shape
        // is what the site can serve, since a static host has no page at a path
        // segment and answers 404 for one.
        val code = if (rest.startsWith("?")) {
            rest.substringAfter("code=", "").substringBefore('&').substringBefore('#')
        } else {
            rest.substringBefore('?').substringBefore('#')
        }
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/?code=${code.uppercase()}"
}
