package com.mtajchert.flocket.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Compose for an Android library: compiler plugin, BOM, bundles, debug tooling, Compose
 * Preview Screenshot Testing and Robolectric Compose test dependencies.
 * Apply after `flocket.android.library`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("flocket.android.library")
            configureCompose(extensions.getByType<CommonExtension>(), screenshot = true)
        }
}
