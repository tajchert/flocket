package com.mtajchert.flocket.core.data

import javax.inject.Qualifier

/** The application-lifetime [kotlinx.coroutines.CoroutineScope] (SupervisorJob, Default dispatcher). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
