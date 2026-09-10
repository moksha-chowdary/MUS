package com.mus.android

import com.mus.android.data.classifier.LanguageClassifier
import org.junit.Assert.*
import org.junit.Test

class LanguageClassifierTest {

    @Test
    fun testClassifyHindiByKeyword() {
        val result = LanguageClassifier.classify(
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            path = "/Muzic/hindi/song.mp3"
        )
        assertEquals(LanguageClassifier.HINDI, result)
    }

    @Test
    fun testClassifyRegionalTeluguByKeyword() {
        val result = LanguageClassifier.classify(
            title = "Samajavaragamana",
            artist = "Sid Sriram",
            album = "Ala Vaikunthapurramuloo",
            path = "/Muzic/telugu/song.mp3"
        )
        assertEquals(LanguageClassifier.TELUGU, result)
    }

    @Test
    fun testClassifyRegionalTamilByKeyword() {
        val result = LanguageClassifier.classify(
            title = "Arabic Kuthu",
            artist = "Anirudh Ravichander",
            album = "Beast",
            path = "/Muzic/tamil/song.mp3"
        )
        assertEquals(LanguageClassifier.TAMIL, result)
    }

    @Test
    fun testClassifyEnglishByKeyword() {
        val result = LanguageClassifier.classify(
            title = "Blank Space",
            artist = "Taylor Swift",
            album = "1989",
            path = "/Muzic/english/song.mp3"
        )
        assertEquals(LanguageClassifier.ENGLISH, result)
    }

    @Test
    fun testEnglishSongByIndianArtistNotForcedToHindi() {
        // Track-level classification: An Indian artist singing an English song with English title and English words
        val detailed = LanguageClassifier.classifyDetailed(
            title = "I Love You More Than Words Can Say",
            artist = "Arijit Singh",
            album = "English Special",
            path = "/Muzic/song.mp3"
        )
        assertEquals(LanguageClassifier.ENGLISH, detailed.language)
    }

    @Test
    fun testExplicitEmbeddedLanguageMetadata() {
        val detailed = LanguageClassifier.classifyDetailed(
            title = "Random Title",
            artist = "Random Artist",
            embeddedLanguage = "hin"
        )
        assertEquals(LanguageClassifier.HINDI, detailed.language)
        assertEquals(LanguageClassifier.Confidence.HIGH, detailed.confidence)

        val detailedEng = LanguageClassifier.classifyDetailed(
            title = "Random Title",
            artist = "Random Artist",
            embeddedLanguage = "en-US"
        )
        assertEquals(LanguageClassifier.ENGLISH, detailedEng.language)
        assertEquals(LanguageClassifier.Confidence.HIGH, detailedEng.confidence)
    }

    @Test
    fun testUnicodeDevanagariScriptDetection() {
        val detailed = LanguageClassifier.classifyDetailed(
            title = "तुम ही हो",
            artist = "अरिजीत सिंह",
        )
        assertEquals(LanguageClassifier.HINDI, detailed.language)
        assertEquals(LanguageClassifier.Confidence.HIGH, detailed.confidence)
    }

    @Test
    fun testUnicodeRegionalScriptDetection() {
        val detailed = LanguageClassifier.classifyDetailed(
            title = "సామజవరగమన",
            artist = "సిద్ శ్రీరామ్",
        )
        assertEquals(LanguageClassifier.TELUGU, detailed.language)
        assertEquals(LanguageClassifier.Confidence.HIGH, detailed.confidence)
    }

    @Test
    fun testLanguageToSystemKeyMapping() {
        assertEquals("HINDI", LanguageClassifier.languageToSystemKey("Hindi"))
        assertEquals("ENGLISH", LanguageClassifier.languageToSystemKey("English"))
        assertEquals("TELUGU", LanguageClassifier.languageToSystemKey("Telugu"))
        assertEquals("TAMIL", LanguageClassifier.languageToSystemKey("Tamil"))
        assertEquals("OTHER", LanguageClassifier.languageToSystemKey("Spanish"))
        assertEquals("OTHER", LanguageClassifier.languageToSystemKey(null))
        assertEquals(5, LanguageClassifier.SYSTEM_PLAYLISTS.size)
    }

    @Test
    fun testUncertainTrackRemainsUnknown() {
        val detailed = LanguageClassifier.classifyDetailed(
            title = "Track 01",
            artist = "",
            path = "/Muzic/Track01.mp3"
        )
        assertEquals(LanguageClassifier.UNKNOWN, detailed.language)
        assertEquals(LanguageClassifier.Confidence.LOW, detailed.confidence)
    }

    @Test
    fun testDefaultPlaylistRecognition() {
        assertTrue(LanguageClassifier.isDefaultPlaylist("Hindi"))
        assertTrue(LanguageClassifier.isDefaultPlaylist("English"))
        assertTrue(LanguageClassifier.isDefaultPlaylist("Regional (Telugu, Tamil, Malayalam)"))
        assertFalse(LanguageClassifier.isDefaultPlaylist("My Workout Jam"))
        assertFalse(LanguageClassifier.isDefaultPlaylist("Late Night Vibes"))
    }
}
