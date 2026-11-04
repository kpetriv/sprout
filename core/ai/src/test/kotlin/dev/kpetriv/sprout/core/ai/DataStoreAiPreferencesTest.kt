package dev.kpetriv.sprout.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreAiPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.preferences(file: File) = DataStoreAiPreferences(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { file },
    )

    @Test
    fun defaultsAreEnabledWithoutConsentAndChangesPersist() = runTest {
        val file = File(folder.root, "ai_preferences.preferences_pb")
        val preferences = preferences(file)
        assertEquals(AiSettings(enabled = true, downloadConsent = false), preferences.current())

        preferences.setDownloadConsent(true)
        preferences.setEnabled(false)
        assertEquals(AiSettings(enabled = false, downloadConsent = true), preferences.settings.first())
    }
}
