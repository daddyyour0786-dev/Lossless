package com.lossless.app

import com.lossless.app.data.sources.addon.AddonClient
import com.lossless.app.data.sources.addon.AddonSigning
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The LastWave addon gate, at the byte level.
 *
 * Two things are being checked and they are different questions. [AddonSigning]'s
 * own arithmetic has to produce the exact digest the addon server expects — a
 * single wrong character in the message layout is a request that is refused for
 * reasons invisible from the app — so the digests below are fixed values computed
 * independently of this code, not values this code happened to produce.
 *
 * Then the client has to actually put them on the wire, for the token-bearing
 * URLs and not for the plain ones, because a signature that is computed and
 * never sent looks identical to a working one from inside the app.
 */
class AddonSigningTest {

    private lateinit var server: MockWebServer

    private val secret = "36d96a751b12ee481c281a8a8e64c482d0c1634a22061ab72f40175017818b85"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── The token ─────────────────────────────────────────────────────────

    @Test
    fun `finds the token in the path`() {
        assertEquals(
            "abc123",
            AddonSigning.token("https://addons.example/a/abc123"),
        )
        assertEquals(
            "abc123",
            AddonSigning.token("https://addons.example/a/abc123/search?q=x"),
        )
    }

    @Test
    fun `an addon with no token in its path is not an error`() {
        // The store's own public endpoints look like this, and they work.
        assertNull(AddonSigning.token("https://addons.example/search?q=x"))
        assertNull(AddonSigning.token("https://addons.example/manifest.json"))
        assertNull(AddonSigning.token(""))
    }

    // ── The signature ─────────────────────────────────────────────────────

    @Test
    fun `signs a stream request exactly as the addon expects`() {
        // HMAC-SHA256 over "1700000000\nGET\n/a/abc123/stream/456\nabc123".
        assertEquals(
            "e66d55ae4d3f43b78eff9c074960428380b6a18d81e28d56e83036ab1815b738",
            AddonSigning.sign(
                url = "https://addons.example/a/abc123/stream/456?quality=lossless",
                method = "GET",
                secret = secret,
                epochSeconds = 1_700_000_000L,
            ),
        )
    }

    @Test
    fun `signs a search request exactly as the addon expects`() {
        assertEquals(
            "cc90954e083bd632ba915ba56f748f3b44320fc063ee8b69a8880406f1317adb",
            AddonSigning.sign(
                url = "https://addons.example/a/abc123/search?q=kesariya",
                method = "GET",
                secret = secret,
                epochSeconds = 1_700_000_000L,
            ),
        )
    }

    @Test
    fun `the query string is not part of the signed message`() {
        // The path is signed; the parameters are not. Two searches of the same
        // endpoint must sign identically or every query would be its own secret.
        val a = AddonSigning.sign("https://h/a/t/search?q=one", "GET", secret, 1_700_000_000L)
        val b = AddonSigning.sign("https://h/a/t/search?q=two", "GET", secret, 1_700_000_000L)
        assertEquals(a, b)
        assertNotNull(a)
    }

    @Test
    fun `nothing is signed for an addon without a token`() {
        assertNull(AddonSigning.sign("https://addons.example/search", "GET", secret, 1_700_000_000L))
        assertEquals(
            emptyMap<String, String>(),
            AddonSigning.headers("https://addons.example/search", "GET", secret),
        )
    }

    @Test
    fun `headers carry the timestamp, the signature and the intent`() {
        val headers = AddonSigning.headers(
            url = "https://addons.example/a/abc123/stream/456",
            method = "GET",
            secret = secret,
            intent = AddonSigning.INTENT_DOWNLOAD,
            nowMs = 1_700_000_000_000L,
        )
        assertEquals("1700000000", headers[AddonSigning.TS_HEADER])
        assertEquals("download", headers[AddonSigning.INTENT_HEADER])
        assertNotNull(headers[AddonSigning.SIGN_HEADER])
    }

    // ── On the wire ───────────────────────────────────────────────────────

    @Test
    fun `the client signs its requests to a token-bearing addon`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path.orEmpty().contains("manifest.json") ->
                        """{"id":"com.lastwave.addon","name":"LastWave","version":"1.0.0",
                           "resources":["search","stream"]}"""
                    request.path.orEmpty().contains("/search") ->
                        """{"tracks":[{"id":"1","title":"Kesariya","artist":"Pritam"}]}"""
                    else -> """{"url":"https://cdn.example/1.flac","format":"flac"}"""
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }

        val client = AddonClient("${server.url("/a/abc123").toString().trimEnd('/')}", secret)
        val found = client.search("kesariya", AddonClient.TIER_LOSSLESS)
        assertEquals(1, found.getOrThrow().size)

        val requests = (0 until server.requestCount).map { server.takeRequest() }
        val search = requests.first { it.path.orEmpty().contains("/search") }
        assertNotNull("a token-bearing addon must be signed", search.getHeader(AddonSigning.SIGN_HEADER))
        assertNotNull(search.getHeader(AddonSigning.TS_HEADER))
        assertEquals("stream", search.getHeader(AddonSigning.INTENT_HEADER))
    }

    @Test
    fun `an addon without a token is called unsigned, as before`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setBody("""{"tracks":[{"id":"1","title":"Kesariya","artist":"Pritam"}]}""")
        }

        val client = AddonClient(server.url("/").toString().trimEnd('/'), secret)
        assertEquals(1, client.search("kesariya", AddonClient.TIER_LOSSLESS).getOrThrow().size)
        val request = server.takeRequest()
        assertNull(request.getHeader(AddonSigning.SIGN_HEADER))
    }
}
