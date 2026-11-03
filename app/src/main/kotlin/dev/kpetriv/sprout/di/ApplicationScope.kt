package dev.kpetriv.sprout.di

import javax.inject.Qualifier

/** Qualifies the process-wide [kotlinx.coroutines.CoroutineScope] for work that must outlive any screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
