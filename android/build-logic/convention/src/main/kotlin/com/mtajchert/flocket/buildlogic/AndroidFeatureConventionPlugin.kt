package com.mtajchert.flocket.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A feature `:impl` module: library + Compose + Hilt, the UI stack (lifecycle, Navigation 3,
 * adaptive) and the shared core modules. A `:feature:X:impl` module also gets its own
 * `:feature:X:api` automatically.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("flocket.android.library")
            pluginManager.apply("flocket.android.compose")
            pluginManager.apply("flocket.hilt")

            Regex("""^:feature:([^:]+):impl$""").matchEntire(path)?.let {
                addDependency("implementation", project(":feature:${it.groupValues[1]}:api"))
            }
            addDependency("implementation", project(":core:designsystem"))
            addDependency("implementation", project(":core:ui"))
            addDependency("implementation", project(":core:model"))
            addDependency("implementation", project(":core:common"))
            addDependency("testImplementation", project(":core:testing"))

            addDependency("implementation", bundle("lifecycle"))
            addDependency("implementation", bundle("navigation3"))
            addDependency("implementation", bundle("adaptive"))
            addDependency("implementation", lib("androidx-hilt-lifecycle-viewmodel-compose"))
            addDependency("implementation", lib("kotlinx-coroutines-android"))
        }
}
