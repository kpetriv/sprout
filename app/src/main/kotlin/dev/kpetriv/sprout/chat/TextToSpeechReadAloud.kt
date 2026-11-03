package dev.kpetriv.sprout.chat

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.kpetriv.sprout.core.ui.ReadAloud
import java.util.Locale

/**
 * [ReadAloud] over Android [TextToSpeech] in the device's locale (the kid's phone, or the parent's in kid mode).
 * The engine starts asynchronously: text asked for before it is ready is dropped (the kid taps again). Each call
 * replaces whatever is being spoken. Nothing is logged or stored here.
 *
 * **Where the text goes** (audit A2-L5, D-M4-23): the decrypted message text is handed to the phone's **system TTS
 * engine**, which the user (or the phone maker) chose; Sprout cannot see whether that engine synthesises on the
 * device. It asks for on-device synthesis wherever the platform lets it: it picks an installed voice of the locale's
 * language that needs no network connection ([Voice.isNetworkConnectionRequired] false, not
 * [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED]), highest quality first, and every utterance asks for embedded and
 * not network synthesis. An engine with no offline voice, or one that ignores those requests, may still send the text
 * to its own servers if the device is configured that way (`docs/PRIVACY.md`, THREAT_MODEL T37).
 */
class TextToSpeechReadAloud(context: Context) : ReadAloud {
    @Volatile
    private var ready = false
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) configure()
    }

    override fun speak(text: String) {
        if (ready && text.isNotBlank()) engine.speak(text, TextToSpeech.QUEUE_FLUSH, OFFLINE_PARAMS, UTTERANCE_ID)
    }

    fun shutdown() {
        ready = false
        engine.stop()
        engine.shutdown()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Some engines throw from getVoices().
    private fun configure() {
        val locale = Locale.getDefault()
        val offline = try {
            offlineVoice(engine.voices.orEmpty(), locale)
        } catch (expected: RuntimeException) {
            null
        }
        when {
            offline != null -> engine.voice = offline
            engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE -> engine.language = locale
        }
        engine.setSpeechRate(KID_SPEECH_RATE)
    }

    internal companion object {
        const val UTTERANCE_ID = "sprout-read-aloud"

        /** A little slower than normal speech, for early readers. */
        const val KID_SPEECH_RATE = 0.9f

        /** Every utterance asks for on-device synthesis (engines that honour the legacy feature keys do). */
        @Suppress("DEPRECATION") // The feature keys are deprecated in favour of Voice, which configure() also uses.
        private val OFFLINE_PARAMS: Bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        }

        /**
         * The best installed voice of [locale]'s language that needs no network: the exact locale first, then the
         * highest quality, then the lowest latency. Null when the engine offers none.
         */
        fun offlineVoice(voices: Collection<Voice>, locale: Locale): Voice? = voices
            .filter { voice ->
                !voice.isNetworkConnectionRequired && voice.locale.language == locale.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
            .sortedWith(
                compareByDescending<Voice> { it.locale == locale }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency },
            )
            .firstOrNull()
    }
}

/** A [TextToSpeechReadAloud] for as long as the caller is in composition; shut down when it leaves. */
@Composable
fun rememberReadAloud(): ReadAloud {
    val context = LocalContext.current
    val readAloud = remember(context) { TextToSpeechReadAloud(context) }
    DisposableEffect(readAloud) { onDispose { readAloud.shutdown() } }
    return readAloud
}
