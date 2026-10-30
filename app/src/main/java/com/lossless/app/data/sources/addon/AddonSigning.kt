package com.lossless.app.data.sources.addon

import com.lossless.app.data.TrackLog
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The LastWave addon gate.
 *
 * A LastWave addon — the ones handed out at lastwaveaddons.clashprojects.qd.je,
 * and anything else speaking that dialect — puts a listener's private token in
 * the *path*:
 *
 *     https://lastwaveaddons.clashprojects.qd.je/a/<token>
 *     https://lastwaveaddons.clashprojects.qd.je/a/<token>/search?q=…
 *
 * and proves each request is really for that token by signing it with a key
 * shared between the client and the addon server. Two headers carry the proof:
 * [TS_HEADER] is the Unix time in seconds, and [SIGN_HEADER] is the hex
 * HMAC-SHA256 of
 *
 *     <ts> "\n" <METHOD> "\n" <path> "\n" <token>
 *
 * keyed with that shared secret. The addon recomputes it, rejects the request if
 * it does not match, and rejects it again if the timestamp is too far from its
 * own clock — which is what stops a captured request being replayed forever.
 *
 * This is the whole of what made BitChord's addon support fail against these
 * servers: its client sent the URL and nothing else, so every call to a
 * token-bearing addon came back refused. Nothing else about the protocol
 * differs — same `/manifest.json`, `/search?q=`, `/stream/{id}` JSON — which is
 * why the fix is a signing interceptor rather than a second client.
 *
 * The secret is shared with every LastWave install, so it is not a security
 * boundary against the listener — it is what stops a token leaked in a screenshot
 * from being used by someone who does not have the client. It lives in
 * [com.lossless.app.BuildConfig.ADDON_SIGNING_SECRET] so a deployment can point
 * Lossless at an addon server with a different key without touching code.
 */
internal object AddonSigning {

    /** Unix time, in seconds, that the signature was made. */
    const val TS_HEADER = "X-LW-TS"

    /** Hex HMAC-SHA256 of the request, keyed with the shared secret. */
    const val SIGN_HEADER = "X-LW-Sign"

    /**
     * Whether the addon is being asked for audio to play or to keep.
     *
     * LastWave's servers meter the two differently — a download spends quota of
     * its own — and an addon that has never heard of this header ignores it.
     */
    const val INTENT_HEADER = "X-LW-Intent"

    const val INTENT_STREAM = "stream"
    const val INTENT_DOWNLOAD = "download"

    private const val TAG = "Lossless"

    /** `/a/<token>` anywhere in the path, which is where these addons put the token. */
    private val TOKEN_IN_PATH = Regex("""/a/([^/]+)""")

    private const val HMAC = "HmacSHA256"

    /**
     * The token this URL carries, or null when it carries none.
     *
     * Null is not a failure. An addon server that needs no token is a perfectly
     * ordinary addon — the public endpoints on the LastWave store are exactly
     * that — and it must keep working, so the absence of a token means "send
     * this one unsigned" rather than "refuse".
     */
    fun token(url: String): String? = runCatching {
        TOKEN_IN_PATH.find(URI(url).path ?: return null)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The signature for one request, or null when there is nothing to sign.
     *
     * The path is signed *decoded*, matching how the addon's own reference client
     * computes it: a token with a `%` in it must hash to the same bytes on both
     * sides, and percent-encoding is the transport's business, not the
     * signature's.
     */
    fun sign(url: String, method: String, secret: String, epochSeconds: Long): String? {
        val token = token(url) ?: return null
        if (secret.isBlank()) return null
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val message = "$epochSeconds\n${method.uppercase()}\n$path\n$token"
        return runCatching {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC))
            mac.doFinal(message.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.onFailure { failure ->
            // A JVM without HmacSHA256 has bigger problems, but one bad request
            // should still surface as a refused call rather than a crash.
            TrackLog.w(TAG, "could not sign addon request: ${failure.message}")
        }.getOrNull()
    }

    /**
     * Every header one request needs, ready to add to a [okhttp3.Request.Builder].
     *
     * Empty for an addon that carries no token, which is the unsigned case above
     * and, deliberately, the same empty map a signing failure produces: the
     * request then goes out as it always did, and the server decides.
     */
    fun headers(
        url: String,
        method: String,
        secret: String,
        intent: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(3)
        // Milliseconds to seconds, and the one number the addon's replay window
        // is measured against: if a device's clock is wrong, its signatures are
        // rejected until the clock is fixed.
        val ts = nowMs / 1000L
        sign(url, method, secret, ts)?.let { signature ->
            out[TS_HEADER] = ts.toString()
            out[SIGN_HEADER] = signature
        }
        if (intent != null) out[INTENT_HEADER] = intent
        return out
    }
}
