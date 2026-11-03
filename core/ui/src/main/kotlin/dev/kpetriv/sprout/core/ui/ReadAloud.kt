package dev.kpetriv.sprout.core.ui

/**
 * Speaks text for kids who cannot read yet (Seedling). Kid-mode screens (wallet, chores) only call it; a
 * text-to-speech implementation arrives with the kid-mode accessibility work. With [None] the read-aloud buttons are
 * hidden.
 */
fun interface ReadAloud {
    fun speak(text: String)

    companion object {
        /** Does nothing; the default until a text-to-speech implementation is provided. */
        val None: ReadAloud = ReadAloud { }
    }
}
