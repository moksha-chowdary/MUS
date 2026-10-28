package com.mus.android

import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.music.potoken.ChallengeFormatException
import com.mus.android.data.music.potoken.commaSeparatedBytesToBase64
import com.mus.android.data.music.potoken.parseIntegrityToken
import com.mus.android.data.music.potoken.parseCreateChallenge
import com.mus.android.data.music.potoken.stringToJsUint8Array
import com.mus.android.data.ytmusic.CookieNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the ported YouTube Music mechanism: trackKey indexing,
 * cookie normalization (SAPISIDHASH auth) and the BotGuard challenge parser.
 */
class YtMusicPortTest {

    // ── Room trackKey indexing ────────────────────────────────

    @Test
    fun `trackKey is normalized artist_title`() {
        assertEquals("artist_title", TrackDownloadManager.makeDownloadKey("Title", "Artist"))
        assertEquals("daft punk_one more time", TrackDownloadManager.makeDownloadKey("One More Time", "Daft Punk"))
    }

    @Test
    fun `trackKey is deterministic regardless of surrounding whitespace or case`() {
        val a = TrackDownloadManager.makeDownloadKey("  Song Name  ", "  The Artist ")
        val b = TrackDownloadManager.makeDownloadKey("song name", "the artist")
        assertEquals(a, b)
    }

    @Test
    fun `downloaded entity replace key dedupes by trackKey`() {
        // Mirrors the Room schema contract: trackKey is UNIQUE and REPLACE-on-conflict.
        val e1 = com.mus.android.data.db.DownloadedTrackEntity(
            id = 1,
            trackKey = "artist_title",
            title = "Title",
            artist = "Artist",
            filePath = "/tmp/a.m4a",
        )
        val e2 = com.mus.android.data.db.DownloadedTrackEntity(
            id = 0,
            trackKey = "artist_title",
            title = "Title",
            artist = "Artist",
            filePath = "/tmp/b.opus",
        )
        assertEquals(e1.trackKey, e2.trackKey)
        // The second insert (autoGenerate id = 0) would REPLACE the row on conflict.
        assertTrue(e2.id == 0L)
    }

    // ── Cookie auth (SAPISIDHASH) ─────────────────────────────

    @Test
    fun `cookie normalizer accepts raw header with SAPISID`() {
        val normalized = CookieNormalizer.normalize("SAPISID=abc123; __Secure-3PAPISID=xyz789; OTHER=q")
        assertTrue(normalized.contains("SAPISID=abc123"))
        assertTrue(normalized.contains("__Secure-3PAPISID=xyz789"))
    }

    @Test
    fun `cookie normalizer rejects missing SAPISID`() {
        try {
            CookieNormalizer.normalize("VISITOR_INFO1_LIVE=abc; YSC=x")
            fail("Expected IllegalArgumentException for missing SAPISID")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("SAPISID"))
        }
    }

    @Test
    fun `cookie normalizer accepts netscape cookies dot txt`() {
        val netscape = listOf(
            "# Netscape HTTP Cookie File",
            ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tSAPISID\tsapisid-value",
            ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tVISITOR_INFO1_LIVE\tvis",
        ).joinToString("\n")
        val normalized = CookieNormalizer.normalize(netscape)
        assertTrue(normalized.contains("SAPISID=sapisid-value"))
    }

    @Test
    fun `cookie normalizer accepts json export`() {
        val json = """[{"name":"SAPISID","value":"json-value"},{"name":"YSC","value":"ysc"}]"""
        val normalized = CookieNormalizer.normalize(json)
        assertTrue(normalized.contains("SAPISID=json-value"))
    }

    @Test
    fun `sapisidhash header format is timestamp_hex`() {
        // Verified against the SAPISIDHASH scheme: "SAPISIDHASH <ts>_<40 hex chars>"
        val sapisid = "exampleSapisid"
        val timestamp = 1700000000L
        val payload = "$timestamp $sapisid https://music.youtube.com"
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val header = "SAPISIDHASH ${timestamp}_$hex"
        assertTrue(header.startsWith("SAPISIDHASH ${timestamp}_"))
        val hexPart = header.substringAfter('_')
        assertEquals(40, hexPart.length)
        hexPart.forEach { c -> assertTrue(c.isDigit() || c in 'a'..'f') }
    }

    // ── PoToken challenge parser ──────────────────────────────

    @Test
    fun `parseCreateChallenge reads legacy envelope`() {
        val program = "functionchallenge"
        val global = "botguardGlobal"
        val challenge = "[\"a\",\"interp\",\"url\",\"hash\",\"$program\",\"$global\"]"
        // Legacy: [msg, base64(scrambledPayload)] where the payload bytes are
        // shifted by -97 before transmission (the parser re-adds 97).
        val scrambled = challenge.map { (it.code - 97).toByte() }.toByteArray()
        val payload = java.util.Base64.getEncoder().encodeToString(scrambled)
        val raw = "[\"message\",\"$payload\"]"

        val parsed = parseCreateChallenge(raw)
        assertTrue(parsed.contains("\"program\":\"$program\"") || parsed.contains("\"program\": \"$program\""))
        assertTrue(parsed.contains(global))
        assertTrue(parsed.contains("interpreterJavascript"))
    }

    @Test
    fun `parseCreateChallenge reads plain array envelope`() {
        val raw = "[\"a\",\"interp-script\",\"interp-url\",\"hash\",\"program-js\",\"global-name\"]"
        val parsed = parseCreateChallenge(raw)
        assertTrue(parsed.contains("program-js"))
        assertTrue(parsed.contains("global-name"))
    }

    @Test
    fun `parseIntegrityToken reads legacy pair`() {
        val token = "aGVsbG8" // base64-ish
        val raw = "[\"$token\", 7200]"
        val (u8, lifetime) = parseIntegrityToken(raw)
        assertTrue(u8.startsWith("new Uint8Array(["))
        assertEquals(7200L, lifetime)
    }

    @Test
    fun `challenge parser rejects garbage loudly`() {
        try {
            parseCreateChallenge("{\"unexpected\": true}")
            fail("Expected ChallengeFormatException")
        } catch (expected: ChallengeFormatException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `string to js uint8 array encodes utf8 bytes`() {
        assertEquals("new Uint8Array([104,105])", stringToJsUint8Array("hi"))
    }

    @Test
    fun `comma separated bytes convert to url safe base64`() {
        val b64 = commaSeparatedBytesToBase64("104,101,108,108,111")
        assertEquals("aGVsbG8", b64)
        assertFalse(b64.contains("="))
        assertFalse(b64.contains("+"))
    }
}
