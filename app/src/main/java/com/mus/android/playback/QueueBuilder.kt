package com.mus.android.playback

import com.mus.android.data.classifier.LanguageClassifier
import com.mus.android.data.model.Track

enum class QueueSource {
    DEFAULT,
    ALBUM,
    ARTIST,
    PLAYLIST,
    LANGUAGE_MIX,
    SEARCH,
}

data class QueueContext(
    val source: QueueSource = QueueSource.DEFAULT,
    val sourceLanguage: String? = null,
    val sourceId: String? = null,
)

object QueueBuilder {

    /**
     * Builds a shuffled queue from the eligible language mix of the selected track,
     * placing the selected track strictly at index 0 as the current track.
     *
     * Rules:
     * - Uses the ACTUAL stored Track.language (no re-inferring, no folder lookups).
     * - Candidates matching the language/category are collected.
     * - Unknown language falls back to the full library pool if the unknown mix is small.
     * - Remaining candidates are shuffled.
     * - Selected track is placed at index 0 and does not appear twice.
     * - Queue order is fixed once created.
     */
    fun buildLanguageMixQueue(
        selectedTrack: Track,
        allTracks: List<Track>,
        rng: java.util.Random? = null
    ): List<Track> {
        val rawLang = selectedTrack.language.trim()

        val pool: List<Track> = when {
            rawLang.equals(LanguageClassifier.HINDI, ignoreCase = true) -> {
                allTracks.filter { it.language.equals(LanguageClassifier.HINDI, ignoreCase = true) }
            }
            rawLang.equals(LanguageClassifier.ENGLISH, ignoreCase = true) -> {
                allTracks.filter { it.language.equals(LanguageClassifier.ENGLISH, ignoreCase = true) }
            }
            rawLang.equals("Telugu", ignoreCase = true) -> {
                val teluguOnly = allTracks.filter { it.language.equals("Telugu", ignoreCase = true) }
                if (teluguOnly.isNotEmpty()) {
                    teluguOnly
                } else {
                    allTracks.filter {
                        it.language.equals(LanguageClassifier.OTHER, ignoreCase = true) ||
                        it.language.equals("Regional", ignoreCase = true)
                    }
                }
            }
            rawLang.equals(LanguageClassifier.OTHER, ignoreCase = true) ||
            rawLang.equals("Regional", ignoreCase = true) -> {
                allTracks.filter {
                    it.language.equals(LanguageClassifier.OTHER, ignoreCase = true) ||
                    it.language.equals("Regional", ignoreCase = true) ||
                    it.language.equals("Telugu", ignoreCase = true) ||
                    it.language.equals("Tamil", ignoreCase = true) ||
                    it.language.equals("Malayalam", ignoreCase = true) ||
                    it.language.equals("Kannada", ignoreCase = true)
                }
            }
            rawLang.equals(LanguageClassifier.UNKNOWN, ignoreCase = true) || rawLang.isBlank() -> {
                val unknownTracks = allTracks.filter {
                    it.language.equals(LanguageClassifier.UNKNOWN, ignoreCase = true) || it.language.isBlank()
                }
                if (unknownTracks.size <= 1 && allTracks.size > 1) {
                    allTracks
                } else {
                    unknownTracks
                }
            }
            else -> {
                val matches = allTracks.filter { it.language.equals(rawLang, ignoreCase = true) }
                if (matches.isNotEmpty()) matches else allTracks
            }
        }

        // Candidate pool must contain selectedTrack
        val poolWithSelected = if (pool.any { it.id == selectedTrack.id }) pool else pool + selectedTrack

        // Shuffled pool excluding selected track
        val remainingCandidates = poolWithSelected.filter { it.id != selectedTrack.id }
        val remainingShuffled = if (rng != null) {
            remainingCandidates.shuffled(rng)
        } else {
            remainingCandidates.shuffled()
        }

        // Selected track is placed at index 0 (CURRENT TRACK)
        return listOf(selectedTrack) + remainingShuffled
    }

    /**
     * Extracts queue context metadata for tracking playback queue origin.
     */
    fun getQueueContextForLanguageMix(track: Track): QueueContext {
        val rawLang = track.language.trim()
        val langCategory = when {
            rawLang.equals(LanguageClassifier.HINDI, ignoreCase = true) -> "HINDI"
            rawLang.equals(LanguageClassifier.ENGLISH, ignoreCase = true) -> "ENGLISH"
            rawLang.equals("Telugu", ignoreCase = true) -> "TELUGU"
            rawLang.equals(LanguageClassifier.OTHER, ignoreCase = true) ||
            rawLang.equals("Regional", ignoreCase = true) -> "OTHER"
            rawLang.equals(LanguageClassifier.UNKNOWN, ignoreCase = true) || rawLang.isBlank() -> "UNKNOWN"
            else -> rawLang.uppercase()
        }
        return QueueContext(
            source = QueueSource.LANGUAGE_MIX,
            sourceLanguage = langCategory,
            sourceId = track.id.toString(),
        )
    }
}
