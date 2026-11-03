package dev.kpetriv.sprout.feature.onboarding

import dev.kpetriv.sprout.core.model.DeviceId

/**
 * A complete link code as typed on the kid's phone: [LENGTH] characters from [ALPHABET] (no 0/O/1/I, M3 §1). Never
 * logged: [toString] hides it (T6).
 */
class LinkCodeInput private constructor(val value: String) {
    override fun toString(): String = "LinkCodeInput(<redacted>)"

    override fun equals(other: Any?): Boolean = other is LinkCodeInput && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val LENGTH: Int = 8
        const val ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** What the code field keeps of typed text: upper case, only alphabet characters, at most [LENGTH]. */
        fun normalise(typed: String): String = typed.uppercase().filter { it in ALPHABET }.take(LENGTH)

        /** The code when [text] is exactly a complete code, otherwise null. */
        fun parse(text: String): LinkCodeInput? =
            text.takeIf { it.length == LENGTH && it.all { char -> char in ALPHABET } }?.let(::LinkCodeInput)
    }
}

/** What both phones show for a device: its id's first 16 hex characters in groups of four ("ab12 cd34 ef56 7890"). */
fun displayFingerprint(deviceId: DeviceId): String =
    deviceId.value.take(FINGERPRINT_CHARS).chunked(GROUP).joinToString(" ")

private const val FINGERPRINT_CHARS = 16
private const val GROUP = 4
