package com.mus.android.data.classifier

import com.mus.android.data.model.Track
import java.util.Locale

object LanguageClassifier {

    const val HINDI = "Hindi"
    const val ENGLISH = "English"
    const val REGIONAL = "Regional" // Telugu, Tamil, Malayalam, Kannada, etc.

    const val PLAYLIST_HINDI = "Hindi"
    const val PLAYLIST_ENGLISH = "English"
    const val PLAYLIST_REGIONAL = "Regional (Telugu, Tamil, Malayalam)"

    val DEFAULT_PLAYLIST_NAMES = listOf(
        PLAYLIST_HINDI,
        PLAYLIST_ENGLISH,
        PLAYLIST_REGIONAL
    )

    private val HINDI_SCRIPTS = listOf(
        Character.UnicodeBlock.DEVANAGARI
    )

    private val REGIONAL_SCRIPTS = listOf(
        Character.UnicodeBlock.TELUGU,
        Character.UnicodeBlock.TAMIL,
        Character.UnicodeBlock.MALAYALAM,
        Character.UnicodeBlock.KANNADA,
        Character.UnicodeBlock.GURMUKHI,
        Character.UnicodeBlock.BENGALI,
        Character.UnicodeBlock.GUJARATI,
        Character.UnicodeBlock.ORIYA
    )

    private val HINDI_KEYWORDS = setOf(
        "hindi", "bollywood", "arijit", "shreya", "kishore", "lata", "rafi", "kumar sanu",
        "alka yagnik", "sonu nigam", "udit narayan", "pritam", "neha kakkar", "badshah",
        "jubin", "atif aslam", "darshan raval", "sachin jigar", "mithoon", "amit trivedi",
        "vishal shekhar", "sunidhi", "arman malik", "armaan malik", "mohit chauhan",
        "b praak", "himesh", "mika singh", "shankar ehsaan", "honey singh", "raftaar",
        "papon", "t-series", "tseries", "zee music", "yash raj", "yrf", "jagjit", "gulzar",
        "rahat fateh", "nusrat", "alisha chinai", "lucky ali", "shaan", "adnan sami",
        "kailash kher", "anuradha paudwal", "abhijeet", "dil", "pyar", "ishq", "tera", "meri",
        "tum hi ho", "channa mereya", "kesariya", "kabira", "kalank", "shayad", "raataan lambiyan",
        "apna bana le", "chaleya", "jhoome jo pathaan", "lut gaye", "bekhayali", "ghungroo",
        "khairiyat", "tujhe kitna chahein", "pal pal", "lag ja gale", "roop tera mastana"
    )

    private val REGIONAL_KEYWORDS = setOf(
        // Languages & Industries
        "telugu", "tamil", "malayalam", "kannada", "punjabi", "marathi", "tollywood", "kollywood", "mollywood",
        // Telugu Artists & Keywords
        "dsp", "devi sri prasad", "thaman", "thaman s", "sid sriram", "anurag kulkarni",
        "ram miriyala", "mangli", "geetha madhuri", "spb", "balasubrahmanyam", "koti",
        "keeravani", "m.m. keeravani", "ghibran", "samajavaragamana", "butta bomma", "ramuloo",
        "saranga dariya", "oo antava", "srivalli", "kurchi madathapetti", "naatu naatu",
        "allu arjun", "mahesh babu", "prabhas", "ntr", "chiranjeevi", "pawankalyan",
        // Tamil Artists & Keywords
        "anirudh", "ravichander", "ar rahman", "a.r. rahman", "rahman", "ilaiyaraaja", "ilayaraja",
        "harris jayaraj", "yuvan", "yuvan shankar raja", "santhosh narayanan", "dhee", "dhanush",
        "gv prakash", "hiphop tamizha", "imman", "vidyasagar", "arabic kuthu", "vaathi", "rowdy baby",
        "why this kolaveri", "naan pizhai", "kaavaalaa", "hukum", "badass", "leo", "jailer", "vikram",
        // Malayalam Artists & Keywords
        "sushin shyam", "gopi sundar", "shaan rahman", "yesudas", "k.j. yesudas", "chithra", "k.s. chithra",
        "vineeth sreenivasan", "job kurian", "rex vijayan", "deepak dev", "bijibal", "premam", "malare",
        "illuminati", "aavesham", "manjummel", "premalu", "aadu", "hridayam", "darshana",
        // Punjabi & Other Regional
        "diljit", "dosanjh", "ap dhillon", "karan aujla", "shubh", "sidhu moose", "amrinder gill",
        "guru randhawa", "jassi gill", "hardy sandhu", "parmish verma", "amrit maan", "sunanda"
    )

    private val ENGLISH_KEYWORDS = setOf(
        "english", "hollywood", "billboard", "pop", "taylor swift", "ed sheeran", "drake",
        "the weeknd", "billie eilish", "eminem", "bruno mars", "dua lipa", "post malone",
        "justin bieber", "rihanna", "beyonce", "coldplay", "imagine dragons", "adele",
        "maroon 5", "shawn mendes", "ariana grande", "charlie puth", "selena gomez",
        "michael jackson", "queen", "beatles", "linkin park", "katy perry", "harry styles",
        "olivia rodrigo", "sam smith", "snoop dogg", "kendrick lamar", "travis scott",
        "twenty one pilots", "chainsmokers", "marshmello", "david guetta", "calvin harris",
        "alan walker", "avicii", "kygo", "sia", "halsey", "camila cabello"
    )

    /**
     * Classifies a Track into Hindi, English, or Regional.
     */
    fun classify(title: String, artist: String, album: String, path: String?): String {
        val combined = "$title $artist $album ${path ?: ""}".lowercase(Locale.ROOT)

        // 1. Script checks
        var hasHindiScript = false
        var hasRegionalScript = false

        for (ch in combined) {
            val block = Character.UnicodeBlock.of(ch)
            if (block in HINDI_SCRIPTS) {
                hasHindiScript = true
                break
            } else if (block in REGIONAL_SCRIPTS) {
                hasRegionalScript = true
                break
            }
        }

        if (hasHindiScript) return HINDI
        if (hasRegionalScript) return REGIONAL

        // 2. Path / Folder specific checks
        if (path != null) {
            val lowerPath = path.lowercase(Locale.ROOT)
            if (lowerPath.contains("/hindi/") || lowerPath.contains("\\hindi\\") || lowerPath.contains("bollywood")) {
                return HINDI
            }
            if (lowerPath.contains("/telugu/") || lowerPath.contains("\\telugu\\") ||
                lowerPath.contains("/tamil/") || lowerPath.contains("\\tamil\\") ||
                lowerPath.contains("/malayalam/") || lowerPath.contains("\\malayalam\\") ||
                lowerPath.contains("/kannada/") || lowerPath.contains("\\kannada\\") ||
                lowerPath.contains("/punjabi/") || lowerPath.contains("\\punjabi\\")
            ) {
                return REGIONAL
            }
            if (lowerPath.contains("/english/") || lowerPath.contains("\\english\\")) {
                return ENGLISH
            }
        }

        // 3. Keyword matching (prioritize explicit artist/title hints)
        // Check Regional keywords
        for (kw in REGIONAL_KEYWORDS) {
            if (containsWordOrPhrase(combined, kw)) {
                return REGIONAL
            }
        }

        // Check Hindi keywords
        for (kw in HINDI_KEYWORDS) {
            if (containsWordOrPhrase(combined, kw)) {
                return HINDI
            }
        }

        // Check English keywords
        for (kw in ENGLISH_KEYWORDS) {
            if (containsWordOrPhrase(combined, kw)) {
                return ENGLISH
            }
        }

        // 4. Fallback heuristics
        // If it looks predominantly latin without Indian indicators, classify as English
        return ENGLISH
    }

    private fun containsWordOrPhrase(text: String, word: String): Boolean {
        return text.contains(word)
    }
}
