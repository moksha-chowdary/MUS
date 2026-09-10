package com.mus.android.data.classifier

import com.mus.android.data.model.MetadataConfidence
import java.util.Locale

data class LanguageClassification(
    val language: String,
    val confidence: String,
)

object LanguageClassifier {

    const val HINDI = "Hindi"
    const val ENGLISH = "English"
    const val TELUGU = "Telugu"
    const val TAMIL = "Tamil"
    const val OTHER = "Other"
    const val UNKNOWN = "Unknown"

    object SystemKey {
        const val HINDI = "HINDI"
        const val ENGLISH = "ENGLISH"
        const val TELUGU = "TELUGU"
        const val TAMIL = "TAMIL"
        const val OTHER = "OTHER"
    }

    data class SystemPlaylistDefinition(
        val systemKey: String,
        val name: String,
    )

    val SYSTEM_PLAYLISTS = listOf(
        SystemPlaylistDefinition(SystemKey.ENGLISH, "English"),
        SystemPlaylistDefinition(SystemKey.HINDI, "Hindi"),
        SystemPlaylistDefinition(SystemKey.TELUGU, "Telugu"),
        SystemPlaylistDefinition(SystemKey.TAMIL, "Tamil"),
        SystemPlaylistDefinition(SystemKey.OTHER, "Other (Regional & Global)"),
    )

    fun languageToSystemKey(language: String?): String {
        if (language == null) return SystemKey.OTHER
        return when (language.trim().lowercase(Locale.ROOT)) {
            "hindi" -> SystemKey.HINDI
            "english" -> SystemKey.ENGLISH
            "telugu" -> SystemKey.TELUGU
            "tamil" -> SystemKey.TAMIL
            else -> SystemKey.OTHER
        }
    }

    object Confidence {
        const val HIGH = MetadataConfidence.HIGH
        const val MEDIUM = MetadataConfidence.MEDIUM
        const val LOW = MetadataConfidence.LOW
    }

    // Backward-compatibility alias for tests and existing references
    const val REGIONAL = "Other"

    const val PLAYLIST_HINDI = "Hindi"
    const val PLAYLIST_ENGLISH = "English"
    const val PLAYLIST_TELUGU = "Telugu"
    const val PLAYLIST_TAMIL = "Tamil"
    const val PLAYLIST_OTHER = "Other (Regional & Global)"
    const val PLAYLIST_REGIONAL = "Regional (Telugu, Tamil, Malayalam)"

    val DEFAULT_PLAYLIST_NAMES = listOf(
        PLAYLIST_HINDI,
        PLAYLIST_ENGLISH,
        PLAYLIST_TELUGU,
        PLAYLIST_TAMIL,
        PLAYLIST_OTHER,
        PLAYLIST_REGIONAL
    )

    fun isDefaultPlaylist(name: String?): Boolean {
        if (name == null) return false
        return name in DEFAULT_PLAYLIST_NAMES ||
                name.startsWith("Regional (") ||
                name.startsWith("Other (") ||
                name == "Telugu" ||
                name == "Tamil"
    }

    private val HINDI_SCRIPTS = listOf(
        Character.UnicodeBlock.DEVANAGARI
    )

    private val REGIONAL_INDIAN_SCRIPTS = listOf(
        Character.UnicodeBlock.TELUGU,
        Character.UnicodeBlock.TAMIL,
        Character.UnicodeBlock.MALAYALAM,
        Character.UnicodeBlock.KANNADA,
        Character.UnicodeBlock.GURMUKHI,
        Character.UnicodeBlock.BENGALI,
        Character.UnicodeBlock.GUJARATI,
        Character.UnicodeBlock.ORIYA
    )

    private val OTHER_GLOBAL_SCRIPTS = listOf(
        Character.UnicodeBlock.ARABIC,
        Character.UnicodeBlock.CYRILLIC,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.THAI,
        Character.UnicodeBlock.GREEK
    )

    private val HINDI_GENRES = setOf(
        "bollywood", "filmi", "hindi", "devotional", "ghazal", "bhajan", "sufi", "qawwali"
    )

    private val OTHER_GENRES = setOf(
        "tollywood", "kollywood", "mollywood", "carnatic", "telugu", "tamil",
        "malayalam", "kannada", "punjabi", "bhangra", "marathi", "bengali",
        "gujarati", "k-pop", "kpop", "j-pop", "jpop", "latin", "reggaeton",
        "spanish", "afrobeats", "french", "german"
    )

    private val HINDI_TITLE_TOKENS = setOf(
        "dil", "pyaar", "pyar", "ishq", "tera", "teri", "tere", "mera", "meri", "mere",
        "tum", "tumhe", "tumhare", "hum", "humko", "humein", "humari", "hamari",
        "aaj", "kal", "raat", "raatan", "raatein", "din", "subah", "shaam", "kabhi",
        "nahi", "nahin", "mat", "kya", "kyun", "kyon", "kaise", "kahan", "jahan",
        "hoga", "hogi", "hota", "hoti", "hai", "hain", "hoon", "tha", "thi", "the",
        "jaan", "jaana", "jaane", "aao", "aaya", "aayi", "chal", "chale", "chalo",
        "deewana", "deewani", "musafir", "zindagi", "duniya", "khushi", "dard", "yaad", "yaadein",
        "saath", "paas", "door", "dekh", "dekha", "suno", "sun", "bol", "baat", "baatein",
        "saajna", "mahi", "mahiya", "ranjha", "heer", "sajna", "dholna", "naina", "aankhon",
        "khuda", "rabba", "duaa", "dua", "shukran", "alvida", "mohabbat", "aashiqui",
        "channa", "mereya", "kesariya", "kabira", "chaleya", "jhoome", "pathaan",
        "roop", "mastana", "mehbooba", "badtameez", "subhanallah", "bekhayali", "ghungroo",
        "khairiyat", "chahein", "chahta", "jeena", "marna", "awara", "shikayat", "fitoor",
        "rang", "barse", "apna", "bana", "le", "lut", "gaye", "pal", "lag", "gale",
        "shree", "ram", "krishna", "aarti", "chalisa", "hanuman", "shiv", "tandav",
        "barbaadiyan", "tere", "sang", "yaara", "kaun", "tujhe", "humsafar", "ban", "jaunga"
    )

    private val TELUGU_GENRES = setOf(
        "tollywood", "telugu", "carnatic telugu"
    )

    private val TAMIL_GENRES = setOf(
        "kollywood", "tamil", "carnatic tamil"
    )

    private val TELUGU_TITLE_TOKENS = setOf(
        "samajavaragamana", "butta", "bomma", "ramuloo", "saranga", "dariya", "antava",
        "srivalli", "kurchi", "madathapetti", "naatu", "keeravani", "thaman", "devi",
        "sri", "prasad", "anurag", "kulkarni", "sid", "sriram", "telugu", "tollywood"
    )

    private val TAMIL_TITLE_TOKENS = setOf(
        "arabic", "kuthu", "vaathi", "kolaveri", "pizhai", "kaavaalaa", "hukum",
        "badass", "anirudh", "ar", "rahman", "yuvan", "shankar", "raja", "ilayaraja",
        "tamil", "kollywood"
    )

    private val REGIONAL_TITLE_TOKENS = setOf(
        "aavesham", "illuminati", "manjummel", "premalu", "malare", "hridayam", "darshana",
        "aadu", "premam", "rowdy", "baby", "dhee"
    )

    private val ENGLISH_TITLE_TOKENS = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "with",
        "of", "from", "by", "about", "into", "through", "after", "over", "between",
        "out", "against", "during", "without", "before", "under", "around", "among",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "their", "our", "its", "mine", "yours", "hers", "theirs", "ours",
        "this", "that", "these", "those", "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "shall", "should",
        "can", "could", "may", "might", "must", "love", "night", "day", "heart", "girl", "boy",
        "baby", "time", "life", "world", "never", "always", "again", "forever", "tonight",
        "dance", "song", "sing", "feel", "feeling", "dream", "eyes", "summer", "winter",
        "good", "bad", "happy", "sad", "beautiful", "little", "big", "dont", "cant", "wont",
        "control", "blank", "space", "shape", "perfect", "blinding", "lights", "starboy",
        "levitating", "peaches", "positions", "stay", "industry", "habits", "shivers",
        "drivers", "license", "deja", "vu", "traitor", "happier", "ghost", "someone",
        "like", "rolling", "deep", "hello", "fire", "rain", "skyfall", "easy", "water",
        "save", "tears", "die", "sunflower", "circles", "rockstar", "congratulations",
        "believer", "thunder", "demons", "radioactive", "bad", "guy", "ocean", "eyes",
        "photograph", "thinking", "loud", "castle", "hill", "perfect", "happier", "galway"
    )

    /**
     * Detailed classification returning language and confidence.
     * Evaluates track-level signals strictly:
     * 1. Genre / embedded tags
     * 2. Unicode script detection on track title
     * 3. Track title token vocabulary (Hinglish vs English vs Telugu vs Tamil vs Regional)
     * 4. Path context heuristics
     * 5. Uncertain tracks -> UNKNOWN, LOW confidence (never forced to binary guess)
     */
    fun classifyDetailed(
        title: String,
        artist: String = "",
        album: String = "",
        path: String? = null,
        genre: String? = null,
        embeddedLanguage: String? = null
    ): LanguageClassification {
        val cleanTitle = title.trim()

        // 1. Explicit embedded language metadata (highest priority signal)
        if (!embeddedLanguage.isNullOrBlank()) {
            val lang = embeddedLanguage.lowercase(Locale.ROOT).trim()
            if (lang.startsWith("te") || lang.contains("tel") || lang.contains("telugu")) {
                return LanguageClassification(TELUGU, MetadataConfidence.HIGH)
            }
            if (lang.startsWith("ta") || lang.contains("tam") || lang.contains("tamil")) {
                return LanguageClassification(TAMIL, MetadataConfidence.HIGH)
            }
            if (lang.startsWith("hi") || lang.contains("hin") || lang.contains("hindi")) {
                return LanguageClassification(HINDI, MetadataConfidence.HIGH)
            }
            if (lang.startsWith("en") || lang.contains("eng") || lang.contains("english")) {
                return LanguageClassification(ENGLISH, MetadataConfidence.HIGH)
            }
            return LanguageClassification(OTHER, MetadataConfidence.HIGH)
        }

        // 2. Explicit genre analysis
        if (!genre.isNullOrBlank()) {
            val lowerGenre = genre.lowercase(Locale.ROOT)
            for (g in TELUGU_GENRES) {
                if (lowerGenre.contains(g)) {
                    return LanguageClassification(TELUGU, MetadataConfidence.HIGH)
                }
            }
            for (g in TAMIL_GENRES) {
                if (lowerGenre.contains(g)) {
                    return LanguageClassification(TAMIL, MetadataConfidence.HIGH)
                }
            }
            for (g in HINDI_GENRES) {
                if (lowerGenre.contains(g)) {
                    return LanguageClassification(HINDI, MetadataConfidence.HIGH)
                }
            }
            for (g in OTHER_GENRES) {
                if (lowerGenre.contains(g)) {
                    return LanguageClassification(OTHER, MetadataConfidence.HIGH)
                }
            }
        }

        // 3. Unicode script detection on Title (strongest track-level direct signal)
        var hasDevanagari = false
        var hasTeluguScript = false
        var hasTamilScript = false
        var hasOtherRegionalScript = false
        var hasOtherGlobalScript = false

        for (ch in cleanTitle) {
            val block = Character.UnicodeBlock.of(ch)
            if (block in HINDI_SCRIPTS) {
                hasDevanagari = true
                break
            } else if (block == Character.UnicodeBlock.TELUGU) {
                hasTeluguScript = true
                break
            } else if (block == Character.UnicodeBlock.TAMIL) {
                hasTamilScript = true
                break
            } else if (block in REGIONAL_INDIAN_SCRIPTS) {
                hasOtherRegionalScript = true
                break
            } else if (block in OTHER_GLOBAL_SCRIPTS) {
                hasOtherGlobalScript = true
                break
            }
        }

        if (hasDevanagari) {
            return LanguageClassification(HINDI, MetadataConfidence.HIGH)
        }
        if (hasTeluguScript) {
            return LanguageClassification(TELUGU, MetadataConfidence.HIGH)
        }
        if (hasTamilScript) {
            return LanguageClassification(TAMIL, MetadataConfidence.HIGH)
        }
        if (hasOtherRegionalScript || hasOtherGlobalScript) {
            return LanguageClassification(OTHER, MetadataConfidence.HIGH)
        }

        // 3. Track-level title token vocabulary analysis for Latin-script titles
        val words = cleanTitle
            .lowercase(Locale.ROOT)
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.isNotBlank() }

        if (words.isNotEmpty()) {
            var hindiCount = 0
            var teluguCount = 0
            var tamilCount = 0
            var regionalCount = 0
            var englishCount = 0

            for (w in words) {
                if (w in HINDI_TITLE_TOKENS) hindiCount++
                if (w in TELUGU_TITLE_TOKENS) teluguCount++
                if (w in TAMIL_TITLE_TOKENS) tamilCount++
                if (w in REGIONAL_TITLE_TOKENS) regionalCount++
                if (w in ENGLISH_TITLE_TOKENS) englishCount++
            }

            if (teluguCount > 0 && teluguCount >= hindiCount && teluguCount >= englishCount) {
                val conf = if (teluguCount >= 2) MetadataConfidence.HIGH else MetadataConfidence.MEDIUM
                return LanguageClassification(TELUGU, conf)
            }
            if (tamilCount > 0 && tamilCount >= hindiCount && tamilCount >= englishCount) {
                val conf = if (tamilCount >= 2) MetadataConfidence.HIGH else MetadataConfidence.MEDIUM
                return LanguageClassification(TAMIL, conf)
            }
            if (regionalCount > 0 && regionalCount >= hindiCount) {
                return LanguageClassification(OTHER, MetadataConfidence.HIGH)
            }
            if (hindiCount > 0 && hindiCount >= englishCount) {
                val conf = if (hindiCount >= 2) MetadataConfidence.HIGH else MetadataConfidence.MEDIUM
                return LanguageClassification(HINDI, conf)
            }
            if (englishCount > 0 && englishCount > hindiCount) {
                val conf = if (englishCount >= 2) MetadataConfidence.HIGH else MetadataConfidence.MEDIUM
                return LanguageClassification(ENGLISH, conf)
            }
        }

        // 4. Secondary folder/path heuristics only if title vocabulary was inconclusive
        if (!path.isNullOrBlank()) {
            val lowerPath = path.lowercase(Locale.ROOT)
            if (lowerPath.contains("/telugu/") || lowerPath.contains("\\telugu\\") || lowerPath.contains("tollywood")) {
                return LanguageClassification(TELUGU, MetadataConfidence.MEDIUM)
            }
            if (lowerPath.contains("/tamil/") || lowerPath.contains("\\tamil\\") || lowerPath.contains("kollywood")) {
                return LanguageClassification(TAMIL, MetadataConfidence.MEDIUM)
            }
            if (lowerPath.contains("/hindi/") || lowerPath.contains("\\hindi\\") || lowerPath.contains("bollywood")) {
                return LanguageClassification(HINDI, MetadataConfidence.MEDIUM)
            }
            if (lowerPath.contains("/malayalam/") || lowerPath.contains("\\malayalam\\") ||
                lowerPath.contains("/kannada/") || lowerPath.contains("\\kannada\\") ||
                lowerPath.contains("/punjabi/") || lowerPath.contains("\\punjabi\\")
            ) {
                return LanguageClassification(OTHER, MetadataConfidence.MEDIUM)
            }
            if (lowerPath.contains("/english/") || lowerPath.contains("\\english\\")) {
                return LanguageClassification(ENGLISH, MetadataConfidence.MEDIUM)
            }
        }

        // 5. Explicit metadata tags in album name
        if (album.isNotBlank()) {
            val lowerAlbum = album.lowercase(Locale.ROOT)
            if (lowerAlbum.contains("bollywood") || lowerAlbum.contains("hindi")) {
                return LanguageClassification(HINDI, MetadataConfidence.MEDIUM)
            }
        }

        // 6. Uncertain / ambiguous track -> UNKNOWN with LOW confidence
        return LanguageClassification(UNKNOWN, MetadataConfidence.LOW)
    }

    /**
     * Backward-compatible helper returning a String classification.
     */
    fun classify(
        title: String,
        artist: String = "",
        album: String = "",
        path: String? = null,
        genre: String? = null,
        embeddedLanguage: String? = null
    ): String {
        val result = classifyDetailed(title, artist, album, path, genre, embeddedLanguage)
        return result.language
    }
}
