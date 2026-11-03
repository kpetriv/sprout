package dev.kpetriv.sprout.core.domainhousehold

import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH

/** What `toString` prints in place of free text that may hold personal data. */
internal const val REDACTED: String = "<redacted>"

/** `toString` form of optional free text: `null` stays visible, any content is redacted. */
internal fun redacted(text: String?): String = if (text == null) "null" else REDACTED

/** A title: not blank, at most [MAX_TITLE_LENGTH] characters. */
internal fun requireTitle(kind: String, title: String) {
    require(title.isNotBlank()) { "$kind title must not be blank" }
    require(title.length <= MAX_TITLE_LENGTH) { "$kind title must be at most $MAX_TITLE_LENGTH characters" }
}
