package dev.kpetriv.sprout.core.model

/** Longest title of a chore, reward or savings goal. */
const val MAX_TITLE_LENGTH: Int = 80

/** Longest chore description. */
const val MAX_DESCRIPTION_LENGTH: Int = 500

/** Longest submission note or rejection reason (equal to the ledger's free-text limit). */
const val MAX_NOTE_LENGTH: Int = 200

/** What `toString` prints in place of free text that may hold personal data. */
internal const val REDACTED: String = "<redacted>"

/** Optional free text of at most [max] characters. Blank is allowed (callers normalise it to null if they wish). */
internal fun requireTextLength(field: String, text: String?, max: Int) {
    require(text == null || text.length <= max) { "$field must be at most $max characters" }
}

/** `toString` form of optional free text: `null` stays visible, any content is redacted. */
internal fun redacted(text: String?): String = if (text == null) "null" else REDACTED
