package dev.kpetriv.sprout.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.kpetriv.sprout.core.ui.ReadAloud
import java.util.Locale

/**
 * [ReadAloud] over Android [TextToSpeech] in the device's locale (the kid's phone, or the parent's in kid mode).
 * The engine starts asynchronously: text asked for before it is ready is dropped (the kid taps again). Each call
 * replaces whatever is being spoken. Nothing is logged or stored; the engine runs on the device.
 */
class TextToSpeechReadAloud(context: Context) : ReadAloud {
    @Volatile
    private var ready = false
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) configure()
    }

    override fun speak(text: String) {
        if (ready && text.isNotBlank()) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        ready = false
        engine.stop()
        engine.shutdown()
    }

    private fun configure() {
        val locale = Locale.getDefault()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        engine.setSpeechRate(KID_SPEECH_RATE)
    }

    private companion object {
        const val UTTERANCE_ID = "sprout-read-aloud"

        /** A little slower than normal speech, for early readers. */
        const val KID_SPEECH_RATE = 0.9f
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
