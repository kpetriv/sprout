package dev.kpetriv.sprout.chat

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice note temp files (audit A2-M2), in-memory playback and the offline read-aloud voice (audit A2-L5). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class ChatMediaTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the start-up sweep deletes leftover recordings and playback files and nothing else`() {
        val directory = File(context.cacheDir, "sweep-${UUID.randomUUID()}").apply { mkdirs() }
        val leftovers = listOf("voice123.m4a", "play456.m4a").map {
            File(directory, it).apply { writeBytes(byteArrayOf(1)) }
        }
        val kept = listOf("voice-notes.txt", "photo.jpg", "playlist.json").map {
            File(directory, it).apply { writeBytes(byteArrayOf(2)) }
        }
        val folder = File(directory, "voice-dir.m4a").apply { mkdirs() }

        assertEquals(2, VoiceTempFiles.sweep(directory))

        assertTrue(leftovers.none { it.exists() })
        assertTrue(kept.all { it.exists() })
        assertTrue(folder.isDirectory, "only files are swept")
        directory.deleteRecursively()
    }

    @Test
    fun `a new recording file lives in cacheDir and is swept at the next start`() {
        val files = VoiceTempFiles(context)
        val recording = files.newRecording()
        recording.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(context.cacheDir.canonicalFile, recording.parentFile?.canonicalFile)

        assertTrue(files.sweep() >= 1)
        assertFalse(recording.exists())
    }

    @Test
    fun `a decrypted clip is read from memory`() {
        val clip = ByteArray(10) { it.toByte() }
        val source = ByteArrayMediaDataSource(clip)
        val buffer = ByteArray(8)

        assertEquals(10L, source.size)
        assertEquals(4, source.readAt(6, buffer, 2, 6))
        assertContentEquals(byteArrayOf(6, 7, 8, 9), buffer.copyOfRange(2, 6))
        assertEquals(-1, source.readAt(10, buffer, 0, 8))
        assertEquals(-1, source.readAt(-1, buffer, 0, 8))
        assertEquals(3, source.readAt(0, buffer, 0, 3))
        source.close()
        assertContentEquals(ByteArray(10) { it.toByte() }, clip, "the caller's bytes are left alone")
    }

    @Test
    fun `read-aloud prefers an installed offline voice of the language`() {
        val uk = Locale.UK
        val network = voice("net", uk, Voice.QUALITY_VERY_HIGH, network = true)
        val notInstalled = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        val missing = voice("missing", uk, Voice.QUALITY_VERY_HIGH, features = notInstalled)
        val usLow = voice("us-low", Locale.US, Voice.QUALITY_LOW)
        val ukNormal = voice("uk-normal", uk, Voice.QUALITY_NORMAL)
        val french = voice("fr", Locale.FRANCE, Voice.QUALITY_VERY_HIGH)

        val chosen = TextToSpeechReadAloud.offlineVoice(listOf(network, missing, usLow, ukNormal, french), uk)

        assertEquals("uk-normal", chosen?.name)
        assertEquals("us-low", TextToSpeechReadAloud.offlineVoice(listOf(network, usLow, french), uk)?.name)
        assertNull(TextToSpeechReadAloud.offlineVoice(listOf(network, missing, french), uk))
    }

    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        network: Boolean = false,
        features: Set<String> = emptySet(),
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)
}
