package dev.kpetriv.sprout.feature.chat.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.chat.ContentResolverPickPhoto
import dev.kpetriv.sprout.feature.chat.OutgoingMessageFilter
import dev.kpetriv.sprout.feature.chat.PickPhoto

/**
 * Chat bindings that need nothing from the app: the photo reader and the no-op kindness filter (M6 replaces this
 * binding with the real check). The app binds [dev.kpetriv.sprout.feature.chat.ChatThreads],
 * [dev.kpetriv.sprout.feature.chat.MessageReader], [dev.kpetriv.sprout.feature.chat.MessageSender],
 * [dev.kpetriv.sprout.feature.chat.ThreadKeyStatus], [dev.kpetriv.sprout.feature.chat.BlobLoader],
 * [dev.kpetriv.sprout.feature.chat.VoiceRecorder] and [dev.kpetriv.sprout.feature.chat.VoicePlayer];
 * `core/time`'s `Clock` comes from `core/common`.
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class ChatModule {
    @Binds
    abstract fun pickPhoto(impl: ContentResolverPickPhoto): PickPhoto

    companion object {
        @Provides
        fun outgoingMessageFilter(): OutgoingMessageFilter = OutgoingMessageFilter.None
    }
}
