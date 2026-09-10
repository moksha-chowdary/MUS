package com.mus.android.data.model

object MetadataSource {
    const val EMBEDDED = "EMBEDDED"
    const val EXTERNAL = "EXTERNAL"
    const val MERGED = "MERGED"
}

object MetadataStatus {
    const val COMPLETE = "COMPLETE"
    const val PARTIAL = "PARTIAL"
    const val ENRICHING = "ENRICHING"
    const val FAILED = "FAILED"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val NEEDS_LOOKUP = "NEEDS_LOOKUP"
}

object MetadataConfidence {
    const val HIGH = "HIGH"
    const val MEDIUM = "MEDIUM"
    const val LOW = "LOW"
}
