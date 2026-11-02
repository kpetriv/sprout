package dev.kpetriv.sprout.core.common

import javax.inject.Qualifier

/** Qualifies an injected CoroutineDispatcher. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: SproutDispatchers)

enum class SproutDispatchers {
    Default,
    IO,
}
