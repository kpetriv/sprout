package dev.kpetriv.sprout.feature.chat.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.chat.ContentResolverPickPhoto
import dev.kpetriv.sprout.feature.chat.PickPhoto

/**
 * Chat bindings that need nothing from the app: the photo reader. The app binds
 * [dev.kpetriv.sprout.feature.chat.ChatThreads], [dev.kpetriv.sprout.feature.chat.MessageReader],
 * [dev.kpetriv.sprout.feature.chat.MessageSender], [dev.kpetriv.sprout.feature.chat.ThreadKeyStatus],
 * [dev.kpetriv.sprout.feature.chat.BlobLoader], [dev.kpetriv.sprout.feature.chat.VoiceRecorder],
 * [dev.kpetriv.sprout.feature.chat.VoicePlayer] and [dev.kpetriv.sprout.feature.chat.OutgoingMessageFilter] (the
 * on-device kindness check, M6); `core/time`'s `Clock` comes from `core/common`.
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class ChatModule {
    @Binds
    abstract fun pickPhoto(impl: ContentResolverPickPhoto): PickPhoto
}
