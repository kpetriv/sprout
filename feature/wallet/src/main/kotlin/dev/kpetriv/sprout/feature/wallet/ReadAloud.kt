package dev.kpetriv.sprout.feature.wallet

/**
 * Speaks text for kids who cannot read yet (Seedling). The wallet only calls it; a text-to-speech
 * implementation arrives with the kid-mode accessibility work.
 */
fun interface ReadAloud {
    fun speak(text: String)

    companion object {
        /** Does nothing; the default until a text-to-speech implementation is provided. */
        val None: ReadAloud = ReadAloud { }
    }
}
